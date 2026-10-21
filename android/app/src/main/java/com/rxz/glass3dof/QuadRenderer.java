package com.rxz.glass3dof;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 把手机屏幕画面画成一块「钉在空间里的屏幕」。
 *
 * 相机旋转来自 HeadPose 的视图矩阵；屏幕四边形固定在场景 +Z 方向 distance 米处，
 * 因此转头时它在视野里反向移动 —— 这就是 3DoF 悬停的视觉本质。
 *
 * 坐标约定：场景系 = 复位那一刻的机体系，+Z 为朝向、+Y 为上。
 * 因为相机朝 +Z 看（而不是 GL 默认的 -Z），屏幕右侧对应场景 -X，
 * 所以纹理 U 方向需要取反（见 VERTICES）。
 *
 * 资源生命周期（旧实现完全没管，反复启停会泄漏）：
 *   - release() 由会话收尾调用：停掉帧回调线程、释放 Surface/SurfaceTexture、
 *     通知 CaptureService 清掉 pendingSurface 与 VirtualDisplay；
 *   - program/texture 的删除在 GL 线程执行（onDrawFrame 看到 released 标记时），
 *     不在任意线程调 glDelete*。
 */
public class QuadRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "QuadRenderer";

    private static final float[] VERTICES = {
            // x,  y,  z,     u,  v
            -1f, -1f, 0f, 1f, 0f,
            1f, -1f, 0f, 0f, 0f,
            -1f, 1f, 0f, 1f, 1f,
            1f, 1f, 0f, 0f, 1f,
    };

    private static final String VS =
            "uniform mat4 uMvp;\n" +
            "uniform mat4 uUvMat;\n" +
            "attribute vec4 aPos;\n" +
            "attribute vec2 aUv;\n" +
            "varying vec2 vUv;\n" +
            "varying vec2 vPos;\n" +
            "void main() {\n" +
            "  gl_Position = uMvp * aPos;\n" +
            "  vPos = aUv;\n" +
            "  vUv = (uUvMat * vec4(aUv, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vUv;\n" +
            "varying vec2 vPos;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "uniform float uSolid;\n" +
            "void main() {\n" +
            "  if (uSolid > 1.5) {\n" +                     // 光标小点：圆形、带柔边
            "    float d = length(vPos - vec2(0.5));\n" +
            "    float a = smoothstep(0.5, 0.40, d);\n" +
            "    if (a < 0.02) discard;\n" +
            "    gl_FragColor = vec4(1.0, 0.86, 0.20, a);\n" +
            "  } else if (uSolid > 0.5) {\n" +             // 无画面时的底板
            "    gl_FragColor = vec4(0.10, 0.12, 0.16, 1.0);\n" +
            "  } else {\n" +
            "    gl_FragColor = texture2D(uTex, vUv);\n" +
            "  }\n" +
            "}\n";

    /** 画面参数改由 Stage 提供（手机端滑块实时写），这里不再持有副本。 */
    private int lastCapW, lastCapH;
    private float contentAspect;

    public volatile int surfaceW, surfaceH;

    private final HeadPose pose;
    private final int capW, capH;

    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] model = new float[16];
    private final float[] vp = new float[16];
    private final float[] mvp = new float[16];
    private final float[] uvMat = new float[16];

    private FloatBuffer vertexBuf;
    private int program;
    private int aPos, aUv, uMvp, uUvMat, uTex, uSolid;

    private int textureId;
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    /** 已交接给 CaptureService / ExtendHost 的那个 Surface（release 时要通知对方清引用）。 */
    private Surface handedSurface;
    private final HandlerThread cbThread;
    private final Handler cbHandler;
    private volatile boolean newFrame;
    private volatile boolean released;

    public QuadRenderer(HeadPose pose, int capW, int capH) {
        this.pose = pose;
        this.capW = capW;
        this.capH = capH;
        this.contentAspect = capW / (float) capH;

        ByteBuffer bb = ByteBuffer.allocateDirect(VERTICES.length * 4);
        bb.order(ByteOrder.nativeOrder());
        vertexBuf = bb.asFloatBuffer();
        vertexBuf.put(VERTICES);
        vertexBuf.position(0);

        cbThread = new HandlerThread("st-cb");
        cbThread.start();
        cbHandler = new Handler(cbThread.getLooper());
    }

    /** 会话收尾：停帧回调、释放表面、通知捕获侧清引用。GL 对象在 GL 线程删（见 onDrawFrame）。 */
    public void release() {
        if (released) {
            return;
        }
        released = true;
        if (surfaceTexture != null) {
            try {
                surfaceTexture.setOnFrameAvailableListener(null);
                surfaceTexture.release();
            } catch (Exception ignored) {
            }
            surfaceTexture = null;
        }
        if (handedSurface != null) {
            // 把捕获服务里的 pendingSurface / VirtualDisplay 一并清掉，
            // 否则下一会话服务启动时会拿这个已失效的表面去建屏（旧实现漏了这一步）
            CaptureService.clearSurface(handedSurface);
            handedSurface = null;
        }
        if (surface != null) {
            try {
                surface.release();
            } catch (Exception ignored) {
            }
            surface = null;
        }
        cbThread.quitSafely();
    }

    // ------------------------------------------------------------ GL 回调

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Log.i(TAG, "onSurfaceCreated");
        // 上下文重建（或上次没走完 release）时，把同上下文里残留的 GL 对象删干净再重建
        deleteGlObjects();

        program = buildProgram();
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aUv = GLES20.glGetAttribLocation(program, "aUv");
        uMvp = GLES20.glGetUniformLocation(program, "uMvp");
        uUvMat = GLES20.glGetUniformLocation(program, "uUvMat");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        uSolid = GLES20.glGetUniformLocation(program, "uSolid");

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        textureId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setDefaultBufferSize(capW, capH);
        surfaceTexture.setOnFrameAvailableListener(this, cbHandler);
        surface = new Surface(surfaceTexture);

        // 交给捕获服务：它会在主线程建 VirtualDisplay
        if (Stage.extendMode) {
            // 扩展屏：由本应用自建私有虚拟屏，内容是自己启动的 Activity / 目标应用
            ExtendHost.onSurface(Stage.appContext, surface, capW, capH, Stage.pendingComponent);
            handedSurface = surface;
        } else {
            CaptureService.setSurface(surface, capW, capH);
            handedSurface = surface;
        }

        Matrix.setIdentityM(uvMat, 0);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
    }

    private void deleteGlObjects() {
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
            textureId = 0;
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        Log.i(TAG, "onSurfaceChanged " + width + "x" + height);
        surfaceW = width;
        surfaceH = height;
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (released) {
            deleteGlObjects();
            return;
        }
        if (newFrame && surfaceTexture != null) {
            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(uvMat);
                hasContent = true;
                textureFrames++;
            } catch (Exception e) {
                Log.w(TAG, "updateTexImage: " + e.getMessage());
            }
            newFrame = false;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // 首帧可能还没跑 onSurfaceChanged，除零会得到 Inf 矩阵，先兜底
        int w = surfaceW > 0 ? surfaceW : capW;
        int h = surfaceH > 0 ? surfaceH : capH;
        if (w <= 0 || h <= 0) {
            return;
        }
        float aspect = w / (float) h;

        // 捕获尺寸会随手机横竖屏变化（CaptureService 会 resize 虚拟屏），
        // 这里跟随更新缓冲区尺寸与内容宽高比，横屏内容才能真的横过来。
        int cw = CaptureService.capW > 0 ? CaptureService.capW : capW;
        int ch = CaptureService.capH > 0 ? CaptureService.capH : capH;
        if ((cw <= 0 || ch <= 0) && Stage.appContext != null) {
            int[] s = CaptureSize.of(Stage.appContext);
            cw = s[0];
            ch = s[1];
        }
        if (cw > 0 && ch > 0 && (cw != lastCapW || ch != lastCapH)) {
            lastCapW = cw;
            lastCapH = ch;
            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(cw, ch);
            }
            contentAspect = cw / (float) ch;
        }
        contentAspectNow = contentAspect;

        // 兜底：有些 ROM 在应用退到后台后不再给我们 display 变更回调，
        // 这里每 30 帧自查一次捕获尺寸，发现横竖屏变了就主动让服务 resize。
        if (!Stage.extendMode && ++rotateCheckTick >= 30) {
            rotateCheckTick = 0;
            if (Stage.appContext != null && CaptureService.isRunning()) {
                int[] want = CaptureSize.of(Stage.appContext);
                if (want[0] != CaptureService.capW || want[1] != CaptureService.capH) {
                    CaptureService.refreshSize(Stage.appContext);
                }
            }
        }

        boolean ready = pose.getViewMatrix(view);
        float fovYDeg = Stage.fovYDeg;
        float d = Stage.distanceM;
        float fill = Stage.fill;
        Matrix.perspectiveM(proj, 0, fovYDeg, aspect, 0.1f, 100f);

        // 屏幕形状跟随内容方向（竖屏内容→竖屏、横屏内容→横屏），
        // 但两个方向各自按"长边填满"来定尺寸，避免横屏时被压成一条：
        //   竖屏：高度填满，宽度按比例（超宽时再收敛）
        //   横屏：宽度填满，高度按比例（超高时再收敛）
        float tanV = (float) Math.tan(Math.toRadians(fovYDeg) / 2.0);
        float tanH = tanV * aspect;
        float fullH = 2f * d * tanV * 0.95f * fill;
        float fullW = 2f * d * tanH * 0.95f * fill;
        float sh, sw;
        if (contentAspect <= 1f) {
            sh = fullH;
            sw = sh * contentAspect;
            if (sw > fullW) {
                sw = fullW;
                sh = sw / contentAspect;
            }
        } else {
            sw = fullW;
            sh = sw / contentAspect;
            if (sh > fullH) {
                sh = fullH;
                sw = sh * contentAspect;
            }
        }

        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, 0f, 0f, d);
        Matrix.scaleM(model, 0, sw / 2f, sh / 2f, 1f);

        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);

        if (program == 0) {
            return;
        }
        GLES20.glUseProgram(program);

        vertexBuf.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, vertexBuf);
        GLES20.glEnableVertexAttribArray(aPos);
        vertexBuf.position(3);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 20, vertexBuf);
        GLES20.glEnableVertexAttribArray(aUv);

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uUvMat, 1, false, uvMat, 0);

        // 有画面就直接显示（哪怕姿态还没就绪），这样"灰屏"只代表一件事：捕获没进来
        boolean showTexture = hasContent;
        GLES20.glUniform1f(uSolid, showTexture ? 0f : 1f);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(uTex, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 触控板光标：画在屏幕平面稍前一点，避免被面板挡住
        if (Stage.cursorVisible) {
            // 面板的 +X 在观察者看来是"左"（相机朝 +Z 看，场景 +X 落在屏幕左侧），
            // 所以光标的横向坐标要反过来算，否则手指向右移、小点向左跑。
            float cxp = (0.5f - Stage.cursorU) * sw;
            float cyp = (0.5f - Stage.cursorV) * sh;
            float r = sh * 0.014f;
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, cxp, cyp, d - 0.005f);
            Matrix.scaleM(model, 0, r, r, 1f);
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);

            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUniform1f(uSolid, 2f);
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glUniform1f(uSolid, showTexture ? 0f : 1f);
        }

        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aUv);
    }

    private boolean hasContent;

    /** 累计收到的投屏帧数（跨实例，供界面诊断"有没有帧"用）。 */
    public static volatile long textureFrames;

    /** 当前内容宽高比（>1 横屏，<1 竖屏），供界面显示。 */
    public static volatile float contentAspectNow = 1f;

    private int rotateCheckTick;

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        if (!released) {
            newFrame = true;
        }
    }

    // ------------------------------------------------------------ 着色器

    private static int buildProgram() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VS);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
        if (vs == 0 || fs == 0) {
            return 0;
        }
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "link: " + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "compile: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }
}
