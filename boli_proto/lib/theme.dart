import 'dart:ui' show FontVariation;

import 'package:flutter/material.dart';

/// Design system for Boli.
///
/// Three constraints drive every decision here, and none of them are aesthetic:
///
///   1. The user is on a deadline. Nothing may punish or gate. No lives, no
///      locks, no "come back tomorrow". Progress is *coverage of situations you
///      will face*, never points.
///   2. The user may be reading in direct sunlight. Contrast is high, weights
///      are heavy, and nothing meaningful is rendered in low-opacity grey.
///   3. The user may have damaged fingertips from manual labour. Minimum touch
///      target is 64dp, not Material's 48dp.
///
/// The Indian visual language is carried by colour and by *structural* handloom
/// borders, not by floating ornament. Decoration on a tool that someone's wages
/// depend on reads as frivolous.
class Boli {
  // Ground and ink — warm, high contrast.
  static const cream = Color(0xFFFCF6EA);
  static const paper = Color(0xFFFFFFFF);
  static const sand = Color(0xFFEADCC2);
  static const ink = Color(0xFF1A1633);
  static const inkSoft = Color(0xFF4A4266);

  // Accents, drawn from textile dye rather than flag colour.
  static const marigold = Color(0xFFF2A013);
  static const turmeric = Color(0xFFE8820C);
  static const terracotta = Color(0xFFBE4A28);
  static const peacock = Color(0xFF0C6E70);
  static const indigo = Color(0xFF2B2560);
  static const madder = Color(0xFFA8203C);
  static const leaf = Color(0xFF2C7A4B);

  /// Readiness is a three-stage scale, never pass/fail. A situation you have
  /// not practised is *not yet covered*, which is a neutral fact, not a failure.
  static const notStarted = sand;
  static const practising = marigold;
  static const ready = peacock;

  static Color forReadiness(double r) {
    if (r <= 0) return notStarted;
    if (r < .8) return practising;
    return ready;
  }

  static const ui = 'Mukta';
  static const display = 'Baloo2';

  static TextStyle head(double size, {double weight = 700, Color color = ink, double height = 1.15}) =>
      TextStyle(
        fontFamily: display,
        fontSize: size,
        color: color,
        height: height,
        fontVariations: [FontVariation('wght', weight)],
      );

  static TextStyle body(double size,
          {FontWeight weight = FontWeight.w500, Color color = ink, double height = 1.4}) =>
      TextStyle(fontFamily: ui, fontSize: size, color: color, height: height, fontWeight: weight);

  /// Small caps label. Used for section headers and metadata.
  static TextStyle label({Color color = inkSoft, double size = 12}) => TextStyle(
        fontFamily: ui,
        fontSize: size,
        color: color,
        fontWeight: FontWeight.w700,
        letterSpacing: 1.3,
      );

  static const double tap = 64; // minimum touch target, per constraint 3

  static ThemeData get theme {
    final base = ThemeData(useMaterial3: true, brightness: Brightness.light);
    return base.copyWith(
      scaffoldBackgroundColor: cream,
      colorScheme: base.colorScheme.copyWith(
        primary: marigold,
        secondary: peacock,
        surface: cream,
        error: madder,
      ),
      textTheme: base.textTheme.apply(fontFamily: ui, bodyColor: ink, displayColor: ink),
    );
  }

  static List<BoxShadow> lift({double y = 3, double blur = 10, double o = .10}) => [
        BoxShadow(color: ink.withValues(alpha: o), offset: Offset(0, y), blurRadius: blur),
      ];

  static BoxDecoration card({Color? fill, Color? border, double radius = 18}) => BoxDecoration(
        color: fill ?? paper,
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: border ?? sand, width: 2),
      );
}

/// A handloom border stripe — the repeating stamp found on the edge of a woven
/// saree or a block-printed cloth. It is used *structurally*, as a section
/// divider or a card edge, so the cultural reference sits in the architecture
/// of the page rather than floating behind the content as decoration.
class HandloomBorder extends StatelessWidget {
  final Color color;
  final double height;
  final bool dense;
  const HandloomBorder({super.key, this.color = Boli.terracotta, this.height = 10, this.dense = false});

  @override
  Widget build(BuildContext context) => SizedBox(
        height: height,
        width: double.infinity,
        child: CustomPaint(painter: _HandloomPainter(color, dense)),
      );
}

class _HandloomPainter extends CustomPainter {
  final Color color;
  final bool dense;
  _HandloomPainter(this.color, this.dense);

  @override
  void paint(Canvas canvas, Size size) {
    final unit = dense ? 12.0 : 18.0;
    final p = Paint()..color = color;
    final line = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.4;

    // Two rules with a row of stamps between them.
    canvas.drawLine(Offset(0, .8), Offset(size.width, .8), line);
    canvas.drawLine(Offset(0, size.height - .8), Offset(size.width, size.height - .8), line);

    final mid = size.height / 2;
    final r = (size.height - 5) / 2;
    for (double x = unit / 2; x < size.width; x += unit) {
      // Alternating diamond / dot, the simplest block-print rhythm.
      if (((x - unit / 2) / unit).round().isEven) {
        final path = Path()
          ..moveTo(x, mid - r)
          ..lineTo(x + r, mid)
          ..lineTo(x, mid + r)
          ..lineTo(x - r, mid)
          ..close();
        canvas.drawPath(path, p);
      } else {
        canvas.drawCircle(Offset(x, mid), r * .48, p);
      }
    }
  }

  @override
  bool shouldRepaint(_HandloomPainter old) => old.color != color || old.dense != dense;
}
