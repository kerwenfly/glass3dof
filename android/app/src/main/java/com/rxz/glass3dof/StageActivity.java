package com.rxz.glass3dof;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

/**
 * 眼镜那块屏上的画面载体。
 *
 * 它被 {@code ActivityOptions.setLaunchDisplayId} 直接投到外接屏上，
 * 因此拥有独立的窗口与任务栈：手机端界面切到后台（去开抖音、B 站）时，
 * 这里的画面依然保持可见，3DoF 悬停不会中断。
 */
public class StageActivity extends Activity {

    private static final String TAG = "StageActivity";
    private ScreenGlView glView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Stage.launched = true;
        android.view.Display disp = DisplayUtils.displayOfContext(this);
        if (disp == null || disp.getDisplayId() == android.view.Display.DEFAULT_DISPLAY) {
            // 没落到副屏（部分 ROM 忽略 setLaunchDisplayId）：主动退出，让手机端回退到 Presentation
            Log.w(TAG, "未落到眼镜屏，退出以触发回退");
            Stage.launched = false;
            finish();
            return;
        }
        Log.i(TAG, "onCreate on display " + disp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        glView = new ScreenGlView(this, Stage.pose);
        setContentView(glView);
        Stage.attach(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glView != null) {
            glView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (glView != null) {
            glView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Stage.detach(this);
        if (glView != null) {
            glView.renderer().release();
            glView = null;
        }
        super.onDestroy();
    }
}
