import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'engine/engine_client.dart';
import 'pages/home_page.dart';
import 'theme/app_theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    systemNavigationBarColor: Colors.transparent,
  ));
  runApp(const Air3DofApp());
}

class Air3DofApp extends StatefulWidget {
  const Air3DofApp({super.key});

  @override
  State<Air3DofApp> createState() => _Air3DofAppState();
}

class _Air3DofAppState extends State<Air3DofApp> {
  final EngineModel _engine = EngineModel();

  @override
  void dispose() {
    _engine.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Air3DoF',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.system,
      theme: buildLightTheme(),
      darkTheme: buildDarkTheme(),
      // 系统字号放大到 1.4 倍为止：再大滑块与列表会溢出，是版式与无障碍的折中
      builder: (context, child) => MediaQuery.withClampedTextScaling(
        maxScaleFactor: 1.4,
        child: child ?? const SizedBox.shrink(),
      ),
      home: HomePage(engine: _engine),
    );
  }
}
