package com.rxz.glass3dof;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

/** 眼镜那块屏上的 GL 视图：由 Presentation 或 StageActivity 承载。 */
public class ScreenGlView extends GLSurfaceView {

    private final QuadRenderer renderer;

    public ScreenGlView(Context ctx, HeadPose pose) {
        super(ctx);
        renderer = init(pose);
    }

    // 只在代码里构建；2 参构造器是为满足 lint ViewConstructor / 布局编辑器
    public ScreenGlView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        renderer = init(new HeadPose());
    }

    private QuadRenderer init(HeadPose pose) {
        setEGLContextClientVersion(2);
        int[] cap = CaptureSize.of(getContext());
        QuadRenderer r = new QuadRenderer(pose, cap[0], cap[1]);
        setRenderer(r);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setPreserveEGLContextOnPause(true);
        return r;
    }

    public QuadRenderer renderer() {
        return renderer;
    }
}
