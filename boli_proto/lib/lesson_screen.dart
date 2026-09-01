import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'data.dart';
import 'theme.dart';
import 'widgets.dart';

class LessonResult {
  final int xp, heartsLeft;
  final bool passed;
  const LessonResult(this.xp, this.heartsLeft, this.passed);
}

class LessonScreen extends StatefulWidget {
  final Lesson lesson;
  final int hearts;
  const LessonScreen({super.key, required this.lesson, required this.hearts});

  @override
  State<LessonScreen> createState() => _LessonScreenState();
}

class _LessonScreenState extends State<LessonScreen> {
  int _i = 0;
  late int _hearts = widget.hearts;
  int _xp = 0;
  bool? _correct;        // null while unanswered
  String _note = '';     // secondary line in the feedback bar
  bool _celebrate = false;

  Exercise get _ex => widget.lesson.exercises[_i];

  void _grade(bool ok, {String note = ''}) {
    setState(() {
      _correct = ok;
      _note = note;
      if (ok) {
        _xp += 10;
        _celebrate = true;
      } else {
        _hearts = math.max(0, _hearts - 1);
      }
    });
    HapticFeedback.mediumImpact();
    if (ok) {
      Future.delayed(const Duration(milliseconds: 1500), () {
        if (mounted) setState(() => _celebrate = false);
      });
    }
  }

  void _next() {
    if (_i + 1 >= widget.lesson.exercises.length || _hearts == 0) {
      Navigator.of(context).pop(LessonResult(_xp, _hearts, _hearts > 0));
      return;
    }
    setState(() {
      _i++;
      _correct = null;
      _note = '';
    });
  }

  @override
  Widget build(BuildContext context) {
    final progress = (_i + (_correct != null ? 1 : 0)) / widget.lesson.exercises.length;

    return Scaffold(
      body: Stack(
        children: [
          const Positioned.fill(child: RangoliBackdrop(opacity: .07)),
          SafeArea(
            child: Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
                  child: Row(
                    children: [
                      IconButton(
                        onPressed: () => Navigator.of(context).pop(LessonResult(_xp, _hearts, false)),
                        icon: Icon(Icons.close_rounded, color: Desi.ink.withValues(alpha: .5)),
                      ),
                      Expanded(child: ProgressBar(value: progress)),
                      const SizedBox(width: 12),
                      Row(
                        children: List.generate(
                          3,
                          (i) => AnimatedScale(
                            duration: const Duration(milliseconds: 240),
                            scale: i < _hearts ? 1 : .7,
                            child: Icon(
                              i < _hearts ? Icons.favorite_rounded : Icons.favorite_border_rounded,
                              color: i < _hearts ? Desi.rose : Desi.sand,
                              size: 21,
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                Expanded(
                  child: AnimatedSwitcher(
                    duration: const Duration(milliseconds: 340),
                    switchInCurve: Curves.easeOutCubic,
                    transitionBuilder: (child, anim) => FadeTransition(
                      opacity: anim,
                      child: SlideTransition(
                        position: Tween(begin: const Offset(.12, 0), end: Offset.zero).animate(anim),
                        child: child,
                      ),
                    ),
                    child: SingleChildScrollView(
                      key: ValueKey(_i),
                      padding: const EdgeInsets.fromLTRB(22, 10, 22, 24),
                      child: _buildExercise(),
                    ),
                  ),
                ),
                if (_correct != null) _FeedbackBar(correct: _correct!, note: _note, onNext: _next),
              ],
            ),
          ),
          if (_celebrate) const Positioned.fill(child: Confetti()),
        ],
      ),
    );
  }

  Widget _buildExercise() {
    final locked = _correct != null;
    switch (_ex.kind) {
      case Kind.choice:
        return _ChoiceExercise(ex: _ex, locked: locked, onGrade: _grade);
      case Kind.speak:
        return _SpeakExercise(ex: _ex, locked: locked, onGrade: _grade);
      case Kind.build:
        return _BuildExercise(ex: _ex, locked: locked, onGrade: _grade);
      case Kind.match:
        return _MatchExercise(ex: _ex, locked: locked, onGrade: _grade);
    }
  }
}

/// Shared header: the instruction line plus the phrase card.
class _Prompt extends StatelessWidget {
  final String text;
  const _Prompt(this.text);

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 18),
        child: Text(text,
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: Desi.indigo)),
      );
}

class _PhraseCard extends StatelessWidget {
  final String marathi, roman, english;
  const _PhraseCard({required this.marathi, this.roman = '', this.english = ''});

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 22),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: Desi.sand, width: 2),
          boxShadow: Desi.lift(y: 4, blur: 12, o: .08),
        ),
        child: Column(
          children: [
            Text(marathi,
                textAlign: TextAlign.center,
                style: const TextStyle(
                    fontSize: 30, fontWeight: FontWeight.w700, color: Desi.indigo, height: 1.35)),
            if (roman.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(roman,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                      fontSize: 15,
                      fontStyle: FontStyle.italic,
                      color: Desi.ink.withValues(alpha: .55))),
            ],
            if (english.isNotEmpty) ...[
              const SizedBox(height: 10),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
                decoration: BoxDecoration(
                  color: Desi.peacock.withValues(alpha: .1),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Text(english,
                    style: const TextStyle(
                        fontSize: 14, fontWeight: FontWeight.w700, color: Desi.peacock)),
              ),
            ],
          ],
        ),
      );
}

// ---------------------------------------------------------------- choice ----

class _ChoiceExercise extends StatefulWidget {
  final Exercise ex;
  final bool locked;
  final void Function(bool, {String note}) onGrade;
  const _ChoiceExercise({required this.ex, required this.locked, required this.onGrade});

  @override
  State<_ChoiceExercise> createState() => _ChoiceExerciseState();
}

class _ChoiceExerciseState extends State<_ChoiceExercise> {
  int? _picked;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _Prompt(widget.ex.prompt),
        _PhraseCard(marathi: widget.ex.marathi, roman: widget.ex.roman),
        const SizedBox(height: 22),
        for (int i = 0; i < widget.ex.options.length; i++)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: _OptionTile(
              label: widget.ex.options[i],
              selected: _picked == i,
              correct: widget.locked && i == widget.ex.answer,
              wrong: widget.locked && _picked == i && i != widget.ex.answer,
              onTap: widget.locked
                  ? null
                  : () {
                      setState(() => _picked = i);
                      widget.onGrade(i == widget.ex.answer,
                          note: i == widget.ex.answer ? '' : widget.ex.options[widget.ex.answer]);
                    },
            ),
          ),
      ],
    );
  }
}

class _OptionTile extends StatelessWidget {
  final String label;
  final bool selected, correct, wrong;
  final VoidCallback? onTap;
  const _OptionTile({
    required this.label,
    required this.selected,
    required this.correct,
    required this.wrong,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    Color border = Desi.sand;
    Color fill = Colors.white;
    if (correct) {
      border = Desi.leaf;
      fill = Desi.leaf.withValues(alpha: .12);
    } else if (wrong) {
      border = Desi.rose;
      fill = Desi.rose.withValues(alpha: .12);
    } else if (selected) {
      border = Desi.marigold;
      fill = Desi.marigold.withValues(alpha: .12);
    }
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 17),
        decoration: BoxDecoration(
          color: fill,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: border, width: 2),
        ),
        child: Row(
          children: [
            Expanded(
              child: Text(label,
                  style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600, color: Desi.ink)),
            ),
            if (correct) const Icon(Icons.check_circle_rounded, color: Desi.leaf, size: 22),
            if (wrong) const Icon(Icons.cancel_rounded, color: Desi.rose, size: 22),
          ],
        ),
      ),
    );
  }
}

// ----------------------------------------------------------------- speak ----

/// The exercise that exercises the actual prototype: press, speak, and the
/// on-device IndicConformer decides whether you said it.
class _SpeakExercise extends StatefulWidget {
  final Exercise ex;
  final bool locked;
  final void Function(bool, {String note}) onGrade;
  const _SpeakExercise({required this.ex, required this.locked, required this.onGrade});

  @override
  State<_SpeakExercise> createState() => _SpeakExerciseState();
}

class _SpeakExerciseState extends State<_SpeakExercise> with SingleTickerProviderStateMixin {
  static const _channel = MethodChannel('boli/asr');

  bool _busy = false;
  String _heard = '';
  String _status = '';

  late final AnimationController _pulse =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 1100))..repeat();

  @override
  void dispose() {
    _pulse.dispose();
    super.dispose();
  }

  /// Devanagari-aware similarity. Strips spaces and punctuation, then a
  /// normalised Levenshtein ratio over characters. Threshold is forgiving:
  /// a 4-second window and a 120M model will not be exact, and penalising a
  /// learner for the recogniser's errors would teach the wrong lesson.
  double _similarity(String a, String b) {
    String norm(String s) => s.replaceAll(RegExp(r'[\s।,.?!]'), '');
    final x = norm(a), y = norm(b);
    if (x.isEmpty || y.isEmpty) return 0;
    final d = List.generate(x.length + 1, (_) => List<int>.filled(y.length + 1, 0));
    for (var i = 0; i <= x.length; i++) d[i][0] = i;
    for (var j = 0; j <= y.length; j++) d[0][j] = j;
    for (var i = 1; i <= x.length; i++) {
      for (var j = 1; j <= y.length; j++) {
        final cost = x[i - 1] == y[j - 1] ? 0 : 1;
        d[i][j] = math.min(math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
      }
    }
    return 1 - d[x.length][y.length] / math.max(x.length, y.length);
  }

  Future<void> _listen() async {
    setState(() {
      _busy = true;
      _heard = '';
      _status = 'Listening…';
    });
    try {
      final text = await _channel.invokeMethod<String>('transcribeMic', {'seconds': 4.0}) ?? '';
      if (!mounted) return;
      final score = _similarity(text, widget.ex.marathi);
      setState(() {
        _heard = text.isEmpty ? '(nothing heard)' : text;
        _status = '';
        _busy = false;
      });
      widget.onGrade(
        score >= .55,
        note: score >= .55 ? '' : widget.ex.marathi,
      );
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _status = e.message ?? 'Microphone unavailable';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _Prompt(widget.ex.prompt),
        _PhraseCard(
          marathi: widget.ex.marathi,
          roman: widget.ex.roman,
          english: widget.ex.english,
        ),
        const SizedBox(height: 34),
        Center(
          child: GestureDetector(
            onTap: (_busy || widget.locked) ? null : _listen,
            child: AnimatedBuilder(
              animation: _pulse,
              builder: (_, __) {
                return SizedBox(
                  width: 190,
                  height: 190,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      // Expanding rings while the mic is open.
                      if (_busy)
                        for (int r = 0; r < 3; r++)
                          Builder(builder: (_) {
                            final v = ((_pulse.value + r / 3) % 1.0);
                            return Container(
                              width: 92 + v * 96,
                              height: 92 + v * 96,
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                border: Border.all(
                                  color: Desi.rose.withValues(alpha: (1 - v) * .5),
                                  width: 2.5,
                                ),
                              ),
                            );
                          }),
                      Container(
                        width: 96,
                        height: 96,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          gradient: _busy
                              ? const LinearGradient(colors: [Desi.rose, Desi.terracotta])
                              : Desi.teal,
                          boxShadow: Desi.lift(y: 6, blur: 20, o: .3),
                        ),
                        child: Icon(
                          _busy ? Icons.graphic_eq_rounded : Icons.mic_rounded,
                          color: Colors.white,
                          size: 42,
                        ),
                      ),
                    ],
                  ),
                );
              },
            ),
          ),
        ),
        const SizedBox(height: 14),
        Center(
          child: Text(
            _busy ? 'Listening…' : (_status.isNotEmpty ? _status : 'Tap and say it'),
            style: TextStyle(
                fontSize: 14, fontWeight: FontWeight.w700, color: Desi.ink.withValues(alpha: .6)),
          ),
        ),
        if (_heard.isNotEmpty) ...[
          const SizedBox(height: 20),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
            decoration: BoxDecoration(
              color: Desi.indigo.withValues(alpha: .05),
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: Desi.sand, width: 1.5),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(children: [
                  Icon(Icons.hearing_rounded, size: 15, color: Desi.ink.withValues(alpha: .5)),
                  const SizedBox(width: 6),
                  Text('Recognised on device',
                      style: TextStyle(
                          fontSize: 11,
                          letterSpacing: .6,
                          fontWeight: FontWeight.w800,
                          color: Desi.ink.withValues(alpha: .5))),
                ]),
                const SizedBox(height: 7),
                Text(_heard,
                    style: const TextStyle(
                        fontSize: 19, fontWeight: FontWeight.w600, color: Desi.indigo)),
              ],
            ),
          ),
        ],
      ],
    );
  }
}

// ----------------------------------------------------------------- build ----

class _BuildExercise extends StatefulWidget {
  final Exercise ex;
  final bool locked;
  final void Function(bool, {String note}) onGrade;
  const _BuildExercise({required this.ex, required this.locked, required this.onGrade});

  @override
  State<_BuildExercise> createState() => _BuildExerciseState();
}

class _BuildExerciseState extends State<_BuildExercise> {
  final List<String> _picked = [];
  late final List<String> _pool = [...widget.ex.bank];

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _Prompt(widget.ex.prompt),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          decoration: BoxDecoration(
            color: Desi.peacock.withValues(alpha: .1),
            borderRadius: BorderRadius.circular(14),
          ),
          child: Text(widget.ex.english,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: Desi.peacock)),
        ),
        const SizedBox(height: 24),
        // Answer tray
        Container(
          constraints: const BoxConstraints(minHeight: 72),
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            border: Border(bottom: BorderSide(color: Desi.sand, width: 2)),
          ),
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final w in _picked)
                _Tile(
                  label: w,
                  onTap: widget.locked
                      ? null
                      : () => setState(() {
                            _picked.remove(w);
                            _pool.add(w);
                          }),
                ),
            ],
          ),
        ),
        const SizedBox(height: 26),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final w in _pool)
              _Tile(
                label: w,
                onTap: widget.locked
                    ? null
                    : () => setState(() {
                          _pool.remove(w);
                          _picked.add(w);
                        }),
              ),
          ],
        ),
        const SizedBox(height: 30),
        if (!widget.locked)
          KeyButton(
            color: _picked.isEmpty ? Desi.sand : Desi.leaf,
            onTap: _picked.isEmpty
                ? null
                : () {
                    final answer = _picked.join(' ');
                    widget.onGrade(answer == widget.ex.marathi, note: widget.ex.marathi);
                  },
            child: const Text('CHECK',
                style: TextStyle(
                    color: Colors.white, fontWeight: FontWeight.w900, letterSpacing: 1.2, fontSize: 15)),
          ),
      ],
    );
  }
}

class _Tile extends StatelessWidget {
  final String label;
  final VoidCallback? onTap;
  const _Tile({required this.label, this.onTap});

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          decoration: Desi.key(Colors.white, edge: Desi.sand, radius: 14),
          child: Text(label,
              style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w600, color: Desi.indigo)),
        ),
      );
}

// ----------------------------------------------------------------- match ----

class _MatchExercise extends StatefulWidget {
  final Exercise ex;
  final bool locked;
  final void Function(bool, {String note}) onGrade;
  const _MatchExercise({required this.ex, required this.locked, required this.onGrade});

  @override
  State<_MatchExercise> createState() => _MatchExerciseState();
}

class _MatchExerciseState extends State<_MatchExercise> {
  late final List<String> _left = [for (final p in widget.ex.pairs) p[0]];
  late final List<String> _right = [for (final p in widget.ex.pairs) p[1]]..shuffle();
  final Set<String> _done = {};
  String? _selL, _selR;
  String? _flashWrong;

  void _check() {
    if (_selL == null || _selR == null) return;
    final ok = widget.ex.pairs.any((p) => p[0] == _selL && p[1] == _selR);
    if (ok) {
      setState(() {
        _done.addAll([_selL!, _selR!]);
        _selL = null;
        _selR = null;
      });
      if (_done.length == widget.ex.pairs.length * 2) {
        widget.onGrade(true);
      }
    } else {
      setState(() => _flashWrong = _selR);
      Future.delayed(const Duration(milliseconds: 420), () {
        if (mounted) {
          setState(() {
            _flashWrong = null;
            _selL = null;
            _selR = null;
          });
        }
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _Prompt(widget.ex.prompt),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                children: [
                  for (final w in _left)
                    _MatchTile(
                      label: w,
                      done: _done.contains(w),
                      selected: _selL == w,
                      wrong: false,
                      onTap: _done.contains(w) || widget.locked
                          ? null
                          : () {
                              setState(() => _selL = w);
                              _check();
                            },
                    ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                children: [
                  for (final w in _right)
                    _MatchTile(
                      label: w,
                      done: _done.contains(w),
                      selected: _selR == w,
                      wrong: _flashWrong == w,
                      onTap: _done.contains(w) || widget.locked
                          ? null
                          : () {
                              setState(() => _selR = w);
                              _check();
                            },
                    ),
                ],
              ),
            ),
          ],
        ),
      ],
    );
  }
}

class _MatchTile extends StatelessWidget {
  final String label;
  final bool done, selected, wrong;
  final VoidCallback? onTap;
  const _MatchTile({
    required this.label,
    required this.done,
    required this.selected,
    required this.wrong,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    Color border = Desi.sand, fill = Colors.white, text = Desi.indigo;
    if (done) {
      border = Desi.leaf.withValues(alpha: .4);
      fill = Desi.leaf.withValues(alpha: .1);
      text = Desi.leaf;
    } else if (wrong) {
      border = Desi.rose;
      fill = Desi.rose.withValues(alpha: .12);
    } else if (selected) {
      border = Desi.marigold;
      fill = Desi.marigold.withValues(alpha: .14);
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: GestureDetector(
        onTap: onTap,
        child: AnimatedOpacity(
          duration: const Duration(milliseconds: 250),
          opacity: done ? .45 : 1,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 16),
            decoration: BoxDecoration(
              color: fill,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: border, width: 2),
            ),
            child: Center(
              child: Text(label,
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600, color: text)),
            ),
          ),
        ),
      ),
    );
  }
}

// -------------------------------------------------------------- feedback ----

class _FeedbackBar extends StatelessWidget {
  final bool correct;
  final String note;
  final VoidCallback onNext;
  const _FeedbackBar({required this.correct, required this.note, required this.onNext});

  @override
  Widget build(BuildContext context) {
    final c = correct ? Desi.leaf : Desi.rose;
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: 1),
      duration: const Duration(milliseconds: 320),
      curve: Curves.easeOutCubic,
      builder: (_, v, child) => Transform.translate(offset: Offset(0, (1 - v) * 140), child: child),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.fromLTRB(22, 18, 22, 22),
        decoration: BoxDecoration(
          color: c.withValues(alpha: .12),
          borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                Icon(correct ? Icons.check_circle_rounded : Icons.cancel_rounded, color: c, size: 30),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(correct ? 'शाब्बास!' : 'Not quite',
                          style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900, color: c)),
                      if (note.isNotEmpty)
                        Padding(
                          padding: const EdgeInsets.only(top: 2),
                          child: Text('Answer: $note',
                              style: TextStyle(
                                  fontSize: 14, fontWeight: FontWeight.w600, color: c.withValues(alpha: .85))),
                        ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            KeyButton(
              color: c,
              onTap: onNext,
              child: const Text('CONTINUE',
                  style: TextStyle(
                      color: Colors.white, fontWeight: FontWeight.w900, letterSpacing: 1.2, fontSize: 15)),
            ),
          ],
        ),
      ),
    );
  }
}
