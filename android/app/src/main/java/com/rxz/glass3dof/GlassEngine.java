package com.rxz.glass3dof;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * 原生引擎门面（Flutter UI 的唯一入口）。
 *
 * 本类是原 MainActivity 里"会话逻辑"的完整搬运：USB/IMU 打开、眼镜屏挂载、
 * MediaProjection 授权、扩展屏（私有/受信/兼容公共屏）启动、DP 重挂、
 * 看门狗、以及触控板注入。界面相关代码全部删除，改由 Flutter 通过
 * MethodChannel/EventChannel 驱动。
 *
 * 线程模型（与原实现一致）：UI 生命周期与 DisplayManager 回调在主线程；
 * USB open/close 与应用列表查询走 IO 单线程；shell 命令在 ShizukuBridge 自己的线程。
 */
public final class GlassEngine {

    private static final String TAG = "GlassEngine";
    private static final String CH_METHOD = "glass3dof/engine";
    private static final String CH_STATUS = "glass3dof/status";
    private static final String PREFS = "cfg";
    private static final int REQ_PROJECTION = 1001;

    /** 捕获长边档位（与原实现一致）。 */
    private static final int[] RES_LEVELS = {1920, 1600, 1280};
    /** 算法内置方向取反（Air 1s 实测）。 */
    private static final boolean BASE_YAW_INVERTED = true;
    private static final boolean BASE_PITCH_INVERTED = true;

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HeadPose pose = new HeadPose();
    private final MethodChannel method;
    private final EventChannel statusChannel;

    private EventChannel.EventSink statusSink;
    private final Runnable statusTask = this::pushStatus;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "engine-io");
        t.setDaemon(true);
        return t;
    });

    private final DisplayManager dm;

    // ---------------- 会话状态
    private GlassUsb glass;
    private ScreenGlView glView;
    private Presentation presentation;
    private boolean running;
    private boolean starting;
    private boolean rebinding;
    private long stageStartMs;
    private long lastRebindMs;
    private boolean restartTried;
    private int focusArmedDisplay = -1;
    private boolean hostedLaunched;
    private String lastError;

    // ---------------- 配置（持久化，key 与原版一致，可覆盖升级）
    private int sizeP = 70;      // 尺寸 0..100 → 0.3..1.3×
    private int distP = 15;      // 距离 0..100 → 1.0..11.0 m
    private int fovP = 8;        // 视场 0..?  → 15..(15+n)°
    private int resP = 1;        // 0/1/2 → 1920/1600/1280
    private boolean freeze = true;
    private boolean invFwd, invYaw, invPitch, invRoll;
    private boolean compat;
    private boolean trusted = true;
    private boolean hostedMode;  // 扩展屏（任意应用）
    private String hostedComponent;
    private String hostedLabel;

    private final DisplayManager.DisplayListener displayListener;

    public GlassEngine(Activity activity, BinaryMessenger messenger) {
        this.act = activity;
        this.dm = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        this.method = new MethodChannel(messenger, CH_METHOD);
        this.statusChannel = new EventChannel(messenger, CH_STATUS);

        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                if (displayId == CaptureService.vdDisplayId) {
                    return;
                }
                diag("显示器接入：id=" + displayId);
                ui.postDelayed(() -> {
                    if (!running || Stage.stageReady || presentation != null) {
                        return;
                    }
                    Display d = DisplayUtils.findExternal(dm);
                    if (d != null) {
                        diag("补挂到显示器 id=" + d.getDisplayId());
                        showGlassesStage(d);
                    }
                }, 1500);
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                if (displayId == CaptureService.vdDisplayId) {
                    return;
                }
                diag("显示器移除：id=" + displayId);
                boolean wasPresentation = presentation != null && presentation.getDisplay() != null
                        && presentation.getDisplay().getDisplayId() == displayId;
                boolean wasStage = Stage.stageReady && Stage.displayId == displayId;
                if (!wasPresentation && !wasStage) {
                    return;
                }
                ui.postDelayed(() -> {
                    if (!running) {
                        return;
                    }
                    Display d = DisplayUtils.findExternal(dm);
                    if (d == null) {
                        stopSession("眼镜已断开");
                        return;
                    }
                    diag("重新挂载到显示器 id=" + d.getDisplayId());
                    rebindDisplay(d);
                }, 1500);
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY && running) {
                    CaptureService.refreshSize(act);
                }
            }
        };
    }

    // ------------------------------------------------------------ 装配

    public void attach() {
        loadSettings();
        applyPoseSettings();
        pushStageConfig();
        CaptureService.setStopCallback(projectionStopped);
        dm.registerDisplayListener(displayListener, ui);
        requestNotificationPermission();
        method.setMethodCallHandler(this::onMethodCall);
        statusChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object args, EventChannel.EventSink sink) {
                statusSink = sink;
                ui.removeCallbacks(statusTask);
                ui.post(statusTask);
            }

            @Override
            public void onCancel(Object args) {
                statusSink = null;
                ui.removeCallbacks(statusTask);
            }
        });
    }

    public void dispose() {
        stopSession(null);
        saveNow();
        ui.removeCallbacksAndMessages(null);
        try {
            dm.unregisterDisplayListener(displayListener);
        } catch (Exception ignored) {
        }
        CaptureService.setStopCallback(null);
        statusSink = null;
    }

    // ------------------------------------------------------------ 通道入口

    private void onMethodCall(MethodCall call, MethodChannel.Result result) {
        try {
            switch (call.method) {
                case "getConfig":
                    result.success(configMap());
                    break;
                case "start": {
                    String mode = call.argument("mode");
                    String comp = call.argument("component");
                    String label = call.argument("label");
                    Boolean compatArg = call.argument("compat");
                    Boolean trustedArg = call.argument("trusted");
                    hostedMode = "hosted".equals(mode);
                    hostedComponent = comp;
                    hostedLabel = label;
                    compat = compatArg != null && compatArg && hostedMode;
                    trusted = trustedArg == null || trustedArg;
                    Stage.useTrustedDisplay = trusted;
                    saveNow();
                    startSession();
                    result.success(null);
                    break;
                }
                case "stop":
                    stopSession(null);
                    result.success(null);
                    break;
                case "setParams": {
                    Integer s = call.argument("size");
                    Integer d = call.argument("distance");
                    Integer f = call.argument("fov");
                    if (s != null) sizeP = clamp(s, 0, 100);
                    if (d != null) distP = clamp(d, 0, 100);
                    if (f != null) fovP = clamp(f, 0, 40);
                    applyParamsAndSave();
                    result.success(null);
                    break;
                }
                case "setPose": {
                    Boolean fr = call.argument("freeze");
                    Boolean fwd = call.argument("invertForward");
                    Boolean yaw = call.argument("invertYaw");
                    Boolean pit = call.argument("invertPitch");
                    Boolean rol = call.argument("invertRoll");
                    if (fr != null) freeze = fr;
                    if (fwd != null) invFwd = fwd;
                    if (yaw != null) invYaw = yaw;
                    if (pit != null) invPitch = pit;
                    if (rol != null) invRoll = rol;
                    applyPoseSettings();
                    saveNow();
                    result.success(null);
                    break;
                }
                case "setResolution": {
                    Integer level = call.argument("level");
                    if (level != null) resP = clamp(level, 0, RES_LEVELS.length - 1);
                    CaptureSize.maxLongSide = RES_LEVELS[resP];
                    saveNow();
                    if (running) {
                        CaptureService.refreshSize(act);
                    }
                    result.success(null);
                    break;
                }
                case "listApps": {
                    IO.execute(() -> {
                        List<Map<String, String>> apps = queryApps();
                        ui.post(() -> result.success(apps));
                    });
                    break;
                }
                case "launchHosted": {
                    String comp = call.argument("component");
                    String label = call.argument("label");
                    if (comp != null) {
                        hostedComponent = comp;
                        hostedLabel = label;
                        saveNow();
                    }
                    if (running && hostedMode) {
                        launchHosted();
                    }
                    result.success(null);
                    break;
                }
                case "caps": {
                    result.success(capsMap());
                    break;
                }
                case "openAccessibilitySettings":
                    openAccessibilitySettings();
                    result.success(null);
                    break;
                case "requestShizuku":
                    requestShizuku();
                    result.success(null);
                    break;
                // ---------------- 触控板
                case "setCursor": {
                    Double u = call.argument("u");
                    Double v = call.argument("v");
                    Boolean visible = call.argument("visible");
                    if (u != null) Stage.cursorU = (float) clamp01(u);
                    if (v != null) Stage.cursorV = (float) clamp01(v);
                    if (visible != null) Stage.cursorVisible = visible;
                    result.success(null);
                    break;
                }
                case "tap": {
                    Boolean longPress = call.argument("longPress");
                    tapAtCursor(longPress != null && longPress);
                    result.success(null);
                    break;
                }
                case "scroll": {
                    Integer axis = call.argument("axis");
                    Integer dir = call.argument("dir");
                    if (axis != null && dir != null) {
                        scroll(axis, dir);
                    }
                    result.success(null);
                    break;
                }
                case "key": {
                    Integer code = call.argument("code");
                    if (code != null) {
                        key(code);
                    }
                    result.success(null);
                    break;
                }
                case "recenter":
                    pose.recenter();
                    result.success(null);
                    break;
                case "recalibrate":
                    // 重新采样零偏（不动画面中位）：换环境/温漂变大后手动触发
                    pose.resetBias();
                    result.success(null);
                    break;
                case "dismissError":
                    lastError = null;
                    result.success(null);
                    break;
                default:
                    result.notImplemented();
            }
        } catch (Throwable t) {
            Log.e(TAG, "method " + call.method + " 失败", t);
            result.error("engine", String.valueOf(t.getMessage()), null);
        }
    }

    // ------------------------------------------------------------ 会话

    private void startSession() {
        if (running || starting) {
            return;
        }
        final boolean appMode = hostedMode;
        if (appMode && (hostedComponent == null || !hostedComponent.contains("/"))) {
            toast("先选择要投屏的应用");
            return;
        }
        Stage.appContext = act.getApplicationContext();
        Stage.extendMode = appMode && !compat;
        Stage.pendingComponent = (appMode && !compat) ? hostedComponent : null;
        CaptureService.hostedMode = compat;
        hostedLaunched = false;

        if (appMode && !ShizukuBridge.ready(act)) {
            diag(AutoConsentAccessibilityService.canInject()
                    ? "没有 Shizuku：用无障碍辅助向扩展屏注入触摸"
                    : "没有 Shizuku 且无障碍辅助未开启：扩展屏能显示，但点击无效");
        }

        Display d = DisplayUtils.findExternal(dm);
        if (d == null) {
            fail("未检测到眼镜显示器：请确认手机支持 USB-C DP 输出并已插好眼镜");
            return;
        }
        UsbDevice dev = GlassUsb.findDevice(act);
        if (dev == null) {
            fail("未找到眼镜 USB 设备（VID 0x1BBB）");
            return;
        }
        UsbManager um = (UsbManager) act.getSystemService(Context.USB_SERVICE);
        if (um == null || !um.hasPermission(dev)) {
            diag("申请 USB 权限…");
            AutoConsentAccessibilityService.armUsbConsentAutoClick(8000);
            toast("请在弹窗中允许访问眼镜");
            new GlassUsb(act, glassListener).requestPermission(granted -> ui.post(() -> {
                if (granted) {
                    diag("USB 权限已授予，继续启动");
                    startSession();
                } else {
                    fail("USB 授权未完成：若系统没弹授权框，请拔插眼镜后重试");
                }
            }));
            return;
        }

        diag("使用显示器：" + DisplayUtils.describe(d));
        showGlassesStage(d);
        pose.resetSession();

        starting = true;
        final GlassUsb g = new GlassUsb(act, glassListener);
        glass = g;
        IO.execute(() -> {
            String err = g.open();
            if (err != null) {
                ui.post(() -> {
                    if (glass != g) {
                        return;
                    }
                    fail("打开失败：" + err);
                });
                return;
            }
            g.start();
            ui.post(() -> {
                if (glass != g) {
                    return;
                }
                diag("HID：" + g.describe());
                starting = false;
                running = true;
                stageStartMs = SystemClock.elapsedRealtime();
                restartTried = false;
                QuadRenderer.textureFrames = 0;
                boolean needProjection = !hostedMode || compat;
                if (needProjection) {
                    requestProjection();
                } else {
                    method.invokeMethod("onSessionStarted", null);
                }
            });
        });
    }

    private void stopSession(String reason) {
        running = false;
        starting = false;
        final GlassUsb g = glass;
        glass = null;
        if (g != null) {
            IO.execute(g::close);
        }
        if (presentation != null) {
            try {
                presentation.dismiss();
            } catch (Exception ignored) {
            }
            presentation = null;
        }
        if (glView != null) {
            glView.onPause();
            glView.renderer().release();
            glView = null;
        }
        act.stopService(new Intent(act, CaptureService.class));
        Stage.close();
        ExtendHost.release();
        Stage.extendMode = false;
        CaptureService.hostedMode = false;
        hostedLaunched = false;
        AutoConsentAccessibilityService.clearFocusRestore();
        focusArmedDisplay = -1;
        pose.resetSession();
        if (reason != null) {
            diag("已停止：" + reason);
            toast(reason);
        }
        method.invokeMethod("onSessionStopped", reason == null ? "" : reason);
    }

    private void fail(String message) {
        lastError = message;
        diag(message);
        toast(message);
        starting = false;
        method.invokeMethod("onError", message);
        pushStatus();
    }

    private final GlassUsb.Listener glassListener = new GlassUsb.Listener() {
        @Override
        public void onStatus(String text) {
            diag(text);
        }

        @Override
        public void onSample(float[] accel, float[] gyroRad, float dt) {
            pose.onSample(accel, gyroRad, dt);
        }

        @Override
        public void onFatal(String reason) {
            ui.post(() -> stopSession("IMU 异常：" + reason));
        }
    };

    private final CaptureService.OnStopped projectionStopped = reason ->
            ui.post(() -> {
                if (running) {
                    stopSession(reason);
                }
            });

    private void showGlassesStage(Display display) {
        Stage.close();
        Stage.pose = pose;
        pushStageConfig();

        Intent intent = new Intent(act, StageActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        Bundle opts = null;
        try {
            opts = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(display.getDisplayId())
                    .toBundle();
        } catch (Exception e) {
            diag("构造副屏启动参数失败：" + e.getMessage());
        }
        try {
            act.startActivity(intent, opts);
            diag("画面已投到眼镜屏（display " + display.getDisplayId() + "）");
        } catch (Exception e) {
            diag("副屏启动失败：" + e.getMessage() + "，回退 Presentation");
            showPresentation(display);
            return;
        }
        ui.postDelayed(() -> {
            if (!running || Stage.launched || Stage.stageReady || presentation != null) {
                return;
            }
            diag("眼镜屏未接管，回退 Presentation（切后台会失效）");
            Stage.close();
            showPresentation(display);
        }, 2500);
    }

    private void showPresentation(Display display) {
        try {
            glView = new ScreenGlView(act, pose);
            presentation = new Presentation(act, display);
            presentation.getWindow().setBackgroundDrawable(new ColorDrawable(0xFF000000));
            presentation.setContentView(glView);
            presentation.setOnDismissListener(p -> {
                if (running && !rebinding) {
                    stopSession(null);
                }
            });
            presentation.show();
        } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException
                | IllegalStateException e) {
            diag("Presentation 挂载失败：" + e);
            presentation = null;
            glView = null;
            stopSession("眼镜屏不可用");
        }
    }

    private void rebindDisplay(Display display) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRebindMs < 2500) {
            diag("忽略过频的重挂请求");
            return;
        }
        lastRebindMs = now;
        rebinding = true;
        try {
            if (presentation != null) {
                presentation.dismiss();
            }
            if (glView != null) {
                glView.onPause();
                glView.renderer().release();
            }
            Stage.close();
        } catch (Exception ignored) {
        }
        presentation = null;
        glView = null;
        showGlassesStage(display);
        rebinding = false;
    }

    private void requestProjection() {
        AutoConsentAccessibilityService.armConsentAutoClick(5000);
        MediaProjectionManager mpm =
                (MediaProjectionManager) act.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        act.startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    /** 由 MainActivity.onActivityResult 转发；返回 true 表示已消费。 */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PROJECTION) {
            return false;
        }
        if (!running) {
            diag("投屏授权返回时会话已停止，忽略");
            act.stopService(new Intent(act, CaptureService.class));
            return true;
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            diag("投屏授权被取消");
            stopSession("需要投屏权限");
            return true;
        }
        Intent i = new Intent(act, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT, data);
        act.startForegroundService(i);
        diag("投屏已启动");
        stageStartMs = SystemClock.elapsedRealtime();
        restartTried = false;
        QuadRenderer.textureFrames = 0;
        method.invokeMethod("onSessionStarted", null);
        return true;
    }

    // ------------------------------------------------------------ 参数

    private void applyParamsAndSave() {
        pushStageConfig();
        saveNow();
        pushStatus();
    }

    private void pushStageConfig() {
        Stage.fill = 0.3f + sizeP / 100f;
        Stage.distanceM = 1f + distP / 10f;
        Stage.fovYDeg = 15f + fovP;
    }

    private void applyPoseSettings() {
        pose.freezeWhenStill = freeze;
        pose.invertYaw = BASE_YAW_INVERTED ^ invYaw;
        pose.invertPitch = BASE_PITCH_INVERTED ^ invPitch;
        pose.invertForward = invFwd;
        pose.invertRoll = invRoll;
    }

    private List<Map<String, String>> queryApps() {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            android.content.pm.PackageManager pm = act.getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> found = pm.queryIntentActivities(q, 0);
            List<String[]> rows = new ArrayList<>();
            for (android.content.pm.ResolveInfo ri : found) {
                rows.add(new String[]{String.valueOf(ri.loadLabel(pm)),
                        ri.activityInfo.packageName + "/" + ri.activityInfo.name});
            }
            Collections.sort(rows, (a, b) -> a[0].compareTo(b[0]));
            for (String[] r : rows) {
                java.util.HashMap<String, String> m = new java.util.HashMap<>();
                m.put("label", r[0]);
                m.put("component", r[1]);
                out.add(m);
            }
        } catch (Throwable t) {
            Log.w(TAG, "查询应用失败", t);
        }
        return out;
    }

    // ------------------------------------------------------------ 触控板注入

    private int currentDisplayId() {
        return CaptureService.vdDisplayId > 0 ? CaptureService.vdDisplayId : ExtendHost.displayId;
    }

    private int[] targetSize() {
        Display d = Ui_free_display(currentDisplayId());
        int[] s = DisplayUtils.sizeOf(d);
        return s != null ? s : CaptureSize.of(act);
    }

    /** 按 displayId 取 Display（不依赖已删除的 Ui 工具类）。 */
    private Display Ui_free_display(int displayId) {
        try {
            Display[] all = dm.getDisplays();
            for (Display d : all) {
                if (d.getDisplayId() == displayId) {
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void tapAtCursor(boolean longPress) {
        int id = currentDisplayId();
        if (id <= 0) {
            return;
        }
        int[] size = targetSize();
        float x = Stage.cursorU * size[0];
        float y = Stage.cursorV * size[1];
        if (ShizukuBridge.ready(act)) {
            if (longPress) {
                ShizukuBridge.longPressOnDisplay(id, x, y);
            } else {
                ShizukuBridge.tapOnDisplay(id, x, y);
            }
            return;
        }
        if (AutoConsentAccessibilityService.injectTap(id, x, y, longPress ? 650 : 80)) {
            return;
        }
        toast("无法注入触摸：请开启 Shizuku 或无障碍辅助");
    }

    private void scroll(int axis, int dir) {
        int id = currentDisplayId();
        if (id <= 0) {
            return;
        }
        int[] size = targetSize();
        float cx = Stage.cursorU * size[0];
        float cy = Stage.cursorV * size[1];
        float x2, y2;
        if (axis == 1) {
            float step = size[0] * 0.30f;
            x2 = clampF(cx + dir * step, 0, size[0]);
            y2 = cy;
        } else {
            float step = size[1] * 0.30f;
            x2 = cx;
            y2 = clampF(cy + dir * step, 0, size[1]);
        }
        if (ShizukuBridge.ready(act)) {
            ShizukuBridge.swipeOnDisplay(id, cx, cy, x2, y2);
            return;
        }
        if (!AutoConsentAccessibilityService.injectSwipe(id, cx, cy, x2, y2, 220)) {
            toast("无法注入触摸：请开启 Shizuku 或无障碍辅助");
        }
    }

    private void key(int code) {
        int id = currentDisplayId();
        if (id > 0 && ShizukuBridge.ready(act)) {
            ShizukuBridge.keyOnDisplay(id, code);
            return;
        }
        if (code == android.view.KeyEvent.KEYCODE_BACK && AutoConsentAccessibilityService.performBack()) {
            return;
        }
        toast("无法注入按键：请开启 Shizuku 或无障碍辅助");
    }

    private void launchHosted() {
        if (hostedComponent == null) {
            toast("先选择要投屏的应用");
            return;
        }
        int displayId = currentDisplayId();
        if (displayId <= 0) {
            toast("扩展屏还没就绪，稍后再试");
            return;
        }
        hostedLaunched = true;
        String[] parts = hostedComponent.split("/");
        String pkg = parts[0];
        String cls = parts.length > 1 ? parts[1] : null;
        ShizukuBridge.launchOnDisplay(pkg, cls, displayId, ok ->
                diag((ok ? "已在扩展屏启动 " : "启动失败 ")
                        + (hostedLabel == null ? pkg : hostedLabel) + "（display " + displayId + "）"));
    }

    // ------------------------------------------------------------ 辅助入口

    private void openAccessibilitySettings() {
        if (AutoConsentAccessibilityService.isEnabled(act)) {
            toast("无障碍辅助已开启");
            return;
        }
        try {
            act.startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            toast("在列表里找到 Air3DoF 并开启");
        } catch (Exception e) {
            toast("打不开无障碍设置页，请到系统设置 → 无障碍里手动开启");
        }
    }

    private void requestShizuku() {
        if (!ShizukuBridge.installed(act)) {
            toast("未检测到 Shizuku，请先安装并启动");
            ShizukuBridge.openShizuku(act);
            return;
        }
        if (ShizukuBridge.ready(act)) {
            toast("Shizuku 已就绪");
            return;
        }
        ShizukuBridge.request(new ShizukuBridge.Listener() {
            @Override
            public void onGranted() {
                ui.post(() -> {
                    diag("Shizuku 已授权");
                    toast("Shizuku 已授权");
                    pushStatus();
                });
            }

            @Override
            public void onDenied() {
                ui.post(() -> toast("Shizuku 授权被拒绝"));
            }
        });
    }

    // ------------------------------------------------------------ 状态推送

    private void pushStatus() {
        if (statusSink == null) {
            ui.removeCallbacks(statusTask);
            return;
        }
        // 看门狗：投屏开始 6 秒还没帧 → 重建一次虚拟屏
        if (running && !restartTried && CaptureService.isRunning()
                && QuadRenderer.textureFrames == 0
                && SystemClock.elapsedRealtime() - stageStartMs > 6000) {
            restartTried = true;
            diag("6 秒未收到投屏帧，重建虚拟屏");
            CaptureService.restart(act);
        }
        // 兼容模式：公开虚拟屏就绪后启动目标应用
        if (running && CaptureService.hostedMode && !hostedLaunched
                && CaptureService.vdDisplayId > 0) {
            hostedLaunched = true;
            diag("公开屏就绪 display=" + CaptureService.vdDisplayId + "，启动目标应用");
            ExtendHost.launch(act, CaptureService.vdDisplayId, hostedComponent);
        }
        // 焦点恢复布防
        if (running) {
            int did = currentDisplayId();
            if (did > 0 && did != focusArmedDisplay) {
                focusArmedDisplay = did;
                AutoConsentAccessibilityService.requestFocusRestore(did);
            }
        }
        for (String line : Stage.drainLog()) {
            diag(line);
        }
        try {
            statusSink.success(statusMap());
        } catch (Throwable t) {
            Log.w(TAG, "status 推送失败", t);
            statusSink = null;
        }
        ui.removeCallbacks(statusTask);
        ui.postDelayed(statusTask, 800);
    }

    private Map<String, Object> statusMap() {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        Display ext = DisplayUtils.findExternal(dm);
        m.put("running", running);
        m.put("starting", starting);
        m.put("glasses", ext != null);
        m.put("displayName", DisplayUtils.describe(ext));
        m.put("displayId", currentDisplayId());
        m.put("stageReady", Stage.stageReady);
        m.put("stageLaunched", Stage.launched);
        m.put("presentation", presentation != null);
        m.put("frames", QuadRenderer.textureFrames);
        m.put("aspect", (double) QuadRenderer.contentAspectNow);
        m.put("mode", hostedMode ? "hosted" : "mirror");
        m.put("component", hostedComponent);
        m.put("label", hostedLabel);

        java.util.HashMap<String, Object> poseM = new java.util.HashMap<>();
        poseM.put("tracking", pose.isTracking());
        float[] yp = pose.yawPitch();
        poseM.put("yaw", (double) yp[0]);
        poseM.put("pitch", (double) yp[1]);
        poseM.put("roll", (double) pose.rollDeg());
        poseM.put("hz", (double) pose.hz);
        poseM.put("frozen", pose.isFrozen());
        poseM.put("biasDps", (double) pose.biasDps());
        poseM.put("residualDps", (double) pose.residualDps());
        m.put("pose", poseM);

        java.util.HashMap<String, Object> capM = new java.util.HashMap<>();
        int[] want = CaptureSize.of(act);
        capM.put("wantW", want[0]);
        capM.put("wantH", want[1]);
        capM.put("w", CaptureService.capW);
        capM.put("h", CaptureService.capH);
        m.put("capture", capM);

        java.util.HashMap<String, Object> imuM = new java.util.HashMap<>();
        imuM.put("open", glass != null && glass.isOpen());
        imuM.put("frames", glass != null ? glass.frames() : 0);
        imuM.put("sendMode", glass != null ? glass.sendModeName() : "");
        m.put("imu", imuM);

        java.util.HashMap<String, Object> a11y = new java.util.HashMap<>();
        a11y.put("enabled", AutoConsentAccessibilityService.isEnabled(act));
        a11y.put("canInject", AutoConsentAccessibilityService.canInject());
        m.put("accessibility", a11y);

        java.util.HashMap<String, Object> shizuku = new java.util.HashMap<>();
        shizuku.put("installed", ShizukuBridge.installed(act));
        shizuku.put("ready", ShizukuBridge.ready(act));
        m.put("shizuku", shizuku);

        m.put("error", lastError);
        m.put("version", versionName());
        return m;
    }

    private Map<String, Object> configMap() {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("size", sizeP);
        m.put("distance", distP);
        m.put("fov", fovP);
        m.put("resolution", resP);
        m.put("freeze", freeze);
        m.put("invertForward", invFwd);
        m.put("invertYaw", invYaw);
        m.put("invertPitch", invPitch);
        m.put("invertRoll", invRoll);
        m.put("mode", hostedMode ? "hosted" : "mirror");
        m.put("compat", compat);
        m.put("trusted", trusted);
        m.put("component", hostedComponent);
        m.put("label", hostedLabel);
        m.put("version", versionName());
        return m;
    }

    private Map<String, Object> capsMap() {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("accessibility", AutoConsentAccessibilityService.isEnabled(act));
        m.put("canInject", AutoConsentAccessibilityService.canInject());
        m.put("shizukuInstalled", ShizukuBridge.installed(act));
        m.put("shizukuReady", ShizukuBridge.ready(act));
        m.put("version", versionName());
        return m;
    }

    private String versionName() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------ 设置读写

    private boolean loading;

    private void loadSettings() {
        SharedPreferences p = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loading = true;
        sizeP = p.getInt("size", 70);
        distP = p.getInt("dist", 15);
        fovP = p.getInt("fov", 8);
        resP = clamp(p.getInt("res", 1), 0, RES_LEVELS.length - 1);
        CaptureSize.maxLongSide = RES_LEVELS[resP];
        freeze = p.getBoolean("freeze", true);
        boolean dirV3 = p.getBoolean("dir_v3", false);
        invFwd = dirV3 && p.getBoolean("inv_fwd", false);
        invYaw = dirV3 && p.getBoolean("inv_yaw", false);
        invPitch = dirV3 && p.getBoolean("inv_pitch", false);
        invRoll = dirV3 && p.getBoolean("inv_roll", false);
        if (!dirV3) {
            p.edit().putBoolean("dir_v3", true)
                    .putBoolean("inv_fwd", false).putBoolean("inv_yaw", false)
                    .putBoolean("inv_pitch", false).putBoolean("inv_roll", false).apply();
        }
        compat = p.getBoolean("compat", false);
        trusted = p.getBoolean("trusted", true);
        hostedMode = p.getInt("mode", 0) >= 1;
        hostedComponent = p.getString("hosted_comp", null);
        hostedLabel = p.getString("hosted_label", null);
        loading = false;
    }

    private void saveNow() {
        if (loading) {
            return;
        }
        act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("size", sizeP)
                .putInt("dist", distP)
                .putInt("fov", fovP)
                .putInt("res", resP)
                .putBoolean("freeze", freeze)
                .putBoolean("inv_fwd", invFwd)
                .putBoolean("inv_yaw", invYaw)
                .putBoolean("inv_pitch", invPitch)
                .putBoolean("inv_roll", invRoll)
                .putBoolean("compat", compat)
                .putBoolean("trusted", trusted)
                .putInt("mode", hostedMode ? 1 : 0)
                .putString("hosted_comp", hostedComponent)
                .putString("hosted_label", hostedLabel)
                .apply();
    }

    // ------------------------------------------------------------ 小工具

    /** Android 13+ 前台服务通知需要运行时权限；拒绝不影响投屏，只是通知不显示。 */
    private void requestNotificationPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && act.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                act.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1002);
            }
        } catch (Throwable t) {
            Log.w(TAG, "申请通知权限失败", t);
        }
    }

    private void diag(String line) {
        Log.i(TAG, line);
    }

    private void toast(final String s) {
        ui.post(() -> {
            try {
                android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
            method.invokeMethod("onToast", s);
        });
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** 供 UI 显示的分辨率档位名。 */
    static String[] resolutionNames() {
        return new String[]{"1920（清晰）", "1600（均衡）", "1280（流畅）"};
    }

    static String fmt(String pattern, Object... args) {
        return String.format(Locale.US, pattern, args);
    }
}
