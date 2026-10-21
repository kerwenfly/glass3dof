package com.rxz.glass3dof;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * 受信虚拟屏客户端。
 *
 * 把建屏动作交给运行在 shell 进程里的 {@link ProjectionUserService}，
 * 拿到 VIRTUAL_DISPLAY_FLAG_TRUSTED 才能在副屏上跑那些"只认真实显示器"的应用。
 *
 * 线程模型：createDisplay 的 Binder 调用（携带 Surface 跨进程）在 IO 线程执行，
 * 回调回主线程。旧实现在主线程同步走 Binder，Surface 大对象序列化时 UI 会卡。
 *
 * 生命周期：bind 之后必须 release——旧实现从不 unbindUserService，
 * shell 侧的 UserService 进程在停止悬停后常驻不退。
 */
public final class TrustedProjection {

    private static final String TAG = "TrustedProjection";

    /** @hide DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED —— SDK 未公开，值取自 AOSP 常量表 */
    public static final int FLAG_TRUSTED = 1 << 10;

    /** 公共屏 + 受信 + 自有内容（不镜像默认屏） */
    public static final int TRUSTED_FLAGS = 0
            | 1               // VIRTUAL_DISPLAY_FLAG_PUBLIC
            | 8               // VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            | FLAG_TRUSTED;

    public interface Callback {
        /** displayId > 0 表示成功；-1 表示失败（调用方应回退其它方式）。 */
        void onResult(int displayId);
    }

    private static final Handler main = new Handler(Looper.getMainLooper());

    /** 建屏的 Binder 调用串行执行，避免与 unbind 交错。 */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "trusted-proj");
        t.setDaemon(true);
        return t;
    });

    private static volatile IProjectionService service;
    private static volatile boolean binding;
    private static volatile Runnable pending;
    /** bind() 时构造并保存，release() 时用来解绑。 */
    private static volatile Shizuku.UserServiceArgs boundArgs;

    public static boolean isBound() {
        return service != null;
    }

    /** 建受信屏；内部会按需绑定 UserService，超时或失败回调 -1。 */
    public static void createDisplay(Context ctx, int w, int h, int dpi, int flags,
                                     Surface surface, Callback cb) {
        if (!ShizukuBridge.ready(ctx)) {
            cb.onResult(-1);
            return;
        }
        final Runnable work = () -> {
            IProjectionService s = service;
            if (s == null) {
                cb.onResult(-1);
                return;
            }
            IO.execute(() -> {
                int id = -1;
                try {
                    id = s.createDisplay(w, h, dpi, flags, surface);
                    if (id <= 0) {
                        String err = "?";
                        try {
                            err = s.lastError();
                        } catch (Throwable ignored) {
                        }
                        Stage.log("受信屏建屏失败：" + err);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "createDisplay 调用失败", t);
                    Stage.log("受信屏调用异常：" + t);
                }
                final int result = id;
                main.post(() -> cb.onResult(result));
            });
        };

        if (service != null) {
            work.run();
            return;
        }
        // 绑定进行中又来一个请求：旧的 pending 直接判失败回调（否则调用方永远等不到回退），
        // 新请求接上。
        Runnable old = pending;
        if (old != null) {
            main.post(old::run);   // work 会看到 service==null（或新 service），结果正确
        }
        pending = work;
        bind(ctx);
    }

    private static void bind(Context ctx) {
        if (binding) {
            return;
        }
        binding = true;
        int versionCode = 0;
        try {
            versionCode = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(ctx.getPackageName(), ProjectionUserService.class.getName()))
                .version(versionCode)
                .processNameSuffix("proj")
                .debuggable(false)
                .daemon(false);
        boundArgs = args;

        try {
            Stage.log("绑定 Shizuku UserService…");
            Shizuku.bindUserService(args, connection);
        } catch (Throwable t) {
            Stage.log("绑定 UserService 失败：" + t);
            binding = false;
            finish(-1);
            return;
        }
        main.postDelayed(() -> {
            if (service == null) {
                Stage.log("UserService 绑定超时");
                binding = false;
                finish(-1);
            }
        }, 6000);
    }

    /** 把挂着的 pending 请求跑完（service 可能已就绪，也可能为空，work 里会各自处理）。 */
    private static void finish(int displayId) {
        Runnable r = pending;
        pending = null;
        if (r != null) {
            r.run();
        }
    }

    private static final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IProjectionService.Stub.asInterface(binder);
            binding = false;
            String info = "?";
            try {
                info = service.info();
            } catch (Throwable t) {
                info = "info 失败：" + t;
            }
            Stage.log("UserService 已连接 " + info);
            finish(0);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding = false;
            Stage.log("UserService 已断开");
        }
    };

    /** 释放虚拟屏并解绑 UserService（旧实现只 releaseDisplay，shell 进程常驻不退）。 */
    public static void release() {
        IProjectionService s = service;
        service = null;
        pending = null;
        if (s != null) {
            try {
                s.releaseDisplay();
            } catch (Throwable ignored) {
            }
        }
        Shizuku.UserServiceArgs args = boundArgs;
        boundArgs = null;
        if (args != null) {
            try {
                Shizuku.unbindUserService(args, connection, false);
            } catch (Throwable t) {
                Log.w(TAG, "unbindUserService 失败", t);
            }
        }
        binding = false;
    }
}
