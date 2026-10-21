package com.rxz.glass3dof;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

/**
 * 显示器发现与判定的唯一实现。
 *
 * 之前 MainActivity.findGlassesDisplay() 与 CaptureSize.externalDisplaySize() 各写了一份
 * "跳过默认屏 / 跳过我们自己的虚拟屏 / 跳过私有屏"的规则，两处规则一旦漂移就会出现
 * "找到的屏和测量的屏不是同一块"。这里收敛成一份。
 */
final class DisplayUtils {

    private DisplayUtils() {
    }

    /** 名字以 glass3dof 开头、或含 Virtual 的屏都是我们（或系统）建的虚拟屏，不是眼镜。 */
    static boolean isOurVirtual(Display d) {
        if (d == null) {
            return true;
        }
        String name = d.getName() == null ? "" : d.getName();
        return name.startsWith("glass3dof") || name.contains("Virtual");
    }

    /**
     * 找眼镜那块屏：优先 PRESENTATION 类别（HDMI/DP 外接屏），找不到再扫全部显示器。
     *
     * @return 找不到返回 null
     */
    static Display findExternal(DisplayManager dm) {
        if (dm == null) {
            return null;
        }
        for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (isUsable(d)) {
                return d;
            }
        }
        for (Display d : dm.getDisplays()) {
            if (isUsable(d)) {
                return d;
            }
        }
        return null;
    }

    private static boolean isUsable(Display d) {
        if (d == null
                || d.getDisplayId() == Display.DEFAULT_DISPLAY
                || d.getDisplayId() == CaptureService.vdDisplayId
                || (d.getFlags() & Display.FLAG_PRIVATE) != 0
                || isOurVirtual(d)) {
            return false;
        }
        return d.getState() != Display.STATE_OFF;
    }

    /** 外接屏的物理分辨率（已是屏幕当前朝向）；找不到返回 null。 */
    static int[] externalSize(DisplayManager dm) {
        Display d = findExternal(dm);
        if (d == null) {
            return null;
        }
        return sizeOf(d);
    }

    /** 某块屏的当前物理分辨率，已按偶数对齐；拿不到返回 null。 */
    static int[] sizeOf(Display d) {
        if (d == null) {
            return null;
        }
        Display.Mode m = d.getMode();
        if (m == null) {
            return null;
        }
        int w = m.getPhysicalWidth() & ~1;
        int h = m.getPhysicalHeight() & ~1;
        return (w > 0 && h > 0) ? new int[]{w, h} : null;
    }

    static String describe(Display d) {
        if (d == null) {
            return "—";
        }
        Display.Mode m = d.getMode();
        return d.getName() + (m == null ? "" : " " + m.getPhysicalWidth() + "×" + m.getPhysicalHeight());
    }

    /**
     * Display.isTrusted() 是 @hide，反射读，仅用于日志判断。
     * 隐藏 API 名单随版本收紧，取不到就当 false（不影响功能）。
     */
    static boolean isTrusted(Display d) {
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

    static DisplayManager dm(Context ctx) {
        return (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
    }

    /**
     * Activity/Context 所在的屏。Context.getDisplay() 是 API 30 才有的
     * （minSdk 29 上直接调会 NoSuchMethodError，lint NewApi 报过），这里做兼容：
     * API 29 走 WindowManager.getDefaultDisplay()——对 Activity 而言两者语义一致
     * （返回该 Activity 所在的屏，不是恒为默认屏）。
     */
    static android.view.Display displayOfContext(Context c) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return c.getDisplay();
        }
        android.view.WindowManager wm =
                (android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        return wm == null ? null : wm.getDefaultDisplay();
    }
}
