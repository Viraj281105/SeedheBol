import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'onboarding.dart';
import 'theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.dark,
    ),
  );
  runApp(const SeedheBolApp());
}

class SeedheBolApp extends StatelessWidget {
  const SeedheBolApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'SeedheBol',
    debugShowCheckedModeBanner: false,
    theme: Boli.theme,
    home: const Splash(),
  );
}

typedef BoliApp = SeedheBolApp;

/// Short open. It also covers the ~1.5s Kotlin spends building both ONNX
/// sessions, so the first speaking exercise never pays for a cold start.
class Splash extends StatefulWidget {
  const Splash({super.key});

  @override
  State<Splash> createState() => _SplashState();
}

class _SplashState extends State<Splash> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1500),
  )..forward();

  @override
  void initState() {
    super.initState();
    Future.delayed(const Duration(milliseconds: 1750), () {
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        PageRouteBuilder(
          transitionDuration: const Duration(milliseconds: 480),
          pageBuilder: (_, __, ___) => const Onboarding(),
          transitionsBuilder: (_, a, __, c) =>
              FadeTransition(opacity: a, child: c),
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
    final mark = CurvedAnimation(
      parent: _c,
      curve: const Interval(0, .6, curve: Curves.easeOutCubic),
    );
    final word = CurvedAnimation(
      parent: _c,
      curve: const Interval(.3, .8, curve: Curves.easeOut),
    );
    final tag = CurvedAnimation(
      parent: _c,
      curve: const Interval(.55, 1, curve: Curves.easeOut),
    );

    return Scaffold(
      backgroundColor: Boli.ink,
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            FadeTransition(
              opacity: mark,
              child: ScaleTransition(
                scale: Tween(begin: .85, end: 1.0).animate(mark),
                child: SizedBox(
                  width: 210,
                  child: HandloomBorder(color: Boli.marigold, height: 16),
                ),
              ),
            ),
            const SizedBox(height: 22),
            FadeTransition(
              opacity: word,
              child: Text(
                'सीधेबोल',
                style: Boli.head(52, weight: 800, color: Boli.cream),
              ),
            ),
            const SizedBox(height: 6),
            FadeTransition(
              opacity: tag,
              child: Column(
                children: [
                  Text(
                    'कामकाजी भाषा · SeedheBol',
                    style: Boli.body(
                      16,
                      color: Boli.cream.withValues(alpha: .72),
                    ),
                  ),
                  const SizedBox(height: 22),
                  SizedBox(
                    width: 210,
                    child: HandloomBorder(
                      color: Boli.marigold.withValues(alpha: .55),
                      height: 12,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
