package com.rxz.glass3dof;

import android.content.Intent;

import androidx.annotation.NonNull;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;

/**
 * Flutter 宿主。界面全部在 Dart 侧，这里只负责挂载原生引擎门面 {@link GlassEngine}。
 *
 * 保留原有的单实例语义：录屏授权结果与 USB 授权回调都要回到同一个 Activity，
 * 所以 launchMode 仍是 singleTop（见 AndroidManifest）。
 */
public class MainActivity extends FlutterActivity {

    private GlassEngine engine;

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        engine = new GlassEngine(this, flutterEngine.getDartExecutor().getBinaryMessenger());
        engine.attach();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (engine != null) {
            // 插件自己的授权结果已先交给 super；这里只处理录屏授权（1001）
            engine.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onDestroy() {
        if (engine != null) {
            engine.dispose();
            engine = null;
        }
        super.onDestroy();
    }
}
