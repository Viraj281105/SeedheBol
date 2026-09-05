import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'data.dart';
import 'practice_screen.dart' show PhraseAudio;
import 'theme.dart';
import 'widgets.dart';

/// End of a situation.
///
/// The headline is a capability claim, not a score: you can now handle this
/// situation. Phrases you got right are listed with a Listen button, so the
/// last thing that happens in a lesson is hearing the sentences again — which
/// is also the cheapest useful revision there is.
///
/// Anything missed is shown as "practise again", never as a loss.
class SuccessScreen extends StatefulWidget {
  final Situation situation;
  final int correct, total;
  final List<String> learned, review;
  final Duration elapsed;
  final VoidCallback onDone;

  const SuccessScreen({
    super.key,
    required this.situation,
    required this.correct,
    required this.total,
    required this.learned,
    required this.review,
    required this.elapsed,
    required this.onDone,
  });

  @override
  State<SuccessScreen> createState() => _SuccessScreenState();
}

class _SuccessScreenState extends State<SuccessScreen>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1600),
  )..forward();

  double get _score => widget.total == 0 ? 0 : widget.correct / widget.total;

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ring = CurvedAnimation(
      parent: _c,
      curve: const Interval(0, .6, curve: Curves.easeOutCubic),
    );
    final body = CurvedAnimation(
      parent: _c,
      curve: const Interval(.3, .85, curve: Curves.easeOut),
    );
    final tail = CurvedAnimation(
      parent: _c,
      curve: const Interval(.55, 1, curve: Curves.easeOut),
    );

    return Scaffold(
      body: Stack(
        children: [
          Positioned.fill(child: _Petals(controller: _c)),
          SafeArea(
            child: Column(
              children: [
                Expanded(
                  child: ListView(
                    padding: const EdgeInsets.fromLTRB(22, 20, 22, 12),
                    children: [
                      // ---- headline -------------------------------------
                      ScaleTransition(
                        scale: Tween(begin: .8, end: 1.0).animate(ring),
                        child: FadeTransition(
                          opacity: ring,
                          child: Column(
                            children: [
                              ReadinessRing(value: _score, size: 128),
                              const SizedBox(height: 20),
                              Text(
                                _score >= .8
                                    ? 'You can handle this now'
                                    : 'Good progress',
                                textAlign: TextAlign.center,
                                style: Boli.head(29, weight: 700),
                              ),
                              const SizedBox(height: 6),
                              Text(
                                widget.situation.title,
                                textAlign: TextAlign.center,
                                style: Boli.body(16.5, color: Boli.inkSoft),
                              ),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 24),

                      // ---- three plain numbers ---------------------------
                      FadeTransition(
                        opacity: body,
                        child: Container(
                          padding: const EdgeInsets.symmetric(vertical: 18),
                          decoration: Boli.card(),
                          child: Row(
                            children: [
                              _Stat(
                                '${widget.correct}/${widget.total}',
                                'ANSWERED\nRIGHT',
                              ),
                              _Stat(
                                '${widget.learned.length}',
                                'PHRASES\nYOU CAN USE',
                              ),
                              _Stat(
                                '${widget.elapsed.inMinutes}:${(widget.elapsed.inSeconds % 60).toString().padLeft(2, '0')}',
                                'TIME\nTAKEN',
                              ),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 24),

                      // ---- what you can now say --------------------------
                      if (widget.learned.isNotEmpty)
                        FadeTransition(
                          opacity: tail,
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              const SectionHead('YOU CAN NOW SAY'),
                              for (final phrase in widget.learned)
                                Padding(
                                  padding: const EdgeInsets.only(bottom: 9),
                                  child: _PhraseRow(text: phrase, ready: true),
                                ),
                            ],
                          ),
                        ),

                      // ---- what to come back to --------------------------
                      if (widget.review.isNotEmpty) ...[
                        const SizedBox(height: 14),
                        FadeTransition(
                          opacity: tail,
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              const SectionHead('SAVED TO PRACTISE AGAIN'),
                              for (final phrase in widget.review.toSet())
                                Padding(
                                  padding: const EdgeInsets.only(bottom: 9),
                                  child: _PhraseRow(text: phrase, ready: false),
                                ),
                            ],
                          ),
                        ),
                      ],
                      const SizedBox(height: 10),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(22, 4, 22, 18),
                  child: BigButton(
                    label: 'Done',
                    icon: Icons.check_rounded,
                    color: Boli.peacock,
                    onTap: widget.onDone,
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

class _Stat extends StatelessWidget {
  final String value, label;
  const _Stat(this.value, this.label);
  @override
  Widget build(BuildContext context) => Expanded(
    child: Column(
      children: [
        Text(value, style: Boli.head(26, weight: 700, color: Boli.terracotta)),
        const SizedBox(height: 4),
        Text(label, textAlign: TextAlign.center, style: Boli.label(size: 10)),
      ],
    ),
  );
}

/// A phrase with a Listen button — the synthesiser doing revision work.
class _PhraseRow extends StatelessWidget {
  final String text;
  final bool ready;
  const _PhraseRow({required this.text, required this.ready});

  @override
  Widget build(BuildContext context) {
    final c = ready ? Boli.leaf : Boli.marigold;
    return Container(
      padding: const EdgeInsets.fromLTRB(14, 10, 10, 10),
      decoration: Boli.card(border: c.withValues(alpha: .35)),
      child: Row(
        children: [
          Icon(
            ready ? Icons.check_circle_rounded : Icons.replay_rounded,
            color: c,
            size: 21,
          ),
          const SizedBox(width: 12),
          Expanded(child: Text(text, style: Boli.head(21, weight: 600))),
          GestureDetector(
            onTap: () => PhraseAudio.play(text),
            child: Container(
              width: 46,
              height: 46,
              decoration: BoxDecoration(
                color: Boli.peacock.withValues(alpha: .12),
                borderRadius: BorderRadius.circular(23),
              ),
              child: const Icon(
                Icons.volume_up_rounded,
                color: Boli.peacock,
                size: 22,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Marigold petals, falling once. Restrained on purpose — this marks the end of
/// a work session, not a jackpot.
class _Petals extends StatelessWidget {
  final Animation<double> controller;
  const _Petals({required this.controller});

  @override
  Widget build(BuildContext context) => IgnorePointer(
    child: AnimatedBuilder(
      animation: controller,
      builder: (_, __) => CustomPaint(
        size: Size.infinite,
        painter: _PetalPainter(controller.value),
      ),
    ),
  );
}

class _PetalPainter extends CustomPainter {
  final double t;
  _PetalPainter(this.t);

  static final _rng = math.Random(7);
  static final _seeds = List.generate(
    22,
    (_) => (
      x: _rng.nextDouble(),
      delay: _rng.nextDouble() * .35,
      size: 5 + _rng.nextDouble() * 6,
      drift: (_rng.nextDouble() - .5) * 90,
      spin: (_rng.nextDouble() - .5) * 8,
    ),
  );

  @override
  void paint(Canvas canvas, Size size) {
    const palette = [
      Boli.marigold,
      Boli.turmeric,
      Boli.terracotta,
      Boli.peacock,
    ];
    for (int i = 0; i < _seeds.length; i++) {
      final s = _seeds[i];
      final lt = ((t - s.delay) / (1 - s.delay)).clamp(0.0, 1.0);
      if (lt <= 0) continue;
      final y = -20 + lt * (size.height * .75);
      final x = s.x * size.width + math.sin(lt * math.pi * 2) * s.drift;
      final paint = Paint()
        ..color = palette[i % palette.length].withValues(alpha: (1 - lt) * .85);
      canvas.save();
      canvas.translate(x, y);
      canvas.rotate(s.spin * lt);
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromCenter(
            center: Offset.zero,
            width: s.size,
            height: s.size * .6,
          ),
          const Radius.circular(3),
        ),
        paint,
      );
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(_PetalPainter old) => old.t != t;
}
