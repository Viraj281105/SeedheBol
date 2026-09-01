import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'home_screen.dart';
import 'theme.dart';
import 'widgets.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.dark,
  ));
  runApp(const BoliApp());
}

class BoliApp extends StatelessWidget {
  const BoliApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Boli',
        debugShowCheckedModeBanner: false,
        theme: Desi.theme,
        home: const SplashScreen(),
      );
}

/// Brief animated open. Also covers the ~1.5s the Kotlin side spends building
/// both ONNX sessions, so the first speaking exercise never waits on a cold
/// start.
class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key});

  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen> with SingleTickerProviderStateMixin {
  late final AnimationController _c =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 1700))..forward();

  @override
  void initState() {
    super.initState();
    Future.delayed(const Duration(milliseconds: 1900), () {
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        PageRouteBuilder(
          transitionDuration: const Duration(milliseconds: 520),
          pageBuilder: (_, __, ___) => const HomeScreen(),
          transitionsBuilder: (_, anim, __, child) => FadeTransition(opacity: anim, child: child),
        ),
      );
    });
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final logo = CurvedAnimation(parent: _c, curve: const Interval(0, .55, curve: Curves.easeOutBack));
    final text = CurvedAnimation(parent: _c, curve: const Interval(.35, .85, curve: Curves.easeOut));
    final tag = CurvedAnimation(parent: _c, curve: const Interval(.55, 1, curve: Curves.easeOut));

    return Scaffold(
      backgroundColor: Desi.indigo,
      body: Stack(
        children: [
          const Positioned.fill(
            child: RangoliBackdrop(color: Desi.marigold, opacity: .16),
          ),
          Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                ScaleTransition(
                  scale: logo,
                  child: Container(
                    width: 108,
                    height: 108,
                    decoration: BoxDecoration(
                      gradient: Desi.gold,
                      borderRadius: BorderRadius.circular(30),
                      boxShadow: [
                        BoxShadow(
                            color: Desi.marigold.withValues(alpha: .45),
                            blurRadius: 40,
                            spreadRadius: 4),
                      ],
                    ),
                    child: const Icon(Icons.graphic_eq_rounded, color: Colors.white, size: 54),
                  ),
                ),
                const SizedBox(height: 26),
                FadeTransition(
                  opacity: text,
                  child: const Text('बोली',
                      style: TextStyle(
                          fontSize: 46, fontWeight: FontWeight.w800, color: Colors.white, height: 1)),
                ),
                const SizedBox(height: 10),
                FadeTransition(
                  opacity: tag,
                  child: Column(
                    children: [
                      Text('Marathi for work',
                          style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w600,
                              color: Colors.white.withValues(alpha: .78))),
                      const SizedBox(height: 18),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
                        decoration: BoxDecoration(
                          color: Colors.white.withValues(alpha: .12),
                          borderRadius: BorderRadius.circular(20),
                        ),
                        child: Row(mainAxisSize: MainAxisSize.min, children: [
                          Icon(Icons.airplanemode_active_rounded,
                              size: 15, color: Colors.white.withValues(alpha: .9)),
                          const SizedBox(width: 6),
                          Text('100% on-device',
                              style: TextStyle(
                                  fontSize: 12,
                                  fontWeight: FontWeight.w800,
                                  letterSpacing: .5,
                                  color: Colors.white.withValues(alpha: .9))),
                        ]),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
