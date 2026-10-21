package com.rxz.glass3dof;

import java.lang.ref.WeakReference;

/**
 * 会话级共享状态：手机端界面与眼镜端画面（StageActivity）各自运行，
 * 靠这个类交换姿态对象和画面参数。
 *
 * 为什么需要它：手机切到抖音等 App 时，本应用的 Activity 会进入后台，
 * 由 Activity 持有的 Presentation 会被系统隐藏，眼镜就退回系统镜像（固定贴头）。
 * 把画面放进"眼镜那块屏自己的任务"（StageActivity + setLaunchDisplayId）后，
 * 手机端界面退到后台，眼镜端仍然可见、仍然在渲染。
 */
public final class Stage {

    /** 姿态会话（IMU 线程写、GL 线程读，内部自带锁）。 */
    public static volatile HeadPose pose = new HeadPose();

    /** 画面参数：手机端滑块直接写，眼镜端每帧读。 */
    public static volatile float fill = 1f;
    public static volatile float distanceM = 2.5f;
    public static volatile float fovYDeg = 23f;

    /** 眼镜端是否已成功接管那块屏（用于决定要不要回退到 Presentation）。 */
    public static volatile boolean stageReady;

    /** StageActivity 是否已经被创建过（哪怕还没画出来）。用来避免与 Presentation 重复占屏。 */
    public static volatile boolean launched;

    /** StageActivity 所在的显示器 id，用于判断"眼镜断了"还是"只是我们的虚拟屏在增删"。 */
    public static volatile int displayId = -1;

    /** 触控板光标：归一化屏幕坐标（0..1），由手机端触控板写、眼镜端渲染读。 */
    public static volatile float cursorU = 0.5f;
    public static volatile float cursorV = 0.5f;
    public static volatile boolean cursorVisible;

    /** 扩展屏模式（无需 Shizuku，内置浏览器跑在私有虚拟屏上）。 */
    public static volatile boolean extendMode;
    /** 任意应用模式是否尝试 Shizuku UserService 的受信屏（兼容性最好）。 */
    public static volatile boolean useTrustedDisplay = true;
    /** 扩展屏要启动的目标组件；null 表示内置浏览器。 */
    public static volatile String pendingComponent;
    /** 应用上下文，供渲染线程建扩展屏用。 */
    public static volatile android.content.Context appContext;

    private static WeakReference<StageActivity> ref;

    /** 简易诊断队列：眼镜端/渲染线程不方便直接弹 UI，写这里，手机端轮询取走。 */
    private static final java.util.ArrayDeque<String> LOG = new java.util.ArrayDeque<>();

    public static void log(String s) {
        synchronized (LOG) {
            if (LOG.size() > 60) {
                LOG.poll();
            }
            LOG.add(s);
        }
    }

    public static String[] drainLog() {
        synchronized (LOG) {
            if (LOG.isEmpty()) {
                return new String[0];
            }
            String[] out = LOG.toArray(new String[0]);
            LOG.clear();
            return out;
        }
    }

    /** 当前眼镜端画面 Activity（拿它当 launcher context 最稳）。 */
    public static StageActivity activity() {
        return ref == null ? null : ref.get();
    }

    private Stage() {
    }

    static void attach(StageActivity a) {
        ref = new WeakReference<>(a);
        stageReady = true;
        android.view.Display d = DisplayUtils.displayOfContext(a);
        if (d != null) {
            displayId = d.getDisplayId();
        }
    }

    static void detach(StageActivity a) {
        StageActivity cur = ref == null ? null : ref.get();
        if (cur == a) {
            ref = null;
            stageReady = false;
            android.view.Display d = DisplayUtils.displayOfContext(a);
            if (d != null && displayId == d.getDisplayId()) {
                displayId = -1;
            }
        }
    }

    /** 结束眼镜端画面。 */
    public static void close() {
        StageActivity a = ref == null ? null : ref.get();
        ref = null;
        stageReady = false;
        launched = false;
        if (a != null) {
            a.finish();
        }
    }
}
