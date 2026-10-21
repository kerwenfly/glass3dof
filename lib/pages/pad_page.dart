import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../engine/engine_client.dart';
import '../theme/app_theme.dart';

/// 手机端触控板（替代原生的 PadActivity）。
///
/// 语义与原生一致：手指拖动 → 光标按位移移动；轻点 → 在当前光标位置点击；
/// 按住 500ms → 长按；双指滑动 → 四向滚动。注入通道由引擎决定
/// （Shizuku 优先，无 Shizuku 时走无障碍 dispatchGesture）。
class PadPage extends StatefulWidget {
  const PadPage({super.key, required this.engine});

  final EngineModel engine;

  @override
  State<PadPage> createState() => _PadPageState();
}

class _PadPageState extends State<PadPage> {
  static const double _gain = 1.6;
  static const int _tapSlopDp = 10;
  static const int _scrollStepDp = 46;
  static const Duration _longPress = Duration(milliseconds: 500);
  static const Duration _scrollInterval = Duration(milliseconds: 120);

  final Map<int, Offset> _pointers = {};
  Offset _lastCentroid = Offset.zero;
  Offset _downAt = Offset.zero;
  bool _moved = false;
  bool _scrollMode = false;
  int _scrollAxis = 0;
  double _scrollAcc = 0;
  DateTime _lastScrollAt = DateTime.fromMillisecondsSinceEpoch(0);
  Timer? _longPressTimer;
  bool _longPressFired = false;

  double _u = 0.5;
  double _v = 0.5;
  bool _blackout = false;

  EngineModel get engine => widget.engine;

  @override
  void initState() {
    super.initState();
    _u = 0.5;
    _v = 0.5;
    engine.addListener(_repaint);
    engine.setCursor(u: _u, v: _v, visible: true);
  }

  @override
  void dispose() {
    engine.removeListener(_repaint);
    _longPressTimer?.cancel();
    engine.setCursor(visible: false);
    // 黑屏时进过沉浸模式，离开页面必须还原，否则状态栏一直不回来
    if (_blackout) {
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    }
    super.dispose();
  }

  void _repaint() {
    if (mounted) setState(() {});
  }

  /// 黑屏：眼镜里连光标一起隐藏，手机整屏纯黑（连状态栏都不留），双击恢复。
  void _enterBlackout() {
    if (_blackout) return;
    setState(() => _blackout = true);
    engine.setCursor(visible: false);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  }

  void _exitBlackout() {
    if (!_blackout) return;
    setState(() => _blackout = false);
    engine.setCursor(visible: true);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  }

  // Flutter 的逻辑像素本身就是 dp，这里的阈值直接用数值即可

  // ------------------------------------------------------------ 手势

  void _onDown(PointerDownEvent e) {
    _pointers[e.pointer] = e.localPosition;
    if (_pointers.length == 1) {
      _downAt = e.localPosition;
      _moved = false;
      _scrollMode = false;
      _scrollAxis = 0;
      _scrollAcc = 0;
      _lastCentroid = e.localPosition;
      _longPressFired = false;
      _longPressTimer?.cancel();
      _longPressTimer = Timer(_longPress, () {
        if (!_moved && _pointers.length == 1) {
          _longPressFired = true;
          HapticFeedback.mediumImpact();
          engine.tap(longPress: true);
        }
      });
    } else if (_pointers.length == 2) {
      _longPressTimer?.cancel();
      _scrollMode = true;
      _scrollAxis = 0;
      _scrollAcc = 0;
      _moved = true;
      _lastCentroid = _centroid();
    }
  }

  Offset _centroid() {
    if (_pointers.isEmpty) return Offset.zero;
    var sum = Offset.zero;
    for (final p in _pointers.values) {
      sum += p;
    }
    return sum / _pointers.length.toDouble();
  }

  void _onMove(PointerMoveEvent e) {
    if (!_pointers.containsKey(e.pointer)) return;
    _pointers[e.pointer] = e.localPosition;

    if (_scrollMode && _pointers.length >= 2) {
      final c = _centroid();
      final d = c - _lastCentroid;
      _lastCentroid = c;
      if (_scrollAxis == 0) {
        if (d.dx.abs() + d.dy.abs() < 6) return;
        _scrollAxis = d.dx.abs() > d.dy.abs() ? 1 : 2;
      }
      _scrollAcc += _scrollAxis == 1 ? d.dx : d.dy;
      const step = _scrollStepDp * 1.0;
      while (_scrollAcc.abs() >= step) {
        final dir = _scrollAcc > 0 ? 1 : -1;
        _scrollAcc -= dir * step;
        _fireScroll(_scrollAxis, dir);
      }
      return;
    }

    if (_pointers.length != 1) return;

    final last = _lastCentroid;
    _lastCentroid = e.localPosition;
    final delta = e.localPosition - last;
    final box = context.size;
    if (box == null || box.width <= 0 || box.height <= 0) return;

    setState(() {
      _u = (_u + delta.dx * _gain / box.width).clamp(0.0, 1.0);
      _v = (_v + delta.dy * _gain / box.height).clamp(0.0, 1.0);
    });
    engine.setCursor(u: _u, v: _v);

    if ((e.localPosition - _downAt).distance > _tapSlopDp) {
      _moved = true;
      _longPressTimer?.cancel();
    }
  }

  void _onUp(PointerUpEvent e) {
    _pointers.remove(e.pointer);
    if (_pointers.isEmpty) {
      _longPressTimer?.cancel();
      if (!_moved && !_longPressFired) {
        HapticFeedback.selectionClick();
        engine.tap();
      }
      _scrollMode = false;
      _moved = false;
    } else if (_pointers.length == 1) {
      _lastCentroid = _pointers.values.first;
      _scrollMode = false;
    }
  }

  void _onCancel(PointerCancelEvent e) {
    _pointers.remove(e.pointer);
    _longPressTimer?.cancel();
    if (_pointers.isEmpty) {
      _scrollMode = false;
      _moved = false;
    }
  }

  void _fireScroll(int axis, int dir) {
    final now = DateTime.now();
    if (now.difference(_lastScrollAt) < _scrollInterval) return;
    _lastScrollAt = now;
    HapticFeedback.selectionClick();
    engine.scroll(axis: axis, dir: dir);
  }

  // ------------------------------------------------------------ 视图

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final running = engine.status.running;
    final canInject = engine.status.canInjectInput;

    return Scaffold(
      backgroundColor: _blackout ? Colors.black : scheme.surface,
      // 黑幕放在 SafeArea 外层：连状态栏/导航栏区域一起盖住，才是"手机完全黑屏"
      body: Stack(
        children: [
          SafeArea(
            child: Column(
              children: [
                _topBar(context, running),
                if (!canInject)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 12, vertical: 10),
                      decoration: BoxDecoration(
                        color: scheme.tertiaryContainer,
                        borderRadius: BorderRadius.circular(kControlRadius),
                      ),
                      child: Row(
                        children: [
                          Icon(Icons.info_outline,
                              size: 18, color: scheme.onTertiaryContainer),
                          const SizedBox(width: kGap),
                          Expanded(
                            child: Text(
                              '当前仅显示：开启 Shizuku 或无障碍辅助后，触控板才能操作眼镜画面',
                              style: theme.textTheme.bodySmall?.copyWith(
                                  color: scheme.onTertiaryContainer),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                Expanded(
                  child: Listener(
                    behavior: HitTestBehavior.opaque,
                    onPointerDown: _onDown,
                    onPointerMove: _onMove,
                    onPointerUp: _onUp,
                    onPointerCancel: _onCancel,
                    child: _padSurface(context),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                  child: Text(
                    '拖动移动光标　·　轻点＝点击　·　按住＝长按　·　双指＝滚动',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodySmall
                        ?.copyWith(color: scheme.onSurfaceVariant),
                  ),
                ),
              ],
            ),
          ),
          // 纯黑遮罩：无任何文字/图标；双击恢复（单击不穿透到触控板）
          Positioned.fill(
            child: IgnorePointer(
              ignoring: !_blackout,
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onDoubleTap: _exitBlackout,
                child: AnimatedOpacity(
                  opacity: _blackout ? 1 : 0,
                  duration: const Duration(milliseconds: 180),
                  curve: Curves.easeOut,
                  child: const ColoredBox(color: Colors.black),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _topBar(BuildContext context, bool running) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 8, 8, 8),
      child: Row(
        children: [
          IconButton(
            onPressed: () => Navigator.of(context).pop(),
            icon: const Icon(Icons.keyboard_arrow_down),
            tooltip: '收起（回设置）',
          ),
          const SizedBox(width: 4),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('触控板', style: theme.textTheme.titleMedium),
                Text(
                  running
                      ? '眼镜屏 display ${engine.status.displayId}'
                      : '会话未运行',
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                ),
              ],
            ),
          ),
          IconButton.filledTonal(
            onPressed: engine.recenter,
            icon: const Icon(Icons.center_focus_strong_outlined),
            tooltip: '复位视角',
          ),
          const SizedBox(width: 6),
          IconButton.filledTonal(
            onPressed: () => engine.key(4),
            icon: const Icon(Icons.arrow_back),
            tooltip: '返回键',
          ),
          const SizedBox(width: 6),
          IconButton.filledTonal(
            onPressed: _enterBlackout,
            icon: const Icon(Icons.dark_mode_outlined),
            tooltip: '黑屏',
          ),
          const SizedBox(width: 6),
          IconButton.filled(
            onPressed: () {
              engine.stop();
              Navigator.of(context).pop();
            },
            icon: const Icon(Icons.stop_circle_outlined),
            tooltip: '停止并退出',
          ),
        ],
      ),
    );
  }

  Widget _padSurface(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return LayoutBuilder(
      builder: (context, box) {
        final w = box.maxWidth;
        final h = box.maxHeight;
        return Container(
          margin: const EdgeInsets.symmetric(horizontal: 16),
          decoration: BoxDecoration(
            color: scheme.surfaceContainerLow,
            borderRadius: BorderRadius.circular(kCardRadius),
            border: Border.all(color: scheme.outlineVariant.withValues(alpha: 0.7)),
          ),
          child: Stack(
            children: [
              // 极简网格，暗示这是"输入面"而不是空白
              Positioned.fill(
                child: CustomPaint(
                  painter: _GridPainter(
                    color: scheme.outlineVariant.withValues(alpha: 0.45),
                  ),
                ),
              ),
              AnimatedPositioned(
                duration: const Duration(milliseconds: 90),
                curve: Curves.easeOut,
                left: (_u * w - 16).clamp(8.0, (w - 40).clamp(8.0, w)),
                top: (_v * h - 16).clamp(8.0, (h - 40).clamp(8.0, h)),
                child: Container(
                  width: 32,
                  height: 32,
                  decoration: BoxDecoration(
                    color: scheme.primary.withValues(alpha: 0.16),
                    shape: BoxShape.circle,
                    border: Border.all(
                        color: scheme.primary.withValues(alpha: 0.65)),
                  ),
                  child: Icon(Icons.circle, size: 8, color: scheme.primary),
                ),
              ),
              const Positioned(
                left: 16,
                top: 14,
                child: _PadHint(),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _PadHint extends StatelessWidget {
  const _PadHint();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Text(
      'TOUCHPAD',
      style: theme.textTheme.labelSmall?.copyWith(
        color: theme.colorScheme.onSurfaceVariant.withValues(alpha: 0.6),
        letterSpacing: 2.4,
        fontWeight: FontWeight.w600,
      ),
    );
  }
}

class _GridPainter extends CustomPainter {
  _GridPainter({required this.color});

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = 0.6;
    const step = 48.0;
    for (double x = step; x < size.width; x += step) {
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), paint);
    }
    for (double y = step; y < size.height; y += step) {
      canvas.drawLine(Offset(0, y), Offset(size.width, y), paint);
    }
  }

  @override
  bool shouldRepaint(covariant _GridPainter old) => old.color != color;
}
