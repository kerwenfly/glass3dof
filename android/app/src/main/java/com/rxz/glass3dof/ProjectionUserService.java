package com.rxz.glass3dof;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Process;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

/**
 * 在 Shizuku 的 shell 进程里运行。
 *
 * 由 {@link TrustedProjection} 通过 {@code Shizuku.bindUserService} 拉起：
 * Shizuku 会 new 出这个类并把实例当作 binder 使用，所以必须**直接继承 Stub**，
 * 不能是 android.app.Service（那样不是 IBinder，绑定永远超时）。
 *
 * 这里以 shell 身份调 {@code DisplayManager.createVirtualDisplay}，
 * 才能带 VIRTUAL_DISPLAY_FLAG_TRUSTED —— 系统会把它当成真实外接显示器，
 * 那些"只认默认屏/受信屏"的应用才会渲染。
 */
public class ProjectionUserService extends IProjectionService.Stub {

    private static final String TAG = "ProjectionUS";

    // 只存在于 Shizuku 的 shell 进程（app_process），不是应用进程，不存在泄漏问题
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static Context appCtx;
    private static String ctxError = "";

    private VirtualDisplay vd;
    private String lastError = "";

    /** 依次尝试的 flag 组合：受信优先，最后一组是"连普通公共屏都不行吗"的对照。 */
    private static final int[] FLAG_CANDIDATES = {
            1 | 8 | (1 << 10),              // PUBLIC | OWN_CONTENT_ONLY | TRUSTED
            1 | (1 << 10),                  // PUBLIC | TRUSTED
            1 | 8 | (1 << 10) | (1 << 8),   // + OWN_DISPLAY_GROUP
            (1 << 10),                      // TRUSTED
            1 | 8,                          // PUBLIC | OWN_CONTENT_ONLY（对照）
    };

    @Override
    public int createDisplay(int width, int height, int dpi, int flags, Surface surface) {
        release();
        lastError = "";
        Context ctx = context();
        if (ctx == null) {
            lastError = "no-context: " + ctxError;
            return -1;
        }
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            lastError = "no-display-manager";
            return -1;
        }
        StringBuilder errors = new StringBuilder();
        // 客户端指定的 flag 组合优先尝试（旧实现完全忽略 flags 形参，接口承诺与行为不一致），
        // 然后再跑内置候选列表兜底
        int[] candidates = FLAG_CANDIDATES;
        if (flags != 0) {
            candidates = new int[FLAG_CANDIDATES.length + 1];
            candidates[0] = flags;
            System.arraycopy(FLAG_CANDIDATES, 0, candidates, 1, FLAG_CANDIDATES.length);
        }
        for (int f : candidates) {
            String err = tryCreate(dm, width, height, dpi, f, surface);
            if (err == null) {
                Display d = vd == null ? null : vd.getDisplay();
                int id = d == null ? -1 : d.getDisplayId();
                lastError = "";
                Log.i(TAG, "flags=0x" + Integer.toHexString(f) + " -> " + id
                        + " trusted=" + isTrusted(d));
                return id;
            }
            errors.append("0x").append(Integer.toHexString(f)).append(':').append(err).append(" | ");
        }
        lastError = errors.toString();
        Log.e(TAG, "all flag sets failed: " + lastError);
        return -1;
    }

    private String tryCreate(DisplayManager dm, int w, int h, int dpi, int flags, Surface surface) {
        try {
            VirtualDisplay d = dm.createVirtualDisplay("glass3dof-trusted", w, h, dpi, surface, flags);
            if (d == null) {
                return "null";
            }
            vd = d;
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ":" + t.getMessage();
        }
    }

    @Override
    public void releaseDisplay() {
        release();
    }

    @Override
    public int getDisplayId() {
        Display d = vd == null ? null : vd.getDisplay();
        return d == null ? -1 : d.getDisplayId();
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public String info() {
        context();
        return "uid=" + Process.myUid()
                + " ctx=" + (appCtx != null ? "ok" : "null(" + ctxError + ")")
                + " vd=" + getDisplayId();
    }

    @Override
    public void destroy() {
        release();
        System.exit(0);
    }

    private void release() {
        if (vd != null) {
            try {
                vd.release();
            } catch (Throwable ignored) {
            }
            vd = null;
        }
    }

    /**
     * shell 进程里没有 Activity，得自己造一个 system context。
     * 依次尝试：已有线程 → systemMain()。
     * 反射 ActivityThread 属私有 API，是 Shizuku UserService 在 shell 进程里的既定做法，
     * 风险已知（新 ROM 上可能失效，届时 info() 会报 ctx=null）。
     */
    @android.annotation.SuppressLint("PrivateApi")
    private static synchronized Context context() {
        if (appCtx != null) {
            return appCtx;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = null;
            try {
                thread = at.getMethod("currentActivityThread").invoke(null);
            } catch (Throwable ignored) {
            }
            if (thread == null) {
                thread = at.getMethod("systemMain").invoke(null);
            }
            if (thread != null) {
                Context sys = (Context) at.getMethod("getSystemContext").invoke(thread);
                // 关键：system context 的包名是 "android"，而当前进程 uid=2000(shell)，
                // DisplayManagerService 会校验 packageName 与调用 uid 是否匹配，
                // 不换包上下文就会抛 "packageName must match the calling uid"。
                try {
                    appCtx = sys.createPackageContext("com.android.shell",
                            Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
                } catch (Throwable t) {
                    ctxError = "createPackageContext: " + t;
                    appCtx = sys;
                }
            }
        } catch (Throwable t) {
            ctxError = t.toString();
            Log.e(TAG, "取 system context 失败", t);
        }
        return appCtx;
    }

    /** Display.isTrusted() 是 @hide，只能反射。 */
    private static boolean isTrusted(Display d) {
        if (d == null) {
            return false;
        }
        try {
            Object r = Display.class.getMethod("isTrusted").invoke(d);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }
}
