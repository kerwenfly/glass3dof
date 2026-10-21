package com.rxz.glass3dof;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

/**
 * 普通扩展屏（无沙箱、无 BlackBox、无 MediaProjection）。
 *
 * 本应用自己创建一块**私有虚拟屏**（owner = 自己），然后：
 *  - 任意应用模式：用 {@code ActivityOptions.setLaunchDisplayId} 把目标应用启动上去。
 *    系统对"在虚拟屏上启动 Activity"的校验看的是**调用方是不是这块屏的 owner**，
 *    owner 就是我们，所以这一步**不需要 Shizuku**。
 *    只有"把触摸送到另一块屏"才需要 shell 权限；没 Shizuku 时降级为只看不动。
 *
 * 若目标应用不支持多屏、被系统重定向回默认屏，表现是扩展屏一直收不到帧；
 * 这时如果 Shizuku 可用，会自动改用 `am start --display` 再试一次。
 */
public final class ExtendHost {

    private static final String TAG = "ExtendHost";
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static volatile int displayId = -1;
    /** 当前扩展屏是不是本应用自己建的（决定能不能用 owner 身份启动 Activity）。 */
    private static volatile boolean localOwned;

    private static VirtualDisplay vd;

    private ExtendHost() {
    }

    /** GL 线程创建好 SurfaceTexture 后调用；targetComponent 为 null 表示内置浏览器。 */
    public static void onSurface(Context ctx, Surface surface, int w, int h, String targetComponent) {
        // 进程重启等极端情况下 Stage.appContext 可能为空，直接崩在 GL 线程太脆
        if (ctx == null) {
            Stage.log("扩展屏缺少上下文，跳过建屏");
            return;
        }
        Context app = ctx.getApplicationContext();
        main.post(() -> create(app, surface, w, h, targetComponent));
    }

    private static void create(Context ctx, Surface surface, int w, int h, String target) {
        release();

        // 任意应用模式：优先用 Shizuku UserService 建"受信屏"。
        // 受信屏对应用来说等同真实外接显示器，能救回那些"只认默认屏"的应用。
        if (target != null && Stage.useTrustedDisplay) {
            Stage.log("受信屏分支：等待 Shizuku（当前 ready=" + ShizukuBridge.ready(ctx) + "）");
            ShizukuBridge.awaitReady(ctx, 3000, ok -> {
                if (!ok) {
                    Stage.log("Shizuku 未就绪，退回本地私有屏（第三方应用可能被重定向回手机屏）");
                    createLocal(ctx, surface, w, h, target);
                    return;
                }
                Stage.log("尝试受信屏（Shizuku UserService）…");
                TrustedProjection.createDisplay(ctx, w, h, 240,
                        TrustedProjection.TRUSTED_FLAGS, surface, id -> {
                            if (id > 0) {
                                displayId = id;
                                localOwned = false;
                                Stage.log("受信屏已创建 display=" + id);
                                launch(ctx, id, target);
                                watchFrames(id);
                            } else {
                                Stage.log("受信屏不可用，回退本地虚拟屏");
                                createLocal(ctx, surface, w, h, target);
                            }
                        });
            });
            return;
        }
        createLocal(ctx, surface, w, h, target);
    }

    private static void createLocal(Context ctx, Surface surface, int w, int h, String target) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            vd = dm.createVirtualDisplay("glass3dof-ext", w, h, 240, surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            if (vd == null || vd.getDisplay() == null) {
                Stage.log("扩展屏创建返回空");
                Log.e(TAG, "createVirtualDisplay 返回空");
                return;
            }
            displayId = vd.getDisplay().getDisplayId();
            localOwned = true;
            Stage.log("扩展屏已创建 display=" + displayId + " " + w + "x" + h
                    + " trusted=" + DisplayUtils.isTrusted(vd.getDisplay()));
            Log.i(TAG, "扩展屏已创建 display=" + displayId + " " + w + "x" + h
                    + (target == null ? "（内置浏览器）" : "（" + target + "）"));
            launch(ctx, displayId, target);
            watchFrames(displayId);
        } catch (Exception e) {
            Stage.log("创建扩展屏失败：" + e);
            Log.e(TAG, "创建扩展屏失败", e);
        }
    }

    /** 2.5 秒还没帧就把情况写进诊断，便于判断是"没启动"还是"启动了不渲染"。 */
    private static void watchFrames(final int expectId) {
        final long base = QuadRenderer.textureFrames;
        main.postDelayed(() -> {
            if (expectId != displayId) {
                return;
            }
            long got = QuadRenderer.textureFrames - base;
            if (got > 3) {
                Stage.log("扩展屏开始出帧（+" + got + "）");
            } else {
                Stage.log("扩展屏 2.5 秒无帧（display " + expectId + "）：目标可能不支持多屏或被重定向");
            }
        }, 2500);
    }

    /**
     * 把目标应用启动到指定 display。优先 Shizuku `am start --display`（最可靠），
     * 2.5 秒内没出帧就换另一种方式重试一次。
     */
    public static void launch(Context ctx, int displayId, String component) {
        final String[] parts = component.split("/");
        final String pkg = parts[0];
        final String cls = parts.length > 1 ? parts[1] : null;

        Intent i = null;
        if (cls != null) {
            i = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, cls);
        }
        if (i == null) {
            i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        }
        if (i == null) {
            Stage.log("找不到启动入口: " + component);
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        final Intent launchIntent = i;

        final long baseFrames = QuadRenderer.textureFrames;
        final int expectId = displayId;

        // 有 Shizuku 时优先用 am start --display：这条路最可靠（等同 shell 手动启动）。
        // Binder + fork 是阻塞调用，走 ShizukuBridge 的 IO 线程，不占 UI。
        final boolean viaShizuku = ShizukuBridge.ready(ctx);
        if (viaShizuku) {
            Stage.log("用 Shizuku 启动到 display " + displayId);
            ShizukuBridge.launchOnDisplay(pkg, cls, displayId, ok ->
                    Stage.log("am start --display 结果：" + (ok ? "已提交" : "失败")));
        } else {
            startOn(ctx, launchIntent, displayId, component);
        }

        // 校验：2.5 秒内扩展屏一帧都没来 → 换另一种方式再试一次
        main.postDelayed(() -> {
            if (expectId != displayId) {
                return;
            }
            if (QuadRenderer.textureFrames - baseFrames > 3) {
                Stage.log("目标应用已在扩展屏上渲染");
                return;
            }
            Stage.log("扩展屏 2.5 秒无帧，目标应用可能被重定向");
            if (viaShizuku) {
                if (localOwned) {
                    Stage.log("再用 owner 身份 setLaunchDisplayId 试一次");
                    startOn(ctx, launchIntent, displayId, component);
                } else {
                    Stage.log("该屏由 shell 拥有，只能靠 am start --display");
                }
            } else if (ShizukuBridge.ready(ctx)) {
                Stage.log("改用 Shizuku am start --display 再试");
                ShizukuBridge.launchOnDisplay(pkg, cls, displayId, null);
            }
        }, 2500);
    }

    private static boolean startOn(Context ctx, Intent intent, int displayId, String tag) {
        // 优先用眼镜端画面 Activity 作为 launcher：它一定处在前台，绕过后台启动限制
        Context launcher = Stage.activity() != null ? Stage.activity() : ctx;
        Bundle opts = null;
        try {
            opts = android.app.ActivityOptions.makeBasic()
                    .setLaunchDisplayId(displayId)
                    .toBundle();
        } catch (Exception e) {
            Log.w(TAG, "构造启动参数失败", e);
        }
        try {
            launcher.startActivity(intent, opts);
            Stage.log("已启动 " + tag + " → display " + displayId
                    + "（launcher=" + launcher.getClass().getSimpleName() + "）");
            Log.i(TAG, "已启动 " + tag + " → display " + displayId);
            return true;
        } catch (Exception e) {
            Stage.log("启动 " + tag + " 失败：" + e);
            Log.e(TAG, "启动 " + tag + " 失败", e);
            return false;
        }
    }

    public static void release() {
        displayId = -1;
        TrustedProjection.release();
        if (vd != null) {
            try {
                vd.release();
            } catch (Exception ignored) {
            }
            vd = null;
        }
    }
}
