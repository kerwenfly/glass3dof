import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// 原生引擎（`GlassEngine.java`）的 Dart 侧门面。
///
/// 只做三件事：把状态流翻译成强类型模型、把用户操作转成 MethodChannel 调用、
/// 维护一份界面用的诊断日志。所有会话逻辑都在原生侧，UI 不持有任何引擎状态。

class PoseStatus {
  const PoseStatus({
    this.tracking = false,
    this.yaw = 0,
    this.pitch = 0,
    this.roll = 0,
    this.hz = 0,
    this.frozen = false,
    this.biasDps = 0,
    this.residualDps = 0,
  });

  final bool tracking;
  final double yaw;
  final double pitch;
  final double roll;
  final double hz;
  final bool frozen;

  /// IMU 自身的估计零偏（三轴最大，°/s）。数值大不代表在漂，它是被减掉的那部分。
  final double biasDps;

  /// 静止窗口内的残差（°/s）。这才是"还会不会漂"的指标，收敛后接近噪声底。
  final double residualDps;

  factory PoseStatus.fromMap(Map<Object?, Object?> m) => PoseStatus(
        tracking: m['tracking'] == true,
        yaw: _d(m['yaw']),
        pitch: _d(m['pitch']),
        roll: _d(m['roll']),
        hz: _d(m['hz']),
        frozen: m['frozen'] == true,
        biasDps: _d(m['biasDps']),
        residualDps: _d(m['residualDps']),
      );
}

class CaptureStatus {
  const CaptureStatus({this.wantW = 0, this.wantH = 0, this.w = 0, this.h = 0});

  final int wantW;
  final int wantH;
  final int w;
  final int h;

  bool get matches => w > 0 && w == wantW && h == wantH;

  factory CaptureStatus.fromMap(Map<Object?, Object?> m) => CaptureStatus(
        wantW: _i(m['wantW']),
        wantH: _i(m['wantH']),
        w: _i(m['w']),
        h: _i(m['h']),
      );
}

class ImuStatus {
  const ImuStatus({this.open = false, this.frames = 0, this.sendMode = ''});

  final bool open;
  final int frames;
  final String sendMode;

  factory ImuStatus.fromMap(Map<Object?, Object?> m) => ImuStatus(
        open: m['open'] == true,
        frames: _i(m['frames']),
        sendMode: (m['sendMode'] as String?) ?? '',
      );
}

class EngineStatus {
  const EngineStatus({
    this.running = false,
    this.starting = false,
    this.glasses = false,
    this.displayName = '—',
    this.displayId = -1,
    this.stageReady = false,
    this.stageLaunched = false,
    this.presentation = false,
    this.frames = 0,
    this.aspect = 1,
    this.mode = 'mirror',
    this.component,
    this.label,
    this.pose = const PoseStatus(),
    this.capture = const CaptureStatus(),
    this.imu = const ImuStatus(),
    this.accessibilityEnabled = false,
    this.canInject = false,
    this.shizukuInstalled = false,
    this.shizukuReady = false,
    this.error,
    this.version = '',
  });

  final bool running;
  final bool starting;
  final bool glasses;
  final String displayName;
  final int displayId;
  final bool stageReady;
  final bool stageLaunched;
  final bool presentation;
  final int frames;
  final double aspect;
  final String mode;
  final String? component;
  final String? label;
  final PoseStatus pose;
  final CaptureStatus capture;
  final ImuStatus imu;
  final bool accessibilityEnabled;
  final bool canInject;
  final bool shizukuInstalled;
  final bool shizukuReady;
  final String? error;
  final String version;

  bool get hosted => mode == 'hosted';
  bool get canInjectInput => shizukuReady || canInject;

  factory EngineStatus.fromMap(Map<Object?, Object?> m) {
    final pose = m['pose'];
    final capture = m['capture'];
    final imu = m['imu'];
    final a11y = m['accessibility'];
    final shizuku = m['shizuku'];
    return EngineStatus(
      running: m['running'] == true,
      starting: m['starting'] == true,
      glasses: m['glasses'] == true,
      displayName: (m['displayName'] as String?) ?? '—',
      displayId: _i(m['displayId']),
      stageReady: m['stageReady'] == true,
      stageLaunched: m['stageLaunched'] == true,
      presentation: m['presentation'] == true,
      frames: _i(m['frames']),
      aspect: _d(m['aspect'], 1),
      mode: (m['mode'] as String?) ?? 'mirror',
      component: m['component'] as String?,
      label: m['label'] as String?,
      pose: pose is Map ? PoseStatus.fromMap(pose) : const PoseStatus(),
      capture: capture is Map ? CaptureStatus.fromMap(capture) : const CaptureStatus(),
      imu: imu is Map ? ImuStatus.fromMap(imu) : const ImuStatus(),
      accessibilityEnabled: a11y is Map && a11y['enabled'] == true,
      canInject: a11y is Map && a11y['canInject'] == true,
      shizukuInstalled: shizuku is Map && shizuku['installed'] == true,
      shizukuReady: shizuku is Map && shizuku['ready'] == true,
      error: (m['error'] as String?)?.isEmpty == true ? null : m['error'] as String?,
      version: (m['version'] as String?) ?? '',
    );
  }
}

class EngineConfig {
  const EngineConfig({
    this.size = 70,
    this.distance = 15,
    this.fov = 8,
    this.resolution = 1,
    this.freeze = true,
    this.invertForward = false,
    this.invertYaw = false,
    this.invertPitch = false,
    this.invertRoll = false,
    this.mode = 'mirror',
    this.compat = false,
    this.trusted = true,
    this.component,
    this.label,
    this.version = '',
  });

  final int size;
  final int distance;
  final int fov;
  final int resolution;
  final bool freeze;
  final bool invertForward;
  final bool invertYaw;
  final bool invertPitch;
  final bool invertRoll;
  final String mode;
  final bool compat;
  final bool trusted;
  final String? component;
  final String? label;
  final String version;

  bool get hosted => mode == 'hosted';

  EngineConfig copyWith({
    int? size,
    int? distance,
    int? fov,
    int? resolution,
    bool? freeze,
    bool? invertForward,
    bool? invertYaw,
    bool? invertPitch,
    bool? invertRoll,
    String? mode,
    bool? compat,
    bool? trusted,
    String? component,
    String? label,
  }) =>
      EngineConfig(
        size: size ?? this.size,
        distance: distance ?? this.distance,
        fov: fov ?? this.fov,
        resolution: resolution ?? this.resolution,
        freeze: freeze ?? this.freeze,
        invertForward: invertForward ?? this.invertForward,
        invertYaw: invertYaw ?? this.invertYaw,
        invertPitch: invertPitch ?? this.invertPitch,
        invertRoll: invertRoll ?? this.invertRoll,
        mode: mode ?? this.mode,
        compat: compat ?? this.compat,
        trusted: trusted ?? this.trusted,
        component: component ?? this.component,
        label: label ?? this.label,
        version: version,
      );

  factory EngineConfig.fromMap(Map<Object?, Object?> m) => EngineConfig(
        size: _i(m['size'], 70),
        distance: _i(m['distance'], 15),
        fov: _i(m['fov'], 8),
        resolution: _i(m['resolution'], 1),
        freeze: m['freeze'] != false,
        invertForward: m['invertForward'] == true,
        invertYaw: m['invertYaw'] == true,
        invertPitch: m['invertPitch'] == true,
        invertRoll: m['invertRoll'] == true,
        mode: (m['mode'] as String?) ?? 'mirror',
        compat: m['compat'] == true,
        trusted: m['trusted'] != false,
        component: m['component'] as String?,
        label: m['label'] as String?,
        version: (m['version'] as String?) ?? '',
      );
}

class AppEntry {
  const AppEntry(this.label, this.component);

  final String label;
  final String component;

  factory AppEntry.fromMap(Map<Object?, Object?> m) =>
      AppEntry((m['label'] as String?) ?? '', (m['component'] as String?) ?? '');
}

class LogLine {
  LogLine(this.text) : at = DateTime.now();

  final String text;
  final DateTime at;

  String get clock =>
      '${at.hour.toString().padLeft(2, '0')}:${at.minute.toString().padLeft(2, '0')}:${at.second.toString().padLeft(2, '0')}';
}

class EngineModel extends ChangeNotifier {
  EngineModel();

  static const MethodChannel _channel = MethodChannel('glass3dof/engine');
  static const EventChannel _statusChannel = EventChannel('glass3dof/status');

  EngineConfig? config;
  EngineStatus status = const EngineStatus();
  final List<LogLine> logs = <LogLine>[];

  /// 会话启动后由首页消费：置 true 表示「该弹出触控板了」。
  bool padRequested = false;

  /// 一次性提示（toast），由 UI 取走并清空。
  String? pendingToast;
  String? lastError;

  StreamSubscription? _sub;
  bool loading = false;
  bool _started = false;

  Future<void> init() async {
    if (_started) return;
    _started = true;
    loading = true;
    notifyListeners();

    _channel.setMethodCallHandler(_onNativeCall);
    try {
      final raw = await _channel.invokeMethod<Map<Object?, Object?>>('getConfig');
      if (raw != null) {
        config = EngineConfig.fromMap(raw);
      }
    } on PlatformException catch (e) {
      lastError = e.message;
    }

    _sub = _statusChannel.receiveBroadcastStream().listen((event) {
      if (event is Map) {
        status = EngineStatus.fromMap(event);
        if (status.error != null) lastError = status.error;
        notifyListeners();
      }
    }, onError: (Object e) {
      lastError = '状态通道断开：$e';
      notifyListeners();
    });

    loading = false;
    notifyListeners();
  }

  Future<dynamic> _onNativeCall(MethodCall call) async {
    switch (call.method) {
      case 'onToast':
        pendingToast = call.arguments as String?;
        notifyListeners();
        break;
      case 'onError':
        lastError = call.arguments as String?;
        notifyListeners();
        break;
      case 'onSessionStarted':
        // 只有扩展屏模式才需要触控板；镜像整屏时手机不参与交互
        padRequested = config?.hosted ?? false;
        notifyListeners();
        break;
      case 'onSessionStopped':
        padRequested = false;
        notifyListeners();
        break;
    }
    return null;
  }

  void addLog(String text) {
    logs.insert(0, LogLine(text));
    if (logs.length > 120) logs.removeLast();
  }

  void consumeToast() => pendingToast = null;

  void clearError() {
    lastError = null;
    _channel.invokeMethod('dismissError');
    notifyListeners();
  }

  void consumePadRequest() => padRequested = false;

  /// 重新拉一次配置（"重新检测"按钮用）：状态流本身每 800ms 自己刷。
  Future<void> refresh() async {
    try {
      final raw = await _channel.invokeMethod<Map<Object?, Object?>>('getConfig');
      if (raw != null) {
        config = EngineConfig.fromMap(raw);
      }
    } on PlatformException catch (e) {
      lastError = e.message;
    }
    notifyListeners();
  }

  // ---------------- 操作

  Future<void> start() async {
    final c = config;
    if (c == null) return;
    lastError = null;
    await _channel.invokeMethod('start', {
      'mode': c.mode,
      'component': c.component,
      'label': c.label,
      'compat': c.compat,
      'trusted': c.trusted,
    });
    notifyListeners();
  }

  Future<void> stop() async {
    await _channel.invokeMethod('stop');
    notifyListeners();
  }

  Future<void> setParams({int? size, int? distance, int? fov}) async {
    if (config == null) return;
    config = config!.copyWith(size: size, distance: distance, fov: fov);
    notifyListeners();
    await _channel.invokeMethod('setParams', {
      'size': config!.size,
      'distance': config!.distance,
      'fov': config!.fov,
    });
  }

  Future<void> setResolution(int level) async {
    if (config == null) return;
    config = config!.copyWith(resolution: level);
    notifyListeners();
    await _channel.invokeMethod('setResolution', {'level': level});
  }

  Future<void> setPose({
    bool? freeze,
    bool? invertForward,
    bool? invertYaw,
    bool? invertPitch,
    bool? invertRoll,
  }) async {
    if (config == null) return;
    config = config!.copyWith(
      freeze: freeze,
      invertForward: invertForward,
      invertYaw: invertYaw,
      invertPitch: invertPitch,
      invertRoll: invertRoll,
    );
    notifyListeners();
    await _channel.invokeMethod('setPose', {
      'freeze': config!.freeze,
      'invertForward': config!.invertForward,
      'invertYaw': config!.invertYaw,
      'invertPitch': config!.invertPitch,
      'invertRoll': config!.invertRoll,
    });
  }

  Future<void> setMode(String mode) async {
    if (config == null) return;
    config = config!.copyWith(mode: mode);
    notifyListeners();
  }

  Future<void> setCompat(bool value) async {
    if (config == null) return;
    config = config!.copyWith(compat: value);
    notifyListeners();
  }

  Future<void> setTrusted(bool value) async {
    if (config == null) return;
    config = config!.copyWith(trusted: value);
    notifyListeners();
  }

  Future<void> setHostedApp(AppEntry app) async {
    if (config == null) return;
    config = config!.copyWith(component: app.component, label: app.label);
    notifyListeners();
    await _channel.invokeMethod('launchHosted', {
      'component': app.component,
      'label': app.label,
    });
  }

  Future<List<AppEntry>> listApps() async {
    try {
      final raw = await _channel.invokeMethod<List<Object?>>('listApps');
      if (raw == null) return const [];
      return raw
          .whereType<Map>()
          .map((e) => AppEntry.fromMap(e))
          .where((e) => e.component.contains('/'))
          .toList();
    } on PlatformException catch (e) {
      lastError = e.message;
      notifyListeners();
      return const [];
    }
  }

  Future<void> openAccessibilitySettings() =>
      _channel.invokeMethod('openAccessibilitySettings');

  Future<void> requestShizuku() => _channel.invokeMethod('requestShizuku');

  Future<void> recenter() => _channel.invokeMethod('recenter');

  /// 重新采样零偏（不动画面中位）。零偏估偏或温漂变大后手动触发。
  Future<void> recalibrate() => _channel.invokeMethod('recalibrate');

  Future<void> setCursor({double? u, double? v, bool? visible}) => _channel
      .invokeMethod('setCursor', {'u': u, 'v': v, 'visible': visible});

  Future<void> tap({bool longPress = false}) =>
      _channel.invokeMethod('tap', {'longPress': longPress});

  Future<void> scroll({required int axis, required int dir}) =>
      _channel.invokeMethod('scroll', {'axis': axis, 'dir': dir});

  Future<void> key(int code) => _channel.invokeMethod('key', {'code': code});

  @override
  void dispose() {
    _sub?.cancel();
    _channel.setMethodCallHandler(null);
    super.dispose();
  }
}

int _i(Object? v, [int fallback = 0]) => v is num ? v.toInt() : fallback;

double _d(Object? v, [double fallback = 0]) => v is num ? v.toDouble() : fallback;
