import 'dart:math' as math;
import 'package:flutter/material.dart';

import '../engine/engine_client.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import 'pad_page.dart';

/// 首页：状态 → 主操作 → 模式 → 参数 → 姿态 → 辅助 → 诊断。
///
/// 宽屏（≥760dp）两列排布，窄屏单列；所有块都在 720dp 容器内居中，
/// 避免平板/横屏下拉成一条超长细线。
class HomePage extends StatefulWidget {
  const HomePage({super.key, required this.engine});

  final EngineModel engine;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  EngineModel get engine => widget.engine;

  bool _padOpening = false;

  @override
  void initState() {
    super.initState();
    engine.addListener(_onEngineChanged);
    WidgetsBinding.instance.addPostFrameCallback((_) => engine.init());
  }

  @override
  void dispose() {
    engine.removeListener(_onEngineChanged);
    super.dispose();
  }

  void _onEngineChanged() {
    if (!mounted) return;

    final toast = engine.pendingToast;
    if (toast != null && toast.isNotEmpty) {
      engine.consumeToast();
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(toast)));
    }

    if (engine.padRequested && !_padOpening) {
      engine.consumePadRequest();
      _openPad();
    }
    setState(() {});
  }

  Future<void> _openPad() async {
    _padOpening = true;
    await Navigator.of(context).push(
      PageRouteBuilder(
        transitionDuration: const Duration(milliseconds: 260),
        reverseTransitionDuration: const Duration(milliseconds: 200),
        pageBuilder: (_, _, _) => PadPage(engine: engine),
        transitionsBuilder: (_, animation, _, child) => FadeTransition(
          opacity: animation,
          child: SlideTransition(
            position: Tween(begin: const Offset(0, 0.04), end: Offset.zero)
                .animate(CurvedAnimation(
                    parent: animation, curve: Curves.easeOutCubic)),
            child: child,
          ),
        ),
      ),
    );
    _padOpening = false;
  }

  @override
  Widget build(BuildContext context) {
    final status = engine.status;
    final config = engine.config;
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Air 3D 悬停'),
            Text(
              config == null
                  ? '连接引擎…'
                  : '雷鸟 Air 1s · v${config.version}',
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ],
        ),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 16),
            child: Center(
              child: StatusPill(
                text: status.running
                    ? '运行中'
                    : (status.starting ? '启动中' : '未开始'),
                tone: status.running
                    ? PillTone.active
                    : (status.starting ? PillTone.pending : PillTone.idle),
              ),
            ),
          ),
        ],
      ),
      body: config == null
          ? const _BootView()
          : LayoutBuilder(
              builder: (context, box) {
                final wide = box.maxWidth >= 760;
                final content = wide
                    ? Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(flex: 3, child: _leftColumn(status, config)),
                          const SizedBox(width: kGap * 2),
                          Expanded(flex: 2, child: _rightColumn(status, config)),
                        ],
                      )
                    : Column(
                        children: [
                          _leftColumn(status, config),
                          const SizedBox(height: kGap * 2),
                          _rightColumn(status, config),
                        ],
                      );
                return SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 40),
                  child: Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 1040),
                      child: content,
                    ),
                  ),
                );
              },
            ),
    );
  }

  // ---------------------------------------------------------------- 左列

  Widget _leftColumn(EngineStatus status, EngineConfig config) {
    return Column(
      children: [
        StaggerEntry(index: 0, child: _statusCard(status)),
        const SizedBox(height: kGap * 2),
        if (engine.lastError != null) ...[
          StaggerEntry(
            index: 1,
            child: ErrorBanner(
              message: engine.lastError!,
              onRetry: () {
                engine.clearError();
                engine.start();
              },
              onDismiss: engine.clearError,
            ),
          ),
          const SizedBox(height: kGap * 2),
        ],
        StaggerEntry(index: 2, child: _actionCard(status)),
        const SizedBox(height: kGap * 2),
        StaggerEntry(index: 3, child: _modeCard(config, status)),
      ],
    );
  }

  Widget _statusCard(EngineStatus s) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final String headline = s.running
        ? (s.mode == 'hosted' ? '扩展屏投屏中' : '镜像投屏中')
        : (s.starting ? '正在启动…' : '准备就绪');
    final String detail = !s.glasses
        ? '未检测到外接显示器'
        : s.running
            ? (s.hosted
                ? '目标应用在眼镜屏上独立运行，手机当触控板'
                : '眼镜屏正在显示手机画面')
            : '眼镜已连接：${s.displayName}';

    return SectionCard(
      title: '状态',
      trailing: statusPillFor(s),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (!s.glasses)
            EmptyState(
              compact: true,
              icon: Icons.tv_off_outlined,
              title: '未检测到眼镜显示器',
              message: '请确认手机支持 USB-C DP 输出，并已插好雷鸟 Air 1s。\n插好后系统会出现“外接显示器”。',
              actionLabel: '重新检测',
              onAction: engine.refresh,
            )
          else ...[
            Row(
              children: [
                _StatusGlyph(running: s.running, starting: s.starting),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      AnimatedSwitcher(
                        duration: const Duration(milliseconds: 220),
                        child: Text(
                          headline,
                          key: ValueKey(headline),
                          style: theme.textTheme.titleLarge,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        detail,
                        style: theme.textTheme.bodySmall
                            ?.copyWith(color: scheme.onSurfaceVariant),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 14),
            _SpacePreview(pose: s.pose, running: s.running),
            const SizedBox(height: 12),
            MetricRow(items: [
              Metric(
                label: '帧',
                value: '${s.frames}',
                icon: Icons.movie_outlined,
                highlight: s.running && s.frames > 0,
              ),
              Metric(
                label: '姿态',
                value: s.pose.tracking
                    ? '${s.pose.hz.toStringAsFixed(0)} Hz'
                    : '等待中',
                icon: Icons.sensors_outlined,
              ),
              Metric(
                label: '捕获',
                value: s.capture.w > 0
                    ? '${s.capture.w}×${s.capture.h}'
                    : '—',
                icon: Icons.aspect_ratio_outlined,
              ),
            ]),
          ],
        ],
      ),
    );
  }

  Widget statusPillFor(EngineStatus s) {
    if (engine.lastError != null) {
      return const StatusPill(text: '需处理', tone: PillTone.error);
    }
    if (s.running) return const StatusPill(text: '运行中', tone: PillTone.active);
    if (s.starting) return const StatusPill(text: '启动中', tone: PillTone.pending);
    return const StatusPill(text: '待机', tone: PillTone.idle);
  }

  Widget _actionCard(EngineStatus s) {
    final busy = s.starting;
    final running = s.running;
    return SectionCard(
      title: '开始 / 停止',
      subtitle: '首次会依次请求 USB 与录屏授权；开启无障碍辅助后会自动确认',
      child: Column(
        children: [
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 220),
            child: SizedBox(
              key: ValueKey('cta-$running-$busy'),
              width: double.infinity,
              child: busy
                  ? FilledButton.icon(
                      onPressed: null,
                      icon: const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      ),
                      label: const Text('启动中…'),
                    )
                  : running
                      ? FilledButton.icon(
                          onPressed: () => engine.stop(),
                          style: FilledButton.styleFrom(
                            backgroundColor: Theme.of(context).colorScheme.error,
                            foregroundColor: Theme.of(context).colorScheme.onError,
                          ),
                          icon: const Icon(Icons.stop_circle_outlined),
                          label: const Text('停止悬停'),
                        )
                      : FilledButton.icon(
                          onPressed: () => engine.start(),
                          icon: const Icon(Icons.play_circle_outline),
                          label: const Text('开始悬停'),
                        ),
            ),
          ),
          if (running) ...[
            const SizedBox(height: kGap),
            Row(
              children: [
                Expanded(
                  child: FilledButton.tonalIcon(
                    onPressed: engine.recenter,
                    icon: const Icon(Icons.center_focus_strong_outlined),
                    label: const Text('画面复位'),
                  ),
                ),
                // 触控板只属于扩展屏模式：镜像整屏时手机不参与交互，直接不显示
                if (s.hosted) ...[
                  const SizedBox(width: kGap),
                  Expanded(
                    child: FilledButton.tonalIcon(
                      onPressed: _openPad,
                      icon: const Icon(Icons.touch_app_outlined),
                      label: const Text('触控板'),
                    ),
                  ),
                ],
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _modeCard(EngineConfig c, EngineStatus s) {
    final canEdit = !s.running && !s.starting;
    return SectionCard(
      title: '投屏方式',
      subtitle: canEdit ? null : '运行中不可切换，先停止悬停',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SegmentedButton<String>(
            segments: const [
              ButtonSegment(
                value: 'mirror',
                icon: Icon(Icons.smartphone_outlined),
                label: Text('镜像整屏'),
              ),
              ButtonSegment(
                value: 'hosted',
                icon: Icon(Icons.open_in_new_outlined),
                label: Text('扩展屏'),
              ),
            ],
            selected: {c.mode},
            onSelectionChanged:
                canEdit ? (v) => engine.setMode(v.first) : null,
          ),
          AnimatedSize(
            duration: const Duration(milliseconds: 240),
            curve: Curves.easeOutCubic,
            alignment: Alignment.topCenter,
            child: !c.hosted
                ? const SizedBox(width: double.infinity)
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const SizedBox(height: kGap + 4),
                      Material(
                        color: Theme.of(context).colorScheme.surfaceContainer,
                        borderRadius: BorderRadius.circular(kControlRadius),
                        clipBehavior: Clip.antiAlias,
                        child: ListTile(
                          enabled: canEdit,
                          leading: const Icon(Icons.apps_outlined),
                          title: Text(
                            c.label ?? '选择要投屏的应用',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          subtitle: Text(
                            c.component == null
                                ? '把任意应用启动到眼镜屏'
                                : '点按更换目标应用',
                          ),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: canEdit ? () => _pickApp() : null,
                        ),
                      ),
                      const SizedBox(height: 4),
                      ToggleRow(
                        title: '兼容模式',
                        subtitle: '改用 MediaProjection 公共屏（需要录屏授权）',
                        value: c.compat,
                        enabled: canEdit,
                        onChanged: (v) async {
                          await engine.setCompat(v);
                        },
                      ),
                      ToggleRow(
                        title: '受信屏',
                        subtitle: 'Shizuku UserService 建屏，兼容“只认默认屏”的应用',
                        value: c.trusted,
                        enabled: canEdit && !c.compat,
                        onChanged: (v) async {
                          await engine.setTrusted(v);
                        },
                      ),
                    ],
                  ),
          ),
        ],
      ),
    );
  }

  // ---------------------------------------------------------------- 右列

  Widget _rightColumn(EngineStatus s, EngineConfig c) {
    return Column(
      children: [
        StaggerEntry(index: 4, child: _paramsCard(c)),
        const SizedBox(height: kGap * 2),
        StaggerEntry(index: 5, child: _poseCard(c)),
        const SizedBox(height: kGap * 2),
        StaggerEntry(index: 6, child: _assistCard(s)),
        const SizedBox(height: kGap * 2),
        StaggerEntry(index: 7, child: _diagnosticsCard(s, c)),
      ],
    );
  }

  Widget _paramsCard(EngineConfig c) {
    return SectionCard(
      title: '画面参数',
      subtitle: '改完即时生效，自动保存',
      child: Column(
        children: [
          SliderRow(
            label: '尺寸',
            valueLabel: '${(0.3 + c.size / 100).toStringAsFixed(2)}×',
            value: c.size.toDouble(),
            min: 0,
            max: 100,
            divisions: 100,
            onChanged: (v) => engine.setParams(size: v.round()),
          ),
          SliderRow(
            label: '虚拟距离',
            valueLabel: '${(1 + c.distance / 10).toStringAsFixed(1)} m',
            value: c.distance.toDouble(),
            min: 0,
            max: 100,
            divisions: 100,
            onChanged: (v) => engine.setParams(distance: v.round()),
          ),
          SliderRow(
            label: '眼镜垂直视场角',
            valueLabel: '${15 + c.fov}°',
            value: c.fov.toDouble(),
            min: 0,
            max: 30,
            divisions: 30,
            onChanged: (v) => engine.setParams(fov: v.round()),
          ),
          const SizedBox(height: 4),
          Align(
            alignment: Alignment.centerLeft,
            child: Text('捕获清晰度',
                style: Theme.of(context).textTheme.bodyMedium),
          ),
          const SizedBox(height: kGap),
          SegmentedButton<int>(
            showSelectedIcon: false,
            segments: const [
              ButtonSegment(value: 0, label: Text('1920')),
              ButtonSegment(value: 1, label: Text('1600')),
              ButtonSegment(value: 2, label: Text('1280')),
            ],
            selected: {c.resolution},
            onSelectionChanged: (v) => engine.setResolution(v.first),
          ),
        ],
      ),
    );
  }

  Widget _poseCard(EngineConfig c) {
    return SectionCard(
      title: '姿态',
      subtitle: '方向已按 Air 1s 实测预置；换机型时才需要逐轴再反一次',
      child: Column(
        children: [
          ToggleRow(
            title: '静止时冻结',
            subtitle: '抑制静止时的画面漂移',
            value: c.freeze,
            onChanged: (v) => engine.setPose(freeze: v),
          ),
          const Divider(height: 16),
          ToggleRow(
            title: '前后取反',
            value: c.invertForward,
            onChanged: (v) => engine.setPose(invertForward: v),
          ),
          ToggleRow(
            title: '左右取反',
            value: c.invertYaw,
            onChanged: (v) => engine.setPose(invertYaw: v),
          ),
          ToggleRow(
            title: '上下取反',
            value: c.invertPitch,
            onChanged: (v) => engine.setPose(invertPitch: v),
          ),
          ToggleRow(
            title: '倾斜取反',
            value: c.invertRoll,
            onChanged: (v) => engine.setPose(invertRoll: v),
          ),
        ],
      ),
    );
  }

  Widget _assistCard(EngineStatus s) {
    return SectionCard(
      title: '辅助能力',
      subtitle: '没有 Shizuku 也能用：无障碍负责自动授权与触摸注入',
      child: Column(
        children: [
          _AssistTile(
            icon: Icons.accessibility_new_outlined,
            title: '无障碍辅助',
            subtitle: s.accessibilityEnabled
                ? (s.canInject ? '已开启 · 触摸注入可用' : '已开启')
                : '未开启 · 授权弹窗需手动确认',
            ok: s.accessibilityEnabled,
            actionLabel: '去开启',
            onAction: engine.openAccessibilitySettings,
          ),
          const Divider(height: 16),
          _AssistTile(
            icon: Icons.terminal_outlined,
            title: 'Shizuku',
            subtitle: s.shizukuReady
                ? '已授权 · 可启动应用到扩展屏并注入输入'
                : (s.shizukuInstalled ? '已安装 · 未授权' : '未安装'),
            ok: s.shizukuReady,
            actionLabel: s.shizukuReady ? null : '去授权',
            onAction: engine.requestShizuku,
          ),
        ],
      ),
    );
  }

  Widget _diagnosticsCard(EngineStatus s, EngineConfig c) {
    final theme = Theme.of(context);
    final rows = <(String, String)>[
      ('显示器', s.displayName),
      ('显示 id', '${s.displayId}'),
      ('画面宿主', s.stageReady
          ? '副屏任务'
          : (s.stageLaunched ? '副屏启动中' : (s.presentation ? 'Presentation' : '—'))),
      ('捕获', s.capture.w > 0
          ? '期望 ${s.capture.wantW}×${s.capture.wantH} · 实际 ${s.capture.w}×${s.capture.h}'
          : '—'),
      ('IMU', s.imu.open
          ? '${s.imu.frames} 帧 · ${s.imu.sendMode}'
          : '未打开'),
      ('画面比例', '${s.aspect.toStringAsFixed(2)}:1'),
      ('估计零偏', s.pose.tracking
          ? '${s.pose.biasDps.toStringAsFixed(2)} °/s（IMU 自身偏移，已被减掉）'
          : '—'),
      ('静止残差', s.pose.tracking
          ? '${s.pose.residualDps.toStringAsFixed(2)} °/s（越小越不漂）'
          : '—'),
      ('引擎版本', c.version),
    ];
    return SectionCard(
      title: '诊断',
      subtitle: '更多日志：adb logcat -s GlassEngine',
      trailing: TextButton(
        onPressed: engine.recalibrate,
        style: TextButton.styleFrom(minimumSize: const Size(72, 40)),
        child: const Text('重新校准'),
      ),
      child: Column(
        children: [
          for (final r in rows)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 5),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 76,
                    child: Text(
                      r.$1,
                      style: theme.textTheme.bodySmall
                          ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                    ),
                  ),
                  Expanded(
                    child: Text(r.$2, style: theme.textTheme.bodySmall),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  // ---------------------------------------------------------------- 应用选择

  Future<void> _pickApp() async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (sheetContext) => _AppPickerSheet(
        loader: engine.listApps,
        onPicked: (app) => engine.setHostedApp(app),
      ),
    );
  }
}

/// 启动等待态：引擎第一次回配置之前显示。
class _BootView extends StatelessWidget {
  const _BootView();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const SizedBox(
            width: 28,
            height: 28,
            child: CircularProgressIndicator(strokeWidth: 2.5),
          ),
          const SizedBox(height: 14),
          Text('正在连接原生引擎…',
              style: theme.textTheme.bodyMedium
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
        ],
      ),
    );
  }
}

/// 状态卡左侧的呼吸圆点：运行中脉动，启动中转圈。
class _StatusGlyph extends StatefulWidget {
  const _StatusGlyph({required this.running, required this.starting});

  final bool running;
  final bool starting;

  @override
  State<_StatusGlyph> createState() => _StatusGlyphState();
}

class _StatusGlyphState extends State<_StatusGlyph>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1400),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final color = widget.running
        ? scheme.primary
        : (widget.starting ? scheme.tertiary : scheme.outline);
    return AnimatedBuilder(
      animation: _c,
      builder: (context, _) {
        final t = widget.running ? 0.5 + _c.value * 0.5 : 1.0;
        return Container(
          width: 46,
          height: 46,
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.12 * t + 0.06),
            shape: BoxShape.circle,
          ),
          child: Icon(
            widget.running
                ? Icons.visibility_outlined
                : (widget.starting
                    ? Icons.hourglass_top_rounded
                    : Icons.visibility_off_outlined),
            color: color,
            size: 24,
          ),
        );
      },
    );
  }
}

/// 空间预览：一块"屏幕"按 yaw/pitch 平移、按 roll 轻微旋转，直观表达 3DoF 悬停。
class _SpacePreview extends StatelessWidget {
  const _SpacePreview({required this.pose, required this.running});

  final PoseStatus pose;
  final bool running;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final yaw = pose.yaw.clamp(-30.0, 30.0);
    final pitch = pose.pitch.clamp(-25.0, 25.0);
    final roll = pose.roll.clamp(-40.0, 40.0);

    return LayoutBuilder(
      builder: (context, box) {
        final w = box.maxWidth;
        const h = 132.0;
        final dx = -(yaw / 30) * (w * 0.16);
        final dy = (pitch / 25) * (h * 0.16);
        final matrix = Matrix4.identity()
          ..translateByDouble(dx, dy, 0, 1)
          ..rotateZ(roll * math.pi / 180 * 0.25);

        return Container(
          height: h,
          width: w,
          decoration: BoxDecoration(
            color: scheme.surfaceContainer,
            borderRadius: BorderRadius.circular(kControlRadius + 2),
          ),
          clipBehavior: Clip.antiAlias,
          child: Stack(
            alignment: Alignment.center,
            children: [
              // 地平线参考
              Positioned(
                left: 0,
                right: 0,
                top: h / 2,
                child: Container(
                  height: 0.6,
                  color: scheme.outlineVariant.withValues(alpha: 0.9),
                ),
              ),
              AnimatedContainer(
                duration: const Duration(milliseconds: 220),
                curve: Curves.easeOutCubic,
                transform: matrix,
                transformAlignment: Alignment.center,
                width: w * 0.46,
                height: h * 0.52,
                decoration: BoxDecoration(
                  color: running
                      ? scheme.primary.withValues(alpha: 0.16)
                      : scheme.surfaceContainerHigh,
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(
                    color: running
                        ? scheme.primary.withValues(alpha: 0.55)
                        : scheme.outlineVariant,
                    width: 1.2,
                  ),
                ),
                child: Center(
                  child: Icon(
                    running ? Icons.cast_connected : Icons.cast_outlined,
                    size: 20,
                    color: running ? scheme.primary : scheme.onSurfaceVariant,
                  ),
                ),
              ),
              Positioned(
                left: 10,
                bottom: 8,
                child: Text(
                  pose.tracking
                      ? 'yaw ${pose.yaw.toStringAsFixed(1)}°  pitch ${pose.pitch.toStringAsFixed(1)}°'
                          '${pose.frozen ? '  ·  静止冻结' : ''}'
                      : '等待 IMU…',
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: scheme.onSurfaceVariant,
                    fontFeatures: const [FontFeature.tabularFigures()],
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _AssistTile extends StatelessWidget {
  const _AssistTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.ok,
    this.actionLabel,
    this.onAction,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final bool ok;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Row(
      children: [
        Container(
          width: 36,
          height: 36,
          decoration: BoxDecoration(
            color: scheme.surfaceContainerHigh,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Icon(icon, size: 18, color: scheme.onSurfaceVariant),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text(title, style: theme.textTheme.bodyMedium),
                  const SizedBox(width: 6),
                  Icon(
                    ok ? Icons.check_circle : Icons.radio_button_unchecked,
                    size: 14,
                    color: ok ? scheme.primary : scheme.outline,
                  ),
                ],
              ),
              const SizedBox(height: 1),
              Text(
                subtitle,
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: scheme.onSurfaceVariant),
              ),
            ],
          ),
        ),
        if (actionLabel != null)
          TextButton(
            onPressed: onAction,
            style: TextButton.styleFrom(minimumSize: const Size(64, 44)),
            child: Text(actionLabel!),
          ),
      ],
    );
  }
}

/// 应用选择面板：加载 / 空 / 错误+重试 / 搜索过滤，全部状态齐备。
class _AppPickerSheet extends StatefulWidget {
  const _AppPickerSheet({required this.loader, required this.onPicked});

  final Future<List<AppEntry>> Function() loader;
  final ValueChanged<AppEntry> onPicked;

  @override
  State<_AppPickerSheet> createState() => _AppPickerSheetState();
}

class _AppPickerSheetState extends State<_AppPickerSheet> {
  late Future<List<AppEntry>> _future = widget.loader();
  String _query = '';

  void _retry() {
    setState(() => _future = widget.loader());
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final height = MediaQuery.sizeOf(context).height * 0.72;
    return SizedBox(
      height: height,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, kGap),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('选择要投到扩展屏的应用',
                    style: theme.textTheme.titleLarge),
                const SizedBox(height: 2),
                Text('只列出有启动入口的应用',
                    style: theme.textTheme.bodySmall
                        ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                const SizedBox(height: 12),
                TextField(
                  decoration: InputDecoration(
                    hintText: '搜索应用',
                    prefixIcon: const Icon(Icons.search, size: 20),
                    isDense: true,
                    filled: true,
                    fillColor: theme.colorScheme.surfaceContainer,
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(kControlRadius),
                      borderSide: BorderSide.none,
                    ),
                  ),
                  onChanged: (v) => setState(() => _query = v.trim()),
                ),
              ],
            ),
          ),
          Expanded(
            child: FutureBuilder<List<AppEntry>>(
              future: _future,
              builder: (context, snap) {
                if (snap.connectionState != ConnectionState.done) {
                  return const LoadingBlock(text: '正在读取应用列表…');
                }
                if (snap.hasError) {
                  return Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: EmptyState(
                        icon: Icons.error_outline,
                        title: '读取失败',
                        message: '${snap.error}',
                        actionLabel: '重试',
                        onAction: _retry,
                      ),
                    ),
                  );
                }
                final all = snap.data ?? const <AppEntry>[];
                final list = _query.isEmpty
                    ? all
                    : all
                        .where((a) =>
                            a.label.toLowerCase().contains(_query.toLowerCase()))
                        .toList();
                if (all.isEmpty) {
                  return const Center(
                    child: Padding(
                      padding: EdgeInsets.all(24),
                      child: EmptyState(
                        icon: Icons.apps_outlined,
                        title: '没有可投屏的应用',
                        message: '设备上没有带启动入口的应用。',
                      ),
                    ),
                  );
                }
                if (list.isEmpty) {
                  return const Center(
                    child: Padding(
                      padding: EdgeInsets.all(24),
                      child: EmptyState(
                        icon: Icons.search_off,
                        title: '没有匹配的应用',
                        message: '换个关键词试试。',
                      ),
                    ),
                  );
                }
                return ListView.separated(
                  padding: const EdgeInsets.fromLTRB(8, 0, 8, 24),
                  itemCount: list.length,
                  separatorBuilder: (_, _) =>
                      const Divider(indent: 56, endIndent: 8),
                  itemBuilder: (context, i) {
                    final app = list[i];
                    return ListTile(
                      leading: CircleAvatar(
                        radius: 18,
                        backgroundColor: theme.colorScheme.surfaceContainerHigh,
                        child: Text(
                          app.label.isEmpty ? '?' : app.label.substring(0, 1),
                          style: theme.textTheme.labelLarge,
                        ),
                      ),
                      title: Text(app.label,
                          maxLines: 1, overflow: TextOverflow.ellipsis),
                      subtitle: Text(
                        app.component.split('/').first,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant),
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () {
                        Navigator.of(context).pop();
                        widget.onPicked(app);
                      },
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
