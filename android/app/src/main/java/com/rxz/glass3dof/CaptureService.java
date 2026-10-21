package com.rxz.glass3dof;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.lang.ref.WeakReference;

/**
 * 投屏捕获服务。
 *
 * Android 14 起：必须先有 foregroundServiceType=mediaProjection 的前台服务，
 * 才能调用 MediaProjectionManager.getMediaProjection()；且必须先 registerCallback 再
 * createVirtualDisplay。这里严格按这个顺序执行。
 *
 * 线程模型：所有涉及 VirtualDisplay 的操作都在主线程排队执行（main handler），
 * GL 线程只通过静态方法投递，不直接碰 VirtualDisplay。
 *
 * 停止通知：MediaProjection 被系统/用户停止时，通过 {@link #stopCallback} 通知
 * MainActivity 触发 stopSession——旧实现只释放虚拟屏，界面还显示"运行中"。
 */
public class CaptureService extends Service {

    private static final String TAG = "CaptureService";
    private static final String CHANNEL = "capture";
    private static final int NOTIF_ID = 71;

    public static final String ACTION_START = "com.rxz.glass3dof.START";
    public static final String ACTION_STOP = "com.rxz.glass3dof.STOP";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_CODE = "code";

    /** 投屏被系统停止时的回调（MainActivity 注册）。弱引用，避免静态字段锁住 Activity。 */
    public interface OnStopped {
        void onProjectionStopped(String reason);
    }

    private static WeakReference<OnStopped> stopCallbackRef;

    public static void setStopCallback(OnStopped cb) {
        stopCallbackRef = cb == null ? null : new WeakReference<>(cb);
    }

    private static void notifyStopped(String reason) {
        OnStopped cb = stopCallbackRef == null ? null : stopCallbackRef.get();
        if (cb != null) {
            try {
                cb.onProjectionStopped(reason);
            } catch (Throwable t) {
                Log.w(TAG, "stopCallback 异常", t);
            }
        }
    }

    private static volatile CaptureService instance;
    /** GL 线程可能先于服务创建 Surface，这里先兜住，等服务起来再建 VirtualDisplay。 */
    private static volatile Surface pendingSurface;
    private static volatile int pendingW, pendingH;

    /** 当前捕获尺寸（静态：GL 线程要按它算内容宽高比）。 */
    public static volatile int capW, capH;

    /**
     * 独立投屏模式：虚拟屏不再镜像默认屏，而是当作一块"扩展屏"，
     * 由选定的应用渲染自己的界面（通过 Shizuku 的 am start --display 拉起）。
     */
    public static volatile boolean hostedMode;

    /**
     * 我们自己的虚拟屏 displayId。
     * 虚拟屏的创建/销毁会触发 DisplayManager 的 add/remove 回调，
     * 手机端如果不把它排除掉，就会"看到新显示器 → 重挂画面 → 新建虚拟屏 → 再触发事件"无限循环。
     */
    public static volatile int vdDisplayId = -1;

    public static boolean isRunning() {
        return instance != null;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private Surface surface;
    private MediaProjection.Callback callback;
    /** 当前 VirtualDisplay 绑定的 Surface 与尺寸，用于幂等判断，避免对同一 projection 反复 createVirtualDisplay。 */
    private Surface boundSurface;
    private int boundW, boundH;

    // ------------------------------------------------------------ 与 GL 线程交接

    /** GL 线程创建好 SurfaceTexture 后调用。 */
    public static void setSurface(Surface s, int w, int h) {
        pendingSurface = s;
        pendingW = w;
        pendingH = h;
        CaptureService svc = instance;
        if (svc != null) {
            svc.main.post(() -> svc.attachSurface(s, w, h));
        } else {
            Log.i(TAG, "setSurface: 服务未启动，已暂存 " + w + "x" + h);
        }
    }

    /** GL 线程销毁 Surface 前调用。 */
    public static void clearSurface(Surface s) {
        if (pendingSurface == s) {
            pendingSurface = null;
        }
        CaptureService svc = instance;
        if (svc != null) {
            svc.main.post(svc::releaseVirtualDisplay);
        }
    }

    private void attachSurface(Surface s, int w, int h) {
        // 尺寸不信任调用方传入的快照（渲染器可能在旧模式下创建），一律按当前模式重算，
        // 否则会出现"检测到竖向 862×1920、实际却还是 1920×1080"的错位。
        int[] size = CaptureSize.of(this);
        int nw = size[0] > 0 ? size[0] : w;
        int nh = size[1] > 0 ? size[1] : h;
        boolean same = s == surface && nw == capW && nh == capH;
        boolean hadDisplay = virtualDisplay != null;
        surface = s;
        capW = nw;
        capH = nh;
        Stage.log("绑定捕获表面 " + capW + "x" + capH + (same ? "（尺寸未变）" : ""));
        if (projection == null) {
            return;
        }
        // 尺寸或表面变了必须重建；没变但虚拟屏不在（比如刚被 clearSurface 释放过）也要补建
        if (!same || !hadDisplay) {
            createVirtualDisplay();
        }
    }

    /** 改捕获分辨率后重建虚拟屏（尺寸变了，只能重建）。 */
    public static void restart(Context ctx) {
        CaptureService svc = instance;
        Surface s = pendingSurface;
        if (svc == null || s == null) {
            return;
        }
        int[] size = CaptureSize.of(ctx);
        svc.main.post(() -> svc.attachSurface(s, size[0], size[1]));
    }

    /** 手机横竖屏切换后按新尺寸缩放虚拟屏（resize 比重建平滑得多）。 */
    public static void refreshSize(Context ctx) {
        CaptureService svc = instance;
        if (svc == null) {
            return;
        }
        int[] s = CaptureSize.of(ctx);
        svc.main.post(() -> svc.resize(s[0], s[1]));
    }

    private void resize(int w, int h) {
        if (w == capW && h == capH && virtualDisplay != null) {
            return;
        }
        Stage.log("捕获尺寸 " + capW + "x" + capH + " → " + w + "x" + h);
        Log.i(TAG, "捕获尺寸 " + capW + "x" + capH + " → " + w + "x" + h);
        capW = w;
        capH = h;
        if (virtualDisplay != null) {
            try {
                virtualDisplay.resize(w, h, getResources().getDisplayMetrics().densityDpi);
                boundW = w;
                boundH = h;
                return;
            } catch (Exception e) {
                Log.w(TAG, "resize 失败，重建虚拟屏", e);
            }
        }
        // 虚拟屏不在时不能只更新尺寸了事——否则永远黑屏，没人会再建它
        createVirtualDisplay();
    }

    private void createVirtualDisplay() {
        if (projection == null || surface == null || capW <= 0) {
            return;
        }
        // 幂等：同一 projection 反复 createVirtualDisplay 在 Android 14 上受限，
        // 同 Surface + 同尺寸时直接跳过（旧实现会无条件重建）。
        if (virtualDisplay != null && surface == boundSurface
                && capW == boundW && capH == boundH) {
            return;
        }
        // 先建新的再释放旧的：直接 release→create 会留出空窗，画面会闪断甚至不出帧
        VirtualDisplay old = virtualDisplay;
        virtualDisplay = null;
        try {
            int flags = hostedMode
                    ? DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    : DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
            VirtualDisplay fresh = projection.createVirtualDisplay(
                    "glass3dof",
                    capW, capH,
                    hostedMode ? 240 : getResources().getDisplayMetrics().densityDpi,
                    flags,
                    surface, null, null);
            virtualDisplay = fresh;
            android.view.Display vd = fresh == null ? null : fresh.getDisplay();
            vdDisplayId = vd == null ? -1 : vd.getDisplayId();
            boundSurface = surface;
            boundW = capW;
            boundH = capH;
            Stage.log("虚拟屏已创建 " + capW + "x" + capH + " id=" + vdDisplayId
                    + (hostedMode ? "（PUBLIC）" : "（镜像）"));
            Log.i(TAG, "VirtualDisplay " + capW + "x" + capH + " 已创建 id=" + vdDisplayId);
        } catch (Exception e) {
            Stage.log("创建虚拟屏失败：" + e);
            Log.e(TAG, "createVirtualDisplay 失败", e);
        }
        if (old != null) {
            old.release();
        }
    }

    private void releaseVirtualDisplay() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        boundSurface = null;
        boundW = boundH = 0;
        vdDisplayId = -1;
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 必须 START_NOT_STICKY：进程被杀后 MediaProjection 授权令牌已失效，
        // 系统拉起重启也拿不回投屏，重启一个空壳只会留下孤儿前台通知。
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground();

        if (projection == null) {
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = extractResult(intent);
            if (data == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            try {
                projection = mpm.getMediaProjection(code, data);
            } catch (Exception e) {
                Log.e(TAG, "getMediaProjection 失败", e);
                notifyStopped("投屏授权已失效");
                stopSelf();
                return START_NOT_STICKY;
            }
            callback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.w(TAG, "MediaProjection 被系统停止");
                    releaseVirtualDisplay();
                    // 旧实现只释放虚拟屏，UI 还停在"运行中"；必须通知 MainActivity 收尾
                    notifyStopped("投屏被系统停止");
                }
            };
            projection.registerCallback(callback, main);
        }
        if (pendingSurface != null) {
            attachSurface(pendingSurface, pendingW, pendingH);
        } else {
            createVirtualDisplay();
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private static Intent extractResult(Intent intent) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_RESULT, Intent.class);
        }
        return intent.getParcelableExtra(EXTRA_RESULT);
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "投屏", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 旧实现 Action 图标传 null，部分 ROM 上"停止"按钮渲染不出来
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "停止", stop).build();

        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("3DoF 悬停运行中")
                .setContentText("眼镜画面已按头部姿态锁定在空间中")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(stopAction)
                .build();
        // minSdk 29：FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION 从 Q 起可用，无需再分支
        startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    @Override
    public void onDestroy() {
        releaseVirtualDisplay();
        if (projection != null) {
            if (callback != null) {
                try {
                    projection.unregisterCallback(callback);
                } catch (Exception ignored) {
                }
            }
            try {
                projection.stop();
            } catch (Exception ignored) {
            }
            projection = null;
        }
        surface = null;
        // 静态暂存的 Surface 可能已被 GL 线程释放；不清掉，下一次会话会用失效表面建屏
        pendingSurface = null;
        pendingW = pendingH = 0;
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
