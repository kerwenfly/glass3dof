import java.io.File
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

/**
 * aidl.exe 在非英文用户名（如 C:\Users\清华同方\...）下，会把生成文件头部的
 * "Using: ..." 注释按系统代码页（GBK）写出，javac 以 UTF-8 读取必然报
 * "编码 UTF-8 的不可映射字符"。头部只是注释、AIDL 文件本身是纯 ASCII，
 * 所以在 javac 之前把所有 AIDL 生成文件里的非 ASCII 字节滤掉即可。
 *
 * 不做这一步的后果不是编译失败，而是 javac 只写出一部分 class 文件、
 * AGP 仍判 task 成功 → 打出的 APK 里缺引擎类（运行即崩）。
 */
fun sanitizeAidlStubs(root: File) {
    if (!root.exists()) return
    root.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { f ->
        val bytes = f.readBytes()
        if (bytes.any { it < 0 }) {
            val ascii = StringBuilder(bytes.size)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                ascii.append(if (v < 0x80) v.toChar() else ' ')
            }
            f.writeText(ascii.toString(), Charsets.UTF_8)
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    doFirst {
        sanitizeAidlStubs(layout.buildDirectory.dir("generated/aidl_source_output_dir").get().asFile)
    }
}

android {
    namespace = "com.rxz.glass3dof"
    compileSdk = flutter.compileSdkVersion
    // 本机只装了 35/36/37 的 build-tools，显式指定避免联网拉取旧的
    buildToolsVersion = "36.0.0"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 原生引擎里全是中文注释/日志，显式 UTF-8，避免 Windows 默认代码页把源码读成乱码
        encoding = "UTF-8"
    }

    defaultConfig {
        applicationId = "com.rxz.glass3dof"
        // 原生引擎（USB HID / MediaProjection / 无障碍 / Shizuku UserService）要求 29+
        minSdk = 29
        // 固定 34：Android 15 的 targetSdk 35 变更会收紧前台服务与屏幕录制行为，保持已验证的语义
        targetSdk = 34
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildFeatures {
        // AGP 8 起默认关闭，Shizuku UserService 的 AIDL 需要它
        aidl = true
    }

    buildTypes {
        release {
            // 与原工程保持一致：关闭 R8 压缩/混淆。
            // Shizuku 的 UserService（ProjectionUserService）是运行时按 Class 反射实例化的，
            // 混淆后构造器/类名被改写会直接起不来；侧载自用没必要为几十 KB 冒这个险。
            isMinifyEnabled = false
            isShrinkResources = false
            // 侧载自用：用 debug 签名即可（对外分发必须换私有 keystore）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // 唯一第三方依赖：Shizuku（把应用启动到扩展屏、向扩展屏注入触摸）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

flutter {
    source = "../.."
}
