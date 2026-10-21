# Air3DoF · 把手机画面「钉」在雷鸟 Air 1s 的空间里

> 插上雷鸟 Air 1s，手机画面以 3DoF 悬停的方式呈现在眼镜里：转头时画面在视野中反向滑动，
> 像一副挂在 2.5 米外的屏幕；手机则变成一块触控板。

`v2.0.1` · Android 10+ (minSdk 29 / targetSdk 34) · Flutter Material 3 UI + Java/OpenGL ES 2.0 引擎

---

## 目录

- [这是什么](#这是什么)
- [效果与原理](#效果与原理)
- [特性](#特性)
- [硬件与系统前提](#硬件与系统前提)
- [两种投屏形态](#两种投屏形态)
- [架构](#架构)
- [目录结构](#目录结构)
- [构建](#构建)
- [使用](#使用)
- [参数说明](#参数说明)
- [权限与合规](#权限与合规)
- [排错](#排错)
- [开发者笔记（踩过的坑）](#开发者笔记踩过的坑)
- [已知限制与路线图](#已知限制与路线图)
- [相关文档](#相关文档)

---

## 这是什么

雷鸟 Air 1s 是一副 USB-C 直连的 BirdBath 眼镜，插上手机后系统会把它当成一块**外接 1080p 显示器**——画面固定贴在眼前，转头也跟着走，看久了很不舒服。

本项目做两件事：

1. **读出眼镜内置的 IMU**（USB HID，VID `0x1BBB` / PID `0xAF50`，64 字节报文、约 460 Hz），用 Mahony 姿态融合算出头部朝向；
2. **把手机画面渲染成一块虚拟屏幕**，按头部朝向做反向位移（3DoF 悬停），让画面「钉」在空间里。

手机端同时变成操控板：相对位移移动光标、轻点点击、按住长按、双指滚动，全部注入到眼镜那块屏。

---

## 效果与原理

```
        真实世界                     眼镜里看到
   ┌───────────────┐            ┌───────────────┐
   │               │            │  ┌─────────┐  │  ← 画面钉在空间里
   │  你转头 →     │    IMU     │  │ 手机画面 │  │     转头时反向滑动
   │               │  ───────►  │  └─────────┘  │
   └───────────────┘    3DoF    └───────────────┘
```

数据链路：

```
眼镜 IMU ──USB HID──► GlassUsb ──采样──► HeadPose(Mahony + OneEuro + 静止冻结)
                                              │
手机屏幕 ──MediaProjection／应用自绘──► VirtualDisplay ──SurfaceTexture──► QuadRenderer(GL)
                                              │                              │
                                       StageActivity（副屏任务）◄─────────────┘
                                              │
手机触控板 / Shizuku / 无障碍 ──注入──► 眼镜那块屏
```

- 姿态只在**静止**时用 EMA 缓慢跟零偏（约 0.5 秒收敛），运动时不拖影；
- 首帧不做阻塞校准：当期角速度当零偏种子、加速度定「上」方向，插上就能用；
- 渲染用 OpenGL ES 2.0 画一个带透视的四边形，纹理来自虚拟屏。

---

## 特性

| | 说明 |
| --- | --- |
| 🥽 3DoF 悬停 | 画面按 yaw/pitch 反向位移、按 roll 轻微旋转，可调尺寸 / 虚拟距离 / 视场角 |
| 🪟 两种形态 | **镜像整屏**（MediaProjection 捕获手机画面）／**扩展屏**（任意应用独立跑在眼镜屏上） |
| 🔌 三条建屏通道 | 本应用私有虚拟屏（免特权）／Shizuku 受信屏（兼容「只认默认屏」的应用）／MediaProjection 公共屏（兼容模式） |
| 📱 手机当触控板 | 相对位移光标 + 轻点 + 长按 + 双指滚动 + 黑屏，坐标按眼镜屏像素换算 |
| 🤖 免 Shizuku 全链路 | 无障碍辅助自动确认录屏/USB 授权、跨屏焦点恢复、`dispatchGesture` 触摸注入 |
| ⚡ Shizuku 增强 | `am start --display` 把应用拉到扩展屏、`input -d` 注入输入（含键盘） |
| 🎨 Flutter Material 3 UI | 跟随系统深浅色、8dp 栅格、统一圆角、零阴影，含加载/空/错误/重试全部状态 |
| 🧊 静止冻结 | 抑制静止时的画面漂移 |
| 🔄 断线自愈 | DP 重新协商后自动重挂、6 秒无帧自动重建虚拟屏 |

---

## 硬件与系统前提

| 项 | 要求 |
| --- | --- |
| 手机 | 支持 USB-C **DP Alt Mode**（小米高端 / 三星 S 系 / 一加 / 部分 P 系列等），否则眼镜根本不出图 |
| 眼镜 | 雷鸟 Air 1s，USB-C 直连手机（VID `0x1BBB` / PID `0xAF50`） |
| 系统 | Android 10+（minSdk 29）；Android 13+ 需允许「通知」权限才能看到前台通知；Android 14 每次会话需授权「共享屏幕」 |
| 其他 | **先退出雷鸟官方 App**——它会占用同一个 HID 接口；线缆必须是支持数据的全功能线 |

可选增强：

| 组件 | 作用 | 没有它也能用吗 |
| --- | --- | --- |
| 无障碍辅助（本应用内） | 自动确认授权弹窗、跨屏焦点恢复、触摸注入 | 能，但授权要手动点、扩展屏只能看不能摸 |
| [Shizuku](https://shizuku.rikka.app/) | 受信虚拟屏、`am start --display`、`input -d`（含键盘注入） | 能，走私有屏 + 无障碍注入 |

---

## 两种投屏形态

### 形态 A：镜像整屏（默认）

1. `MediaProjection` 拿到录屏令牌 → `createVirtualDisplay(PUBLIC)` 建一块虚拟屏；
2. 虚拟屏的 Surface 交给 GL 渲染器，画面贴到眼镜上；
3. 手机屏幕内容完整镜像，适合看视频、刷信息流。

### 形态 B：扩展屏（任意应用）

1. 本应用自己创建**私有虚拟屏**（owner 是自己，不弹录屏授权）；
2. 用 `ActivityOptions.setLaunchDisplayId` 把选中的应用直接启动到那块屏；
3. 手机端不再显示投屏内容，只做触控板。

三条建屏通道的优先级：

| 通道 | 权限 | 适用 |
| --- | --- | --- |
| Shizuku 受信屏 | Shizuku | 兼容性最好，等同真实外接显示器 |
| 本应用私有屏 | 无 | 默认路径，免特权，绝大多数应用可用 |
| MediaProjection 公共屏 | 录屏授权 | 私有屏被系统重定向时的兜底（「兼容模式」） |

---

## 架构

**引擎全部在原生侧**（USB HID、OpenGL、MediaProjection、Shizuku、无障碍都无法在 Dart 里做），**界面全部在 Flutter 侧**。

```
┌──────────────────────────── Flutter (Dart) ────────────────────────────┐
│  HomePage（状态/参数/模式/辅助/诊断）      PadPage（触控板/黑屏）        │
│  EngineModel ⇄ MethodChannel(glass3dof/engine) / EventChannel(status)  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌─────────────────────────── Android (Java) ─────────────────────────────┐
│ MainActivity(FlutterActivity) → GlassEngine（会话编排）                 │
│  ├─ GlassUsb       USB HID 读 IMU（460Hz，64 字节报文，多发送方式探测）  │
│  ├─ HeadPose       Mahony + OneEuro + 静止冻结 + 方向取反               │
│  ├─ QuadRenderer   GL ES 2.0 透视四边形渲染（手机/眼镜两端共用）         │
│  ├─ StageActivity  眼镜屏上的画面宿主（独立任务栈，切后台仍可见）        │
│  ├─ CaptureService 前台服务持有 MediaProjection（Android 14 要求）      │
│  ├─ ExtendHost     私有/受信虚拟屏 + 应用启动 + 出帧看门狗              │
│  ├─ TrustedProjection + ProjectionUserService（AIDL）  Shizuku 受信屏  │
│  ├─ ShizukuBridge  `am start --display` / `input -d`（IO 单线程串行）   │
│  └─ AutoConsentAccessibilityService  自动授权 / 焦点恢复 / 手势注入     │
└────────────────────────────────────────────────────────────────────────┘
```

**线程模型**（原实现的教训都固化在这里）：

| 线程 | 职责 |
| --- | --- |
| 主线程 | 生命周期、DisplayManager 回调、VirtualDisplay 挂载 |
| `engine-io`（单线程） | USB open/close、应用列表查询（阻塞调用不占 UI） |
| `shizuku-sh`（单线程） | shell 命令串行执行，保证 tap/swipe 顺序 |
| `a11y-worker`（单线程） | 无障碍节点树遍历（binder 往返多，**绝不能上主线程**） |
| GL 线程 | 姿态插值 + 渲染 |

**通信协议**

| 通道 | 方向 | 内容 |
| --- | --- | --- |
| `glass3dof/engine` | Dart → 原生 | `getConfig` `start` `stop` `setParams` `setPose` `setResolution` `listApps` `launchHosted` `openAccessibilitySettings` `requestShizuku` `setCursor` `tap` `scroll` `key` `recenter` `dismissError` |
| `glass3dof/engine` | 原生 → Dart | `onToast` `onError` `onSessionStarted` `onSessionStopped` |
| `glass3dof/status` | 原生 → Dart | 800ms 状态快照（会话/姿态/捕获/IMU/无障碍/Shizuku） |

---

## 目录结构

```
Air_1s/
├── glass3dof_flutter/            ★ 当前版本（Flutter UI + 原生引擎，v2.0.1）
│   ├── lib/                      Dart UI
│   │   ├── main.dart             入口（主题跟随系统、字号上限 1.4×）
│   │   ├── theme/app_theme.dart  Material 3 色彩 / 8dp / 圆角 / 零阴影
│   │   ├── engine/engine_client.dart  通道门面 + 强类型状态模型
│   │   ├── pages/home_page.dart  状态/主操作/模式/参数/姿态/辅助/诊断
│   │   ├── pages/pad_page.dart   触控板（含黑屏）
│   │   └── widgets/common.dart   卡片/胶囊/滑块/开关/空错加载态/入场动画
│   └── android/app/src/main/
│       ├── java/com/rxz/glass3dof/   ← 原生引擎（与旧版同源）
│       │   ├── MainActivity.java      FlutterActivity 宿主
│       │   └── GlassEngine.java       原 MainActivity 的会话逻辑
│       ├── aidl/…/IProjectionService.aidl
│       └── res/xml/accessibility_service_config.xml
│
├── glass3dof/                    ◇ 旧版（纯原生 View UI，v1.0.5，保留作对照）
│   ├── app/src/main/java/com/rxz/glass3dof/   同一套引擎 + 原生界面
│   └── docs/                     开源调研 / 技术方案 / 代码审计
│
└── screen/                       研究资料（不参与构建）
    ├── base.apk                  AR Ctrl 逆向样本
    ├── connect-screen-master/    connect-screen 明文源码
    └── analysis/                 无障碍拓展投屏的逆向分析与杂交方案
```

---

## 构建

### Flutter 版（当前版本）

```bash
cd glass3dof_flutter
flutter pub get
flutter build apk --release --target-platform android-arm64
# 产物：build/app/outputs/flutter-apk/app-release.apk
# 归档：Air3DoF-2.0.1-flutter-release.apk
```

- `applicationId = com.rxz.glass3dof`，`minSdk 29` / `targetSdk 34`，release 使用 **debug 签名**（侧载自用；对外分发请换私有 keystore）
- **故意关闭 R8**（`isMinifyEnabled = false`）：Shizuku 的 `ProjectionUserService` 是运行时按 Class 反射实例化的，混淆后会起不来
- 工程里有一个 Gradle 钩子 `sanitizeAidlStubs()`：`aidl.exe` 在中文用户名路径下会把生成文件头部注释按 GBK 写出、导致 javac 读 UTF-8 报错（且 AGP 会误判成功、只产出部分 class），该钩子在 javac 前把 AIDL 生成文件净化成纯 ASCII

### 原生版（旧版，纯 View UI）

```bash
cd glass3dof
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

### 版本与兼容

| 版本 | versionCode | 说明 |
| --- | --- | --- |
| `1.0.5` | 105 | 纯原生 UI，含无障碍免 Shizuku 链路 |
| `2.0.1` | 201 | Flutter Material 3 UI；引擎与设置项不变，可**直接覆盖安装** 1.x（配置键未变，参数会继承） |

安装：

```bash
adb install -r glass3dof_flutter/Air3DoF-2.0.1-flutter-release.apk
```

---

## 使用

1. 插好眼镜，确认手机出现「外接显示器」（多数机型默认镜像当前画面）。
2. 打开 Air3DoF：
   - 首次会依次请求 **USB 设备访问**、**通知**、**共享屏幕**（仅镜像/兼容模式需要）；
   - 建议先到「辅助能力」里开启 **无障碍辅助**——之后授权弹窗会被自动确认，且没有 Shizuku 也能点击眼镜画面。
3. 选投屏方式：
   - **镜像整屏**：手机画面直接投；
   - **扩展屏**：先选一个应用，它会被启动到眼镜那块屏上。
4. 点「开始悬停」，眼镜上出现画面后即进入 3DoF 模式。
5. **扩展屏模式**会自动跳到**触控板**（镜像整屏不进触控板，手机不参与交互）：
   - 拖动 = 移动光标（相对位移，不是绝对定位）
   - 轻点 = 在光标处点击 · 按住 500ms = 长按
   - 双指滑动 = 四向滚动
   - 右上角：复位视角 / 返回键 / 黑屏 / 停止
6. **黑屏**：手机整屏纯黑（连状态栏都隐藏）、眼镜里的光标一起消失，双击任意位置恢复。
7. 手机端可实时调：尺寸、虚拟距离、垂直视场角、捕获清晰度、静止冻结、四轴方向取反。

> 提示：会话运行中手机可以正常去用别的 App，眼镜画面由 `StageActivity` 独立任务栈维持。

---

## 参数说明

| 参数 | 范围 | 含义 |
| --- | --- | --- |
| 尺寸 | 0.30×–1.30× | 虚拟屏幕相对视场的填充比例，越大画面越「大」 |
| 虚拟距离 | 1.0–11.0 m | 影响透视与位移幅度：越远，转头时画面滑得越慢 |
| 垂直视场角 | 15°–45° | Air 1s 实测约 **23°**，填错会让位移手感偏快或偏慢 |
| 捕获清晰度 | 1920 / 1600 / 1280（长边） | 越高越清晰、越费电；实际值见「诊断」里的期望/实际分辨率 |
| 静止时冻结 | 开/关 | 静止超过阈值后停止积分，抑制漂移 |
| 前后/左右/上下/倾斜取反 | 开/关 | 算法已按 Air 1s 实测预置取反；换机型或换固件方向不对时逐轴再反一次 |

---

## 权限与合规

| 权限 / 能力 | 用途 |
| --- | --- |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Android 14 起必须由带 `mediaProjection` 类型的前台服务持有录屏令牌 |
| `POST_NOTIFICATIONS` | 前台服务通知可见（拒绝不影响投屏） |
| USB 设备访问（运行时授权） | 打开眼镜的 HID 接口读 IMU |
| 屏幕录制（每次会话授权） | 镜像整屏 / 兼容模式；扩展屏默认私有屏**不需要** |
| `BIND_ACCESSIBILITY_SERVICE`（无障碍辅助） | 自动确认录屏/USB 授权弹窗、跨屏焦点恢复、`dispatchGesture` 触摸注入 |
| Shizuku（可选） | 受信虚拟屏、`am start --display`、`input -d` |

**关于无障碍**：本应用的无障碍服务只做三件事——点掉系统授权弹窗的确认按钮、把输入焦点还给眼镜屏上的应用、向眼镜屏注入点击/滑动。不读取、不上传任何内容。该用法不符合 Google Play 对无障碍 API 的政策，本项目**仅供侧载自用**；若要分发，请把「自动确认」做成可关闭的极客选项。

**关于 DRM**：Netflix / 部分会员视频走硬件 DRM 通路，MediaProjection 只能拿到黑屏，这是系统限制，无解。

---

## 排错

| 现象 | 原因 / 处理 |
| --- | --- |
| 提示「未检测到眼镜显示器」 | 手机不支持 DP Alt Mode，或线缆只有充电功能。先确认不装任何 App 时插上眼镜能出画面 |
| 提示「未找到眼镜 USB 设备」 | 雷鸟官方 App 占用 HID / 线缆无数据线 / USB2 被 DP 复用。看「诊断」里的 USB 与 IMU 状态 |
| `claimInterface` 失败 | 同上；非 root 无法从内核驱动手里抢接口 |
| 接口打开但一直「等待 IMU」 | 报告描述符为 64 字节无 Report ID，程序已按 64 收发并自动轮换 5 种下发方式；仍无数据请换线/换口 |
| 眼镜上是灰屏 | 灰屏 = 渲染器在跑但**没收到投屏帧**。「诊断」里帧数为 0 → 捕获没挂上（6 秒会自动重建一次）；帧数很小又不再增长 → 虚拟屏被反复重建 |
| 画面不动 | 勾了「静止时冻结」且你在微动（阈值约 2.9°/s）；或尚未收到 IMU |
| 转头方向相反 | 用四轴「取反」逐项修正，别一次全开 |
| 诊断里「估计零偏」数值很大（如 2~3 °/s） | 这是 IMU 自身的零速偏移量，**已被减掉**，不代表在漂；看「静止残差」才有效——收敛后应 < 0.1 °/s。换环境或温漂变大时点「重新校准」 |
| 眼镜里没有光标 | 黑屏模式中光标是隐藏的；或触控板页面已退出 |
| 触控板点了没反应 | 底部提示「仅显示」：既没 Shizuku 也没开无障碍辅助 → 至少开一个 |
| 无障碍列表里找不到 Air3DoF | 需 `android:exported="true"`（隐式 Intent 枚举会排除非导出组件），已修复。列表在「已下载的服务」分组，名字是 Air3DoF |
| 扩展屏里的应用被拉回手机 | 该应用不支持多屏：开 Shizuku 用受信屏，或换「兼容模式」+ `am start --display` |
| 播放会员视频全黑 | DRM 限制，无解 |

日志：

```bash
adb logcat -s GlassEngine ExtendHost CaptureService AutoConsent
```

---

## 开发者笔记（踩过的坑）

1. **AIDL + 中文用户名**：`aidl.exe` 把生成文件的头部注释按系统代码页写出，javac 读 UTF-8 报错；更坑的是 AGP 仍判任务成功、只产出一部分 class → APK 里缺引擎类。已在 `build.gradle.kts` 里用 `sanitizeAidlStubs()` 在 javac 前净化。
2. **R8 与 Shizuku UserService**：UserService 由 Shizuku 反射实例化，类名/构造器被改写会直接起不来 → release 关闭 `minifyEnabled`。
3. **无障碍 `exported`**：系统设置用隐式 Intent 枚举无障碍服务，跨进程解析会排除非导出组件——设成 `false` 时服务在 `dumpsys accessibility` 里可见、但设置列表里根本不出现。
4. **无障碍节点操作不能上主线程**：`getWindows/getRoot/递归 getChild` 全是 binder 往返，会拖死主线程 → `CaptureService.attachSurface` 排不上队 → 眼镜灰屏。全部移到 `a11y-worker`，并给焦点恢复加 800ms 节流 + 5 次上限。
5. **`accessibilityEventTypes`**：`aapt2` 不接受 `0xFFFFFFFF` 字面量，要写 `typeAllMask`；但全事件掩码会引发事件洪水，最终收窄到 `windowStateChanged|windowContentChanged|windowsChanged`。
6. **MediaProjection 授权的时序**：自动点击必须在 `createScreenCaptureIntent()` **之前**布防（5 秒窗口、250ms 轮询、450ms 二次扫描），否则会错过弹窗。
7. **DisplayManager 自激循环**：自己创建的虚拟屏也会触发 `onDisplayAdded`，不过滤掉会「建屏 → 触发 → 重挂 → 再建屏」。
8. **装错包的假象**：项目里可能残留旧的 `Air3DoF-1.0.x-release.apk`，装错会以为功能没生效——认首页副标题的版本号 `vX.Y.Z`。

---

## 已知限制与路线图

**限制**

- 无 Shizuku 时只能做手势注入（点击/滑动/返回），**键盘不可用**；且 `dispatchGesture` 指定 displayId 需要 Android 11+
- 扩展屏能否启动第三方应用取决于厂商对多窗口的支持，部分 ROM 会重定向回主屏
- DRM 视频无法捕获
- 无障碍自动化不符合 Play 政策，仅侧载

**路线图**

- [ ] 眼镜端 6DoF（利用 IMU 的线性加速度做位置追踪）
- [ ] 双指缩放 / 三指手势映射
- [ ] 插眼镜自动进入上次会话配置
- [ ] 眼镜端画质选项（超分/锐化）与延迟统计面板

---

## 相关文档

| 文档 | 内容 |
| --- | --- |
| [`glass3dof/docs/00-开源调研.md`](../glass3dof/docs/00-开源调研.md) | 同类项目横向对比与取舍 |
| [`glass3dof/docs/01-技术方案.md`](../glass3dof/docs/01-技术方案.md) | 数据流、坐标约定、线程模型、风险表 |
| [`glass3dof/docs/02-代码审计报告.md`](../glass3dof/docs/02-代码审计报告.md) | 历史代码审计与修复记录 |
| [`screen/analysis/无障碍拓展投屏实现分析.md`](../screen/analysis/无障碍拓展投屏实现分析.md) | AR Ctrl / connect-screen 逆向：无障碍拓展投屏的实现机制 |
| [`screen/无障碍拓展投屏杂交方案.md`](../screen/无障碍拓展投屏杂交方案.md) | 免 Shizuku 的方案设计与实施顺序 |

## 许可

个人项目，仅供学习与自用。使用前请自行确认所在地区关于屏幕录制、无障碍服务与第三方客户端的相关规定。
