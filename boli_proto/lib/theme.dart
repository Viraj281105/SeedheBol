import 'dart:ui' show FontVariation;

import 'package:flutter/material.dart';

/// India-inspired palette: marigold and saffron against deep indigo, with
/// terracotta and peacock accents. Drawn from festival colour rather than
/// flag colour, which reads as decoration instead of nationalism.
///
/// No `google_fonts` dependency anywhere in this app: that package fetches
/// fonts over the network at runtime, which would quietly break the one claim
/// this project exists to make. System fonts render Devanagari correctly.
class Desi {
  static const marigold = Color(0xFFF6A623);
  static const saffron = Color(0xFFEF7215);
  static const terracotta = Color(0xFFC1502E);
  static const peacock = Color(0xFF0E7C7B);
  static const rose = Color(0xFFD81E5B);
  static const indigo = Color(0xFF221B45);
  static const indigoSoft = Color(0xFF3A3168);
  static const cream = Color(0xFFFFF7E9);
  static const sand = Color(0xFFF3E4C8);
  static const leaf = Color(0xFF2E9E5B);
  static const ink = Color(0xFF2A2340);

  static const gold = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [marigold, saffron],
  );
  static const dusk = LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: [indigoSoft, indigo],
  );
  static const teal = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [Color(0xFF17A2A0), peacock],
  );

  static const ui = 'Mukta';
  static const display = 'Baloo2';

  /// Baloo 2 ships as a variable font, so weight comes from the `wght` axis
  /// rather than from a static file per weight.
  static TextStyle displayStyle({
    required double size,
    double weight = 700,
    Color color = indigo,
    double height = 1.2,
  }) =>
      TextStyle(
        fontFamily: display,
        fontSize: size,
        color: color,
        height: height,
        fontVariations: [FontVariation('wght', weight)],
      );

  /// Chunky, slightly playful, and legible in Devanagari at small sizes.
  static ThemeData get theme {
    final base = ThemeData(useMaterial3: true, brightness: Brightness.light);
    return base.copyWith(
      scaffoldBackgroundColor: cream,
      colorScheme: base.colorScheme.copyWith(
        primary: saffron,
        secondary: peacock,
        surface: cream,
        error: rose,
      ),
      textTheme: base.textTheme.apply(
        fontFamily: ui,
        bodyColor: ink,
        displayColor: ink,
      ),
      splashFactory: InkSparkle.splashFactory,
    );
  }

  /// The soft drop used on every raised surface. Warm, never grey.
  static List<BoxShadow> lift({double y = 6, double blur = 18, double o = .16}) => [
        BoxShadow(color: indigo.withValues(alpha: o), offset: Offset(0, y), blurRadius: blur),
      ];

  /// Duolingo's signature trick: a solid darker edge under a button so it
  /// reads as a physical key that depresses when tapped.
  static BoxDecoration key(Color face, {Color? edge, double radius = 18}) => BoxDecoration(
        color: face,
        borderRadius: BorderRadius.circular(radius),
        border: Border(bottom: BorderSide(color: edge ?? _darken(face), width: 4)),
      );

  static Color _darken(Color c, [double amount = .18]) {
    final h = HSLColor.fromColor(c);
    return h.withLightness((h.lightness - amount).clamp(0.0, 1.0)).toColor();
  }
}
