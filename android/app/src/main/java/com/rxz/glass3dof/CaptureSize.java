package com.rxz.glass3dof;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

/**
 * 投屏捕获分辨率：手机整屏尺寸，长边限制在 1920 以内（带宽/功耗与清晰度的折中）。
 *
 * 用「Display.Mode 的物理尺寸 + 当前旋转」来算，而不是 WindowMetrics：
 * 前者在横竖屏切换时是可预测的（旋转 90/270 就交换宽高），
 * 后者在 Service 上下文里可能拿到过期值，导致横屏画面转不过来。
 */
public final class CaptureSize {

    /** 长边上限：越低越省带宽、越不易卡，但文字越糊。界面可调。 */
    public static volatile int maxLongSide = 1600;

    private CaptureSize() {
    }

    public static int[] of(Context ctx) {
        // 独立投屏 / 扩展屏：尺寸取眼镜屏本身的分辨率（内容由应用自己画在这块屏上）
        if (CaptureService.hostedMode || Stage.extendMode) {
            int[] ext = DisplayUtils.externalSize(DisplayUtils.dm(ctx));
            if (ext != null) {
                return ext;
            }
        }

        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);

        int w = 1080, h = 1920;
        if (d != null) {
            // getRealSize 返回的是"当前朝向"下的尺寸，横竖屏切换会直接交换宽高，
            // 比 Display.Mode（物理尺寸，不随旋转变）+ getRotation 更省心。
            DisplayMetrics metrics = new DisplayMetrics();
            d.getRealMetrics(metrics);
            w = metrics.widthPixels;
            h = metrics.heightPixels;
            if (w <= 0 || h <= 0) {
                Display.Mode m = d.getMode();
                if (m != null) {
                    w = m.getPhysicalWidth();
                    h = m.getPhysicalHeight();
                    int rot = d.getRotation();
                    if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
                        int t = w;
                        w = h;
                        h = t;
                    }
                }
            }
        } else {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Rect b = wm.getMaximumWindowMetrics().getBounds();
                    w = b.width();
                    h = b.height();
                }
            }
        }

        int longSide = Math.max(w, h);
        int cap = maxLongSide;
        if (longSide > cap) {
            float s = cap / (float) longSide;
            w = Math.round(w * s);
            h = Math.round(h * s);
        }
        // 偶数：部分图形路径要求对齐
        return new int[]{w & ~1, h & ~1};
    }
}
