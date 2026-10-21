package com.rxz.glass3dof;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Shizuku 桥：以 shell 身份执行命令。
 *
 * 用来做两件 framework 不允许普通应用做的事：
 *   1. `am start --display N -n pkg/cls` —— 把任意应用启动到我们的扩展屏；
 *   2. `input -d N tap x y`            —— 把触摸注入到那块屏。
 *
 * 没有 Shizuku 就没法实现"独立投屏 + 手机变操控板"，只能退回镜像模式。
 *
 * 线程模型（重要）：shell 命令是阻塞的（fork app_process + 读写管道，几十到几百毫秒），
 * **一律不在调用线程执行**。对外只给异步接口，内部串行跑在一条独立的 IO 线程上：
 * 既不会阻塞 UI，也保证 tap/swipe 的顺序与用户操作顺序一致。
 */
public final class ShizukuBridge {

    private static final String TAG = "ShizukuBridge";
    public static final int REQ_CODE = 4001;
    private static final String PKG = "moe.shizuku.privileged.api";

    /** pkg/class 形态校验，防止把存档里的脏字符串拼进 shell 命令。 */
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+");

    /** 串行执行 shell 命令。所有 input/am 都走这里，保证顺序且不占 UI 线程。 */
    private static final ExecutorService SHELL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shizuku-sh");
        t.setDaemon(true);
        return t;
    });

    public interface Listener {
        void onGranted();

        void onDenied();
    }

    public interface ReadyCallback {
        void onReady(boolean ready);
    }

    private ShizukuBridge() {
    }

    // ------------------------------------------------------------ 就绪判定

    /**
     * 等 Shizuku binder 就绪。应用刚启动时 binder 可能还没绑上，
     * 直接判 ready() 会误判成"没有 Shizuku"，从而错过受信屏。
     *
     * 回调只会有一次；监听器在回调后立即移除（旧实现只加不减，每建一次屏就泄漏一个）。
     */
    public static void awaitReady(Context ctx, long timeoutMs, ReadyCallback cb) {
        if (ready(ctx)) {
            cb.onReady(true);
            return;
        }
        if (ctx != null && !installed(ctx)) {
            cb.onReady(false);
            return;
        }
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final boolean[] done = {false};
        final Shizuku.OnBinderReceivedListener[] holder = new Shizuku.OnBinderReceivedListener[1];
        holder[0] = () -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            h.post(() -> {
                try {
                    if (holder[0] != null) {
                        Shizuku.removeBinderReceivedListener(holder[0]);
                    }
                } catch (Throwable ignored) {
                }
                cb.onReady(ready(ctx));
            });
        };
        try {
            Shizuku.addBinderReceivedListenerSticky(holder[0]);
        } catch (Throwable t) {
            cb.onReady(false);
            return;
        }
        h.postDelayed(() -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            try {
                Shizuku.removeBinderReceivedListener(holder[0]);
            } catch (Throwable ignored) {
            }
            cb.onReady(ready(ctx));
        }, timeoutMs);
    }

    /** Shizuku 服务在跑且已授权。 */
    public static boolean ready(Context ctx) {
        try {
            if (!Shizuku.pingBinder()) {
                return false;
            }
            if (Shizuku.isPreV11()) {
                // 老版本没有运行时权限，直接可用
                return true;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    public static boolean installed(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 申请权限；结果通过 listener 回主线程，监听器用完即摘。 */
    public static void request(Listener listener) {
        try {
            if (!Shizuku.pingBinder()) {
                listener.onDenied();
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                listener.onGranted();
                return;
            }
            final boolean[] done = {false};
            final Shizuku.OnRequestPermissionResultListener[] holder =
                    new Shizuku.OnRequestPermissionResultListener[1];
            holder[0] = (requestCode, grantResult) -> {
                if (done[0]) {
                    return;
                }
                done[0] = true;
                try {
                    Shizuku.removeRequestPermissionResultListener(holder[0]);
                } catch (Throwable ignored) {
                }
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    listener.onGranted();
                } else {
                    listener.onDenied();
                }
            };
            Shizuku.addRequestPermissionResultListener(holder[0]);
            Shizuku.requestPermission(REQ_CODE);
        } catch (Throwable t) {
            Log.w(TAG, "request 失败", t);
            listener.onDenied();
        }
    }

    // ------------------------------------------------------------ 命令执行

    private static final class Result {
        int code = -1;
        String out = "";
        String err = "";
    }

    /**
     * 执行一条 shell 命令（**阻塞，只能在 SHELL 线程上调**）。
     *
     * stdout 与 stderr 必须并行读取：只读一路的话，另一路把管道缓冲写满就会互相等死
     * （`am start` 出错时会往 stderr 刷 usage）。
     */
    private static Result run(String cmd) {
        Result res = new Result();
        IRemoteProcess rp = null;
        try {
            if (!Shizuku.pingBinder()) {
                return null;
            }
            IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            rp = service.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            final IRemoteProcess proc = rp;

            final StringBuilder errBuf = new StringBuilder();
            Thread errReader = new Thread(() -> {
                try (BufferedReader er = new BufferedReader(new InputStreamReader(
                        new ParcelFileDescriptor.AutoCloseInputStream(proc.getErrorStream())))) {
                    String line;
                    while ((line = er.readLine()) != null) {
                        synchronized (errBuf) {
                            errBuf.append(line).append('\n');
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, "shizuku-err");
            errReader.setDaemon(true);
            errReader.start();

            StringBuilder outBuf = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new ParcelFileDescriptor.AutoCloseInputStream(rp.getInputStream())))) {
                String line;
                while ((line = r.readLine()) != null) {
                    outBuf.append(line).append('\n');
                }
            }
            res.code = rp.waitFor();
            errReader.join(1000);
            res.out = outBuf.toString();
            synchronized (errBuf) {
                res.err = errBuf.toString();
            }
            Log.i(TAG, cmd + " -> " + res.code + " " + res.out + res.err);
            return res;
        } catch (Throwable t) {
            Log.e(TAG, "exec 失败: " + cmd, t);
            return null;
        } finally {
            if (rp != null) {
                try {
                    rp.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 提交一条命令，立即返回（给触摸注入用）。 */
    private static void execAsync(String cmd) {
        SHELL.execute(() -> run(cmd));
    }

    /**
     * 把应用启动到指定 display。结果通过 cb 回主线程。
     *
     * 校验 component 形态：它可能来自 SharedPreferences（存档被改就成了命令注入）。
     */
    public static void launchOnDisplay(String pkg, String cls, int displayId, Consumer<Boolean> cb) {
        final String component = cls == null ? pkg : pkg + "/" + cls;
        if (!COMPONENT.matcher(component).matches()) {
            Log.w(TAG, "非法组件名，拒绝执行：" + component);
            if (cb != null) {
                cb.accept(false);
            }
            return;
        }
        final String cmd = "am start --display " + displayId
                + " --user current -n " + component
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER";
        SHELL.execute(() -> {
            Result r = run(cmd);
            boolean ok = r != null && r.code == 0 && !r.out.contains("Error") && !r.err.contains("Error");
            if (cb != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.accept(ok));
            }
        });
    }

    /** 在扩展屏上注入一次点击。 */
    public static void tapOnDisplay(int displayId, float x, float y) {
        execAsync("input -d " + displayId + " tap " + Math.round(x) + " " + Math.round(y));
    }

    public static void longPressOnDisplay(int displayId, float x, float y) {
        execAsync("input -d " + displayId + " swipe " + Math.round(x) + " " + Math.round(y)
                + " " + Math.round(x) + " " + Math.round(y) + " 650");
    }

    /** 在扩展屏上注入一次滑动（拖动/滚动）。 */
    public static void swipeOnDisplay(int displayId, float x1, float y1, float x2, float y2) {
        execAsync("input -d " + displayId + " swipe " + Math.round(x1) + " " + Math.round(y1)
                + " " + Math.round(x2) + " " + Math.round(y2) + " 220");
    }

    public static void keyOnDisplay(int displayId, int keyCode) {
        execAsync("input -d " + displayId + " keyevent " + keyCode);
    }

    /** 打开 Shizuku 应用（未安装时打开官网）。 */
    public static void openShizuku(Context ctx) {
        try {
            android.content.Intent i = ctx.getPackageManager().getLaunchIntentForPackage(PKG);
            if (i != null) {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://shizuku.rikka.app/"));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
