import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'theme.dart';

/// Readiness shown as a filling arc, not a score. The centre carries the
/// state in words, because a bare percentage means little to someone who
/// reads haltingly.
class ReadinessRing extends StatelessWidget {
  final double value;
  final double size;
  final bool showLabel;
  const ReadinessRing({super.key, required this.value, this.size = 54, this.showLabel = true});

  @override
  Widget build(BuildContext context) {
    final c = Boli.forReadiness(value);
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: value),
      duration: const Duration(milliseconds: 700),
      curve: Curves.easeOutCubic,
      builder: (_, v, __) => SizedBox(
        width: size,
        height: size,
        child: CustomPaint(
          painter: _RingPainter(v, c),
          child: showLabel
              ? Center(
                  child: v <= 0
                      ? Icon(Icons.remove_rounded, size: size * .34, color: Boli.inkSoft)
                      : (v >= 1
                          ? Icon(Icons.check_rounded, size: size * .42, color: c)
                          : Text('${(v * 100).round()}',
                              style: Boli.head(size * .32, weight: 700, color: c))),
                )
              : null,
        ),
      ),
    );
  }
}

class _RingPainter extends CustomPainter {
  final double v;
  final Color c;
  _RingPainter(this.v, this.c);

  @override
  void paint(Canvas canvas, Size size) {
    final r = size.width / 2 - 4;
    final centre = Offset(size.width / 2, size.height / 2);
    canvas.drawCircle(
        centre,
        r,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 6
          ..color = Boli.sand);
    if (v > 0) {
      canvas.drawArc(
        Rect.fromCircle(center: centre, radius: r),
        -math.pi / 2,
        2 * math.pi * v.clamp(0, 1),
        false,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 6
          ..strokeCap = StrokeCap.round
          ..color = c,
      );
    }
  }

  @override
  bool shouldRepaint(_RingPainter o) => o.v != v || o.c != c;
}

/// The primary action control. Flat, heavy, and never smaller than 64dp — the
/// depressing-key affordance was removed with the rest of the game styling.
class BigButton extends StatefulWidget {
  final String label;
  final IconData? icon;
  final VoidCallback? onTap;
  final Color color;
  final Color? textColor;
  final bool outline;
  const BigButton({
    super.key,
    required this.label,
    this.icon,
    this.onTap,
    this.color = Boli.marigold,
    this.textColor,
    this.outline = false,
  });

  @override
  State<BigButton> createState() => _BigButtonState();
}

class _BigButtonState extends State<BigButton> {
  bool _down = false;

  @override
  Widget build(BuildContext context) {
    final on = widget.onTap != null;
    final fg = widget.textColor ?? (widget.outline ? widget.color : Colors.white);
    return GestureDetector(
      onTapDown: on ? (_) => setState(() => _down = true) : null,
      onTapUp: on ? (_) => setState(() => _down = false) : null,
      onTapCancel: on ? () => setState(() => _down = false) : null,
      onTap: widget.onTap,
      child: AnimatedScale(
        scale: _down ? .97 : 1,
        duration: const Duration(milliseconds: 90),
        child: Container(
          height: Boli.tap,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: widget.outline ? Colors.transparent : (on ? widget.color : Boli.sand),
            border: Border.all(color: on ? widget.color : Boli.sand, width: 2.5),
            borderRadius: BorderRadius.circular(14),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              if (widget.icon != null) ...[
                Icon(widget.icon, color: on ? fg : Boli.inkSoft, size: 24),
                const SizedBox(width: 10),
              ],
              Text(widget.label,
                  style: Boli.body(17,
                      weight: FontWeight.w800, color: on ? fg : Boli.inkSoft)),
            ],
          ),
        ),
      ),
    );
  }
}

/// One situation on the board. Deliberately wide, tall and entirely tappable.
class SituationCard extends StatelessWidget {
  final String title, native, contextLabel;
  final IconData icon;
  final int phrases;
  final double readiness;
  final bool urgent;
  final VoidCallback? onTap;
  final int index;

  const SituationCard({
    super.key,
    required this.title,
    required this.native,
    required this.contextLabel,
    required this.icon,
    required this.phrases,
    required this.readiness,
    required this.urgent,
    required this.index,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final c = Boli.forReadiness(readiness);
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: 1),
      duration: Duration(milliseconds: 380 + index * 70),
      curve: Curves.easeOutCubic,
      builder: (_, v, child) => Opacity(
        opacity: v.clamp(0, 1),
        child: Transform.translate(offset: Offset(0, (1 - v) * 22), child: child),
      ),
      child: Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: GestureDetector(
          onTap: onTap,
          child: Container(
            decoration: Boli.card(border: urgent ? Boli.terracotta.withValues(alpha: .5) : null),
            child: Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 16, 16, 14),
                  child: Row(
                    children: [
                      Container(
                        width: 52,
                        height: 52,
                        decoration: BoxDecoration(
                          color: c.withValues(alpha: .12),
                          borderRadius: BorderRadius.circular(13),
                        ),
                        child: Icon(icon, color: c, size: 26),
                      ),
                      const SizedBox(width: 14),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            if (urgent)
                              Padding(
                                padding: const EdgeInsets.only(bottom: 4),
                                child: Text('NEEDED THIS WEEK',
                                    style: Boli.label(color: Boli.terracotta, size: 10.5)),
                              ),
                            Text(title, style: Boli.head(19, weight: 600)),
                            const SizedBox(height: 2),
                            Text(native, style: Boli.body(15, color: Boli.inkSoft)),
                          ],
                        ),
                      ),
                      const SizedBox(width: 10),
                      ReadinessRing(value: readiness, size: 50),
                    ],
                  ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                  decoration: BoxDecoration(
                    color: Boli.cream,
                    borderRadius: const BorderRadius.vertical(bottom: Radius.circular(16)),
                    border: Border(top: BorderSide(color: Boli.sand, width: 1.5)),
                  ),
                  child: Row(
                    children: [
                      Icon(Icons.chat_bubble_outline_rounded, size: 15, color: Boli.inkSoft),
                      const SizedBox(width: 6),
                      Text('$phrases phrases', style: Boli.body(13.5, color: Boli.inkSoft)),
                      const SizedBox(width: 14),
                      Icon(Icons.place_outlined, size: 15, color: Boli.inkSoft),
                      const SizedBox(width: 6),
                      Text(contextLabel, style: Boli.body(13.5, color: Boli.inkSoft)),
                      const Spacer(),
                      Text(
                        readiness <= 0
                            ? 'Not covered'
                            : (readiness >= 1 ? 'Ready' : 'Practising'),
                        style: Boli.body(13.5, weight: FontWeight.w800, color: c),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Horizontal filter chips for the context strip.
class FilterChipRow extends StatelessWidget {
  final List<String> labels;
  final List<IconData> icons;
  final int selected;
  final ValueChanged<int> onSelect;
  const FilterChipRow({
    super.key,
    required this.labels,
    required this.icons,
    required this.selected,
    required this.onSelect,
  });

  @override
  Widget build(BuildContext context) => SizedBox(
        height: 46,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 18),
          itemCount: labels.length,
          separatorBuilder: (_, __) => const SizedBox(width: 8),
          itemBuilder: (_, i) {
            final on = i == selected;
            return GestureDetector(
              onTap: () => onSelect(i),
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 180),
                padding: const EdgeInsets.symmetric(horizontal: 15),
                decoration: BoxDecoration(
                  color: on ? Boli.ink : Colors.transparent,
                  border: Border.all(color: on ? Boli.ink : Boli.sand, width: 2),
                  borderRadius: BorderRadius.circular(24),
                ),
                child: Row(children: [
                  Icon(icons[i], size: 17, color: on ? Boli.cream : Boli.inkSoft),
                  const SizedBox(width: 7),
                  Text(labels[i],
                      style: Boli.body(14.5,
                          weight: FontWeight.w700, color: on ? Boli.cream : Boli.inkSoft)),
                ]),
              ),
            );
          },
        ),
      );
}

/// Mic control used by every speaking surface. Rings expand while open.
class MicButton extends StatefulWidget {
  final bool busy;
  final VoidCallback? onTap;
  final double size;
  const MicButton({super.key, required this.busy, this.onTap, this.size = 100});

  @override
  State<MicButton> createState() => _MicButtonState();
}

class _MicButtonState extends State<MicButton> with SingleTickerProviderStateMixin {
  late final AnimationController _c =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 1200))..repeat();

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: widget.onTap,
        child: AnimatedBuilder(
          animation: _c,
          builder: (_, __) => SizedBox(
            width: widget.size * 1.9,
            height: widget.size * 1.9,
            child: Stack(
              alignment: Alignment.center,
              children: [
                if (widget.busy)
                  for (int r = 0; r < 3; r++)
                    Builder(builder: (_) {
                      final v = (_c.value + r / 3) % 1.0;
                      return Container(
                        width: widget.size + v * widget.size * .9,
                        height: widget.size + v * widget.size * .9,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          border: Border.all(
                              color: Boli.terracotta.withValues(alpha: (1 - v) * .55), width: 3),
                        ),
                      );
                    }),
                Container(
                  width: widget.size,
                  height: widget.size,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: widget.busy ? Boli.terracotta : Boli.peacock,
                    boxShadow: Boli.lift(y: 5, blur: 16, o: .22),
                  ),
                  child: Icon(widget.busy ? Icons.graphic_eq_rounded : Icons.mic_rounded,
                      color: Colors.white, size: widget.size * .44),
                ),
              ],
            ),
          ),
        ),
      );
}

/// Section heading with a handloom rule beneath it.
class SectionHead extends StatelessWidget {
  final String text;
  final String? trailing;
  const SectionHead(this.text, {super.key, this.trailing});

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(text, style: Boli.label(size: 12.5)),
              const Spacer(),
              if (trailing != null) Text(trailing!, style: Boli.label(color: Boli.terracotta, size: 12.5)),
            ],
          ),
          const SizedBox(height: 6),
          HandloomBorder(color: Boli.sand, height: 8, dense: true),
          const SizedBox(height: 14),
        ],
      );
}
