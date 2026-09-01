import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'theme.dart';

/// Slowly rotating kolam/rangoli geometry behind the app. Kept very low
/// contrast so it reads as texture, never as clutter.
class RangoliBackdrop extends StatefulWidget {
  final Color color;
  final double opacity;
  const RangoliBackdrop({super.key, this.color = Desi.marigold, this.opacity = .10});

  @override
  State<RangoliBackdrop> createState() => _RangoliBackdropState();
}

class _RangoliBackdropState extends State<RangoliBackdrop> with SingleTickerProviderStateMixin {
  late final AnimationController _c =
      AnimationController(vsync: this, duration: const Duration(seconds: 60))..repeat();

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => IgnorePointer(
        child: AnimatedBuilder(
          animation: _c,
          builder: (_, __) => CustomPaint(
            size: Size.infinite,
            painter: _RangoliPainter(_c.value, widget.color, widget.opacity),
          ),
        ),
      );
}

class _RangoliPainter extends CustomPainter {
  final double t;
  final Color color;
  final double opacity;
  _RangoliPainter(this.t, this.color, this.opacity);

  @override
  void paint(Canvas canvas, Size size) {
    final p = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.4
      ..color = color.withValues(alpha: opacity);

    // Two mandalas, counter-rotating, anchored off-canvas so they feel large.
    _mandala(canvas, Offset(size.width * .85, size.height * .12), size.width * .42, t * 2 * math.pi, p);
    _mandala(canvas, Offset(size.width * .1, size.height * .82), size.width * .34, -t * 2 * math.pi, p);

    // Kolam dot lattice.
    final dot = Paint()..color = color.withValues(alpha: opacity * .8);
    const gap = 46.0;
    for (double y = 0; y < size.height; y += gap) {
      for (double x = 0; x < size.width; x += gap) {
        canvas.drawCircle(Offset(x, y), 1.5, dot);
      }
    }
  }

  void _mandala(Canvas canvas, Offset c, double r, double rot, Paint p) {
    const petals = 12;
    for (int i = 0; i < petals; i++) {
      final a = rot + i * 2 * math.pi / petals;
      final path = Path()
        ..moveTo(c.dx, c.dy)
        ..quadraticBezierTo(
          c.dx + math.cos(a - .28) * r * .72, c.dy + math.sin(a - .28) * r * .72,
          c.dx + math.cos(a) * r, c.dy + math.sin(a) * r,
        )
        ..quadraticBezierTo(
          c.dx + math.cos(a + .28) * r * .72, c.dy + math.sin(a + .28) * r * .72,
          c.dx, c.dy,
        );
      canvas.drawPath(path, p);
    }
    canvas.drawCircle(c, r * .30, p);
    canvas.drawCircle(c, r * .18, p);
  }

  @override
  bool shouldRepaint(_RangoliPainter old) => old.t != t;
}

/// Marigold-petal burst for a correct answer or a finished lesson.
class Confetti extends StatefulWidget {
  final int count;
  const Confetti({super.key, this.count = 40});

  @override
  State<Confetti> createState() => _ConfettiState();
}

class _ConfettiState extends State<Confetti> with SingleTickerProviderStateMixin {
  late final AnimationController _c =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 1600))..forward();
  late final List<_Bit> _bits;

  @override
  void initState() {
    super.initState();
    final r = math.Random();
    const palette = [Desi.marigold, Desi.saffron, Desi.rose, Desi.peacock, Desi.leaf, Desi.terracotta];
    _bits = List.generate(widget.count, (i) {
      return _Bit(
        angle: -math.pi / 2 + (r.nextDouble() - .5) * 2.4,
        speed: 260 + r.nextDouble() * 420,
        spin: (r.nextDouble() - .5) * 12,
        size: 6 + r.nextDouble() * 8,
        color: palette[r.nextInt(palette.length)],
        delay: r.nextDouble() * .18,
      );
    });
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => IgnorePointer(
        child: AnimatedBuilder(
          animation: _c,
          builder: (_, __) => CustomPaint(
            size: Size.infinite,
            painter: _ConfettiPainter(_c.value, _bits),
          ),
        ),
      );
}

class _Bit {
  final double angle, speed, spin, size, delay;
  final Color color;
  _Bit({required this.angle, required this.speed, required this.spin,
        required this.size, required this.color, required this.delay});
}

class _ConfettiPainter extends CustomPainter {
  final double t;
  final List<_Bit> bits;
  _ConfettiPainter(this.t, this.bits);

  @override
  void paint(Canvas canvas, Size size) {
    final origin = Offset(size.width / 2, size.height * .46);
    for (final b in bits) {
      final lt = ((t - b.delay) / (1 - b.delay)).clamp(0.0, 1.0);
      if (lt <= 0) continue;
      final dx = math.cos(b.angle) * b.speed * lt;
      final dy = math.sin(b.angle) * b.speed * lt + 620 * lt * lt; // gravity
      final pos = origin + Offset(dx, dy);
      final paint = Paint()..color = b.color.withValues(alpha: (1 - lt).clamp(0.0, 1.0));
      canvas.save();
      canvas.translate(pos.dx, pos.dy);
      canvas.rotate(b.spin * lt);
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromCenter(center: Offset.zero, width: b.size, height: b.size * .62),
          const Radius.circular(2),
        ),
        paint,
      );
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(_ConfettiPainter old) => old.t != t;
}

/// A button that visibly depresses when pressed, giving taps physical weight.
class KeyButton extends StatefulWidget {
  final Widget child;
  final VoidCallback? onTap;
  final Color color;
  final EdgeInsets padding;
  const KeyButton({
    super.key,
    required this.child,
    this.onTap,
    this.color = Desi.leaf,
    this.padding = const EdgeInsets.symmetric(vertical: 16, horizontal: 24),
  });

  @override
  State<KeyButton> createState() => _KeyButtonState();
}

class _KeyButtonState extends State<KeyButton> {
  bool _down = false;

  @override
  Widget build(BuildContext context) {
    final enabled = widget.onTap != null;
    return GestureDetector(
      onTapDown: enabled ? (_) => setState(() => _down = true) : null,
      onTapUp: enabled ? (_) => setState(() => _down = false) : null,
      onTapCancel: enabled ? () => setState(() => _down = false) : null,
      onTap: widget.onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 70),
        transform: Matrix4.translationValues(0, _down ? 4 : 0, 0),
        padding: widget.padding,
        decoration: Desi.key(
          enabled ? widget.color : Desi.sand,
          edge: _down ? Colors.transparent : null,
        ),
        child: Center(child: widget.child),
      ),
    );
  }
}

/// Top-of-screen stat strip: streak, XP, hearts.
class StatBar extends StatelessWidget {
  final int hearts, streak, xp;
  const StatBar({super.key, required this.hearts, required this.streak, required this.xp});

  @override
  Widget build(BuildContext context) => Row(
        children: [
          _chip(Icons.local_fire_department_rounded, '$streak', Desi.saffron),
          const SizedBox(width: 10),
          _chip(Icons.bolt_rounded, '$xp', Desi.peacock),
          const Spacer(),
          Row(
            children: List.generate(
              3,
              (i) => Padding(
                padding: const EdgeInsets.only(left: 3),
                child: AnimatedScale(
                  duration: const Duration(milliseconds: 260),
                  scale: i < hearts ? 1 : .74,
                  child: Icon(
                    i < hearts ? Icons.favorite_rounded : Icons.favorite_border_rounded,
                    color: i < hearts ? Desi.rose : Desi.sand,
                    size: 24,
                  ),
                ),
              ),
            ),
          ),
        ],
      );

  Widget _chip(IconData icon, String label, Color c) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
        decoration: BoxDecoration(
          color: c.withValues(alpha: .13),
          borderRadius: BorderRadius.circular(30),
        ),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Icon(icon, color: c, size: 19),
          const SizedBox(width: 5),
          Text(label, style: TextStyle(fontWeight: FontWeight.w800, color: c, fontSize: 15)),
        ]),
      );
}

/// Progress bar that eases toward its target rather than jumping.
class ProgressBar extends StatelessWidget {
  final double value;
  const ProgressBar({super.key, required this.value});

  @override
  Widget build(BuildContext context) => LayoutBuilder(
        builder: (_, box) => Container(
          height: 14,
          decoration: BoxDecoration(color: Desi.sand, borderRadius: BorderRadius.circular(10)),
          child: Align(
            alignment: Alignment.centerLeft,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 420),
              curve: Curves.easeOutCubic,
              width: box.maxWidth * value.clamp(0.0, 1.0),
              decoration: BoxDecoration(
                gradient: Desi.gold,
                borderRadius: BorderRadius.circular(10),
              ),
            ),
          ),
        ),
      );
}
