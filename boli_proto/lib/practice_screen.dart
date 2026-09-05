import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'bridge/boli_bridge.dart';
import 'bridge/phonetic_diagnostics.dart';
import 'data.dart';
import 'success_screen.dart';
import 'theme.dart';
import 'widgets.dart';

/// The practice runner.
///
/// No hearts, no lives, no fail state. A wrong answer schedules the phrase for
/// review and moves on; it never ends the session or blocks progress. Someone
/// with three weeks cannot be sent back to the start of a unit.
class PracticeScreen extends StatefulWidget {
  final Situation situation;
  final bool isVoiceFirst;
  const PracticeScreen({
    super.key,
    required this.situation,
    this.isVoiceFirst = false,
  });

  @override
  State<PracticeScreen> createState() => _PracticeScreenState();
}

class _PracticeScreenState extends State<PracticeScreen> {
  int _i = 0;
  int _right = 0;
  bool? _correct;
  String _note = '';
  bool _done = false;
  late bool _voiceFirst;
  final List<String> _review = [];
  final List<String> _learned = [];
  final DateTime _startedAt = DateTime.now();

  Exercise get _ex => widget.situation.exercises[_i];

  @override
  void initState() {
    super.initState();
    _voiceFirst = widget.isVoiceFirst;
    if (_voiceFirst && _ex.marathi.isNotEmpty) {
      Future.delayed(const Duration(milliseconds: 400), () {
        if (mounted) PhraseAudio.play(_ex.marathi);
      });
    }
  }

  void _grade(bool ok, {String note = ''}) {
    setState(() {
      _correct = ok;
      _note = note;
      if (ok) {
        _right++;
        if (_ex.marathi.isNotEmpty && !_learned.contains(_ex.marathi)) {
          _learned.add(_ex.marathi);
        }
      } else {
        _review.add(_ex.marathi);
      }
    });

    // Record attempt in offline local memory for Gemma personalization
    if (_ex.marathi.isNotEmpty) {
      BoliBridge.instance.recordWordAttempt(
        word: _ex.marathi,
        isCorrect: ok,
      );
    }

    HapticFeedback.selectionClick();
  }

  void _next() {
    if (_i + 1 >= widget.situation.exercises.length) {
      setState(() => _done = true);
      // Record completed situation in learner memory
      BoliBridge.instance.recordCompletedScenario(widget.situation.title);
      return;
    }
    setState(() {
      _i++;
      _correct = null;
      _note = '';
    });
    if (_voiceFirst && _ex.marathi.isNotEmpty) {
      Future.delayed(const Duration(milliseconds: 300), () {
        if (mounted) PhraseAudio.play(_ex.marathi);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final total = widget.situation.exercises.length;
    final progress = (_i + (_correct != null ? 1 : 0)) / total;

    if (_done) {
      return SuccessScreen(
        situation: widget.situation,
        correct: _right,
        total: total,
        learned: _learned,
        review: _review,
        elapsed: DateTime.now().difference(_startedAt),
        onDone: () => Navigator.of(context).pop(_right / total),
      );
    }

    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            // ---- header: what you are practising, and how far in ----------
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 8, 20, 10),
              child: Row(
                children: [
                  GestureDetector(
                    onTap: () => Navigator.of(context).pop(_right / total),
                    child: const SizedBox(
                      width: 48,
                      height: 48,
                      child: Icon(Icons.close_rounded, size: 26),
                    ),
                  ),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          widget.situation.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Boli.body(15, weight: FontWeight.w800),
                        ),
                        Text(
                          'Phrase ${_i + 1} of $total',
                          style: Boli.body(13, color: Boli.inkSoft),
                        ),
                      ],
                    ),
                  ),
                  GestureDetector(
                    onTap: () {
                      setState(() => _voiceFirst = !_voiceFirst);
                      HapticFeedback.lightImpact();
                      if (_voiceFirst && _ex.marathi.isNotEmpty) {
                        PhraseAudio.play(_ex.marathi);
                      }
                    },
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 6,
                      ),
                      decoration: BoxDecoration(
                        color: _voiceFirst
                            ? Boli.peacock
                            : Boli.peacock.withValues(alpha: .12),
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(
                            _voiceFirst
                                ? Icons.volume_up_rounded
                                : Icons.volume_mute_rounded,
                            size: 16,
                            color: _voiceFirst ? Boli.cream : Boli.peacock,
                          ),
                          const SizedBox(width: 4),
                          Text(
                            _voiceFirst ? 'ध्वनी ON' : 'ध्वनी',
                            style: Boli.body(
                              12,
                              weight: FontWeight.w700,
                              color: _voiceFirst ? Boli.cream : Boli.peacock,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: LayoutBuilder(
                builder: (_, box) => Container(
                  height: 8,
                  decoration: BoxDecoration(
                    color: Boli.sand,
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 400),
                      curve: Curves.easeOutCubic,
                      width: box.maxWidth * progress,
                      decoration: BoxDecoration(
                        color: Boli.marigold,
                        borderRadius: BorderRadius.circular(6),
                      ),
                    ),
                  ),
                ),
              ),
            ),
            Expanded(
              child: AnimatedSwitcher(
                duration: const Duration(milliseconds: 320),
                transitionBuilder: (child, a) => FadeTransition(
                  opacity: a,
                  child: SlideTransition(
                    position: Tween(
                      begin: const Offset(.1, 0),
                      end: Offset.zero,
                    ).animate(a),
                    child: child,
                  ),
                ),
                child: SingleChildScrollView(
                  key: ValueKey(_i),
                  padding: const EdgeInsets.fromLTRB(20, 22, 20, 20),
                  child: switch (_ex.kind) {
                    Kind.choice => _Choice(
                      ex: _ex,
                      locked: _correct != null,
                      onGrade: _grade,
                    ),
                    Kind.speak => _Speak(
                      ex: _ex,
                      locked: _correct != null,
                      onGrade: _grade,
                    ),
                    Kind.build => _Build(
                      ex: _ex,
                      locked: _correct != null,
                      onGrade: _grade,
                    ),
                    Kind.match => _Match(
                      ex: _ex,
                      locked: _correct != null,
                      onGrade: _grade,
                    ),
                  },
                ),
              ),
            ),
            if (_correct != null)
              _Verdict(correct: _correct!, note: _note, onNext: _next),
          ],
        ),
      ),
    );
  }
}

// ------------------------------------------------------------- shared bits --

class _Instruction extends StatelessWidget {
  final String text;
  final String? subtext;
  const _Instruction(this.text, {this.subtext});
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(text, style: Boli.head(23, weight: 700)),
        if (subtext != null && subtext!.isNotEmpty) ...[
          const SizedBox(height: 3),
          Text(
            subtext!,
            style: Boli.body(14.5, color: Boli.inkSoft, weight: FontWeight.w600),
          ),
        ],
      ],
    ),
  );
}

/// Plays a phrase through the on-device synthesiser. Shared by every exercise
/// so there is exactly one place that talks to the "speak" channel method.
class PhraseAudio {
  static const _channel = MethodChannel('boli/asr');
  static Future<void> play(String text) async {
    try {
      await _channel.invokeMethod('speak', {'text': text});
    } on PlatformException {
      // A missing phrase is not worth interrupting a lesson for.
    }
  }
}

class _Phrase extends StatefulWidget {
  final String marathi, devanagariPhonetic, roman, english;

  /// Speak the phrase as soon as it appears. Asking someone to pronounce a
  /// word they have never heard is the bug this exists to fix.
  final bool autoPlay;
  const _Phrase({
    required this.marathi,
    this.devanagariPhonetic = '',
    this.roman = '',
    this.english = '',
    this.autoPlay = false,
  });

  @override
  State<_Phrase> createState() => _PhraseState();
}

class _PhraseState extends State<_Phrase> {
  bool _playing = false;

  @override
  void initState() {
    super.initState();
    if (widget.autoPlay) {
      // Let the entry transition settle first, otherwise the audio starts
      // under a screen that is still sliding in.
      Future.delayed(const Duration(milliseconds: 420), () {
        if (mounted) _speak();
      });
    }
  }

  Future<void> _speak() async {
    setState(() => _playing = true);
    await PhraseAudio.play(widget.marathi);
    if (!mounted) return;
    // No completion callback from AudioTrack here; the indicator is a cue that
    // the tap registered, not a claim about playback position.
    await Future.delayed(const Duration(milliseconds: 900));
    if (mounted) setState(() => _playing = false);
  }

  String get marathi => widget.marathi;
  String get devanagariPhonetic => widget.devanagariPhonetic;
  String get roman => widget.roman;
  String get english => widget.english;

  @override
  Widget build(BuildContext context) => Container(
    width: double.infinity,
    decoration: Boli.card(),
    child: Column(
      children: [
        HandloomBorder(
          color: Boli.marigold.withValues(alpha: .5),
          height: 9,
          dense: true,
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(18, 18, 18, 20),
          child: Column(
            children: [
              Text(
                marathi,
                textAlign: TextAlign.center,
                style: Boli.head(36, weight: 600, height: 1.3),
              ),
              if (devanagariPhonetic.isNotEmpty && devanagariPhonetic != marathi) ...[
                const SizedBox(height: 8),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 5),
                  decoration: BoxDecoration(
                    color: Boli.marigold.withValues(alpha: .16),
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(color: Boli.marigold.withValues(alpha: .45)),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.record_voice_over_rounded, size: 15, color: Boli.ink),
                      const SizedBox(width: 6),
                      Text(
                        'उच्चार: $devanagariPhonetic',
                        style: Boli.head(17, weight: 700, color: Boli.ink),
                      ),
                    ],
                  ),
                ),
              ],
              if (roman.isNotEmpty) ...[
                const SizedBox(height: 6),
                Text(
                  roman,
                  textAlign: TextAlign.center,
                  style: Boli.body(
                    16,
                    color: Boli.inkSoft,
                  ).copyWith(fontStyle: FontStyle.italic),
                ),
              ],
              if (english.isNotEmpty) ...[
                const SizedBox(height: 12),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 6,
                  ),
                  decoration: BoxDecoration(
                    color: Boli.peacock.withValues(alpha: .1),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    english,
                    style: Boli.body(
                      15,
                      weight: FontWeight.w700,
                      color: Boli.peacock,
                    ),
                  ),
                ),
              ],
              const SizedBox(height: 16),
              GestureDetector(
                onTap: _speak,
                child: Container(
                  height: 52,
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  decoration: BoxDecoration(
                    color: Boli.peacock.withValues(alpha: _playing ? .22 : .1),
                    borderRadius: BorderRadius.circular(26),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        _playing
                            ? Icons.volume_up_rounded
                            : Icons.play_arrow_rounded,
                        color: Boli.peacock,
                        size: 24,
                      ),
                      const SizedBox(width: 8),
                      Text(
                        _playing ? 'Playing' : 'Listen',
                        style: Boli.body(
                          15.5,
                          weight: FontWeight.w800,
                          color: Boli.peacock,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

// ----------------------------------------------------------------- choice ---

class _Choice extends StatefulWidget {
  final Exercise ex;
  final bool locked;
  final void Function(bool, {String note}) onGrade;
  const _Choice({
    required this.ex,
    required this.locked,
    required this.onGrade,
  });
  @override
  State<_Choice> createState() => _ChoiceState();
}

class _ChoiceState extends State<_Choice> {
  int? _picked;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _Instruction(
        widget.ex.prompt,
        subtext: widget.ex.promptL1.isNotEmpty
            ? widget.ex.promptL1
            : 'याचा अर्थ काय? (Choose meaning)',
      ),
      _Phrase(
        marathi: widget.ex.marathi,
        devanagariPhonetic: widget.ex.devanagariPhonetic,
        roman: widget.ex.roman,
      ),
      const SizedBox(height: 22),
      for (int i = 0; i < widget.ex.options.length; i++)
        Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: _Option(
            label: widget.ex.options[i],
            right: widget.locked && i == widget.ex.answer,
            wrong: widget.locked && _picked == i && i != widget.ex.answer,
            onTap: widget.locked
                ? null
                : () {
                    setState(() => _picked = i);
                    widget.onGrade(
                      i == widget.ex.answer,
                      note: i == widget.ex.answer
                          ? ''
                          : widget.ex.options[widget.ex.answer],
                    );
                  },
          ),
        ),
    ],
  );
}

class _Option extends StatelessWidget {
  final String label;
  final bool right, wrong;
  final VoidCallback? onTap;
  const _Option({
    required this.label,
    required this.right,
    required this.wrong,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    var border = Boli.sand, fill = Boli.paper;
    if (right) {
      border = Boli.leaf;
      fill = Boli.leaf.withValues(alpha: .1);
    } else if (wrong) {
      border = Boli.madder;
      fill = Boli.madder.withValues(alpha: .1);
    }
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        height: Boli.tap,
        padding: const EdgeInsets.symmetric(horizontal: 18),
        decoration: BoxDecoration(
          color: fill,
          border: Border.all(color: border, width: 2.5),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Row(
          children: [
            Expanded(
              child: Text(label, style: Boli.body(17, weight: FontWeight.w600)),
            ),
            if (right)
              const Icon(
                Icons.check_circle_rounded,
                color: Boli.leaf,
                size: 23,
              ),
            if (wrong)
              const Icon(Icons.cancel_rounded, color: Boli.madder, size: 23),
          ],
        ),
      ),
    );
  }
}

// ------------------------------------------------------------------ speak ---

/// The exercise that runs real inference: IndicConformer, on this device.
class _Speak extends StatefulWidget {
  final Exercise ex;
  final bool locked;
  final void Function(bool, {String note}) onGrade;
  const _Speak({required this.ex, required this.locked, required this.onGrade});
  @override
  State<_Speak> createState() => _SpeakState();
}

class _SpeakState extends State<_Speak> {
  static const _channel = MethodChannel('boli/asr');
  bool _busy = false;
  String _heard = '';
  String _error = '';
  double _score = 0;
  PhoneticDiagnosticResult? _diagnostic;
  bool _semanticMatch = false;
  String _semanticFeedback = '';
  String _betterWay = '';

  double _similarity(String a, String b) {
    String norm(String s) => s.replaceAll(RegExp(r'[\s।,.?!]'), '');
    final x = norm(a), y = norm(b);
    if (x.isEmpty || y.isEmpty) return 0;
    final d = List.generate(
      x.length + 1,
      (_) => List<int>.filled(y.length + 1, 0),
    );
    for (var i = 0; i <= x.length; i++) {
      d[i][0] = i;
    }
    for (var j = 0; j <= y.length; j++) {
      d[0][j] = j;
    }
    for (var i = 1; i <= x.length; i++) {
      for (var j = 1; j <= y.length; j++) {
        final cost = x[i - 1] == y[j - 1] ? 0 : 1;
        d[i][j] = math.min(
          math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
          d[i - 1][j - 1] + cost,
        );
      }
    }
    return 1 - d[x.length][y.length] / math.max(x.length, y.length);
  }

  Future<void> _listen() async {
    setState(() {
      _busy = true;
      _heard = '';
      _error = '';
      _diagnostic = null;
      _semanticMatch = false;
      _semanticFeedback = '';
      _betterWay = '';
    });
    try {
      final text =
          await _channel.invokeMethod<String>('transcribeMic', {
            'seconds': 4.0,
          }) ??
          '';
      if (!mounted) return;

      // Run computational phonetics & L1 interference diagnosis
      final diag = PhoneticDiagnosticService.analyze(
        targetText: widget.ex.marathi,
        heardText: text,
        targetLang: 'mr',
        nativeLang: 'hi',
      );
      final rawSim = _similarity(text, widget.ex.marathi);
      final s = diag.score > 0 ? (diag.score * 0.5 + rawSim * 0.5) : rawSim;

      bool ok = s >= .55;
      bool semMatch = false;
      String semFeedback = '';
      String semBetter = '';

      if (!ok && text.trim().isNotEmpty) {
        // Evaluate semantic intent using Gemma AI / intelligent fallback
        final eval = await BoliBridge.instance.evaluateSpokenIntent(
          targetPhrase: widget.ex.marathi,
          prompt: widget.ex.prompt,
          spokenText: text,
        );
        if (eval['is_matched'] == true) {
          semMatch = true;
          ok = true;
          semFeedback = eval['feedback'] as String? ?? 'अर्थ योग्य आहे! (Meaning understood!)';
          semBetter = eval['better_way'] as String? ?? '';
        }
      }

      setState(() {
        _heard = text.isEmpty ? '—' : text;
        _score = semMatch ? math.max(s, 0.78) : s;
        _diagnostic = diag;
        _semanticMatch = semMatch;
        _semanticFeedback = semFeedback;
        _betterWay = semBetter;
        _busy = false;
      });

      // Record acoustic pronunciation score in offline memory
      if (widget.ex.marathi.isNotEmpty) {
        BoliBridge.instance.recordPronunciationWeakness(
          word: widget.ex.marathi,
          score: semMatch ? math.max(s, 0.78) : s,
          phoneme: diag.weakPhonemes.isNotEmpty ? diag.weakPhonemes.first : null,
        );
      }

      widget.onGrade(ok, note: ok ? (semMatch ? 'अर्थ समजला!' : '') : widget.ex.marathi);
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = e.message ?? 'Microphone unavailable';
      });
    }
  }

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _Instruction(
        widget.ex.prompt,
        subtext: widget.ex.promptL1.isNotEmpty
            ? widget.ex.promptL1
            : 'मोठ्याने बोला (माइक दाबा आणि बोला)',
      ),
      _Phrase(
        marathi: widget.ex.marathi,
        devanagariPhonetic: widget.ex.devanagariPhonetic,
        roman: widget.ex.roman,
        english: widget.ex.english,
        autoPlay: true,
      ),
      const SizedBox(height: 20),
      Center(
        child: MicButton(
          busy: _busy,
          onTap: (_busy || widget.locked) ? null : _listen,
        ),
      ),
      const SizedBox(height: 8),
      Center(
        child: Text(
          _busy
              ? 'Listening… ऐकत आहे…'
              : (_error.isNotEmpty ? _error : 'माइक दाबा आणि बोला · Tap & Speak'),
          style: Boli.body(15, weight: FontWeight.w700, color: Boli.inkSoft),
        ),
      ),
      if (_heard.isNotEmpty) ...[
        const SizedBox(height: 20),
        _HeardPanel(
          heard: _heard,
          score: _score,
          target: widget.ex.marathi,
          diagnostic: _diagnostic,
          semanticMatch: _semanticMatch,
          semanticFeedback: _semanticFeedback,
          betterWay: _betterWay,
        ),
      ],
    ],
  );
}

/// Shows what the device heard, phonemic IPA transcription, and targeted
/// L1 articulatory guidance from on-device confusion matrices.
class _HeardPanel extends StatelessWidget {
  final String heard, target;
  final double score;
  final PhoneticDiagnosticResult? diagnostic;
  final bool semanticMatch;
  final String semanticFeedback;
  final String betterWay;

  const _HeardPanel({
    required this.heard,
    required this.score,
    required this.target,
    this.diagnostic,
    this.semanticMatch = false,
    this.semanticFeedback = '',
    this.betterWay = '',
  });

  @override
  Widget build(BuildContext context) {
    final ok = score >= .55 || semanticMatch;
    final diag = diagnostic;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: Boli.card(fill: Boli.cream),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.memory_rounded, size: 16, color: Boli.inkSoft),
              const SizedBox(width: 7),
              Text('HEARD ON THIS PHONE', style: Boli.label(size: 11)),
              const Spacer(),
              Text(
                semanticMatch ? 'Context Match (Gemma AI)' : '${(score * 100).round()}% match',
                style: Boli.body(
                  13,
                  weight: FontWeight.w800,
                  color: ok ? Boli.leaf : Boli.terracotta,
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: Text(heard, style: Boli.head(24, weight: 600)),
              ),
              if (diag != null && diag.heardIpa.isNotEmpty)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: Boli.sand.withValues(alpha: .5),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text(
                    '/${diag.heardIpa}/',
                    style: Boli.label(size: 11, color: Boli.inkSoft),
                  ),
                ),
            ],
          ),

          // Gemma AI Semantic Understanding Card
          if (semanticMatch) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Boli.peacock.withValues(alpha: .09),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: Boli.peacock.withValues(alpha: .40)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.auto_awesome_rounded, size: 16, color: Boli.peacock),
                      const SizedBox(width: 6),
                      Text(
                        'GEMMA AI · अर्थ समजला (MEANING UNDERSTOOD)',
                        style: Boli.label(size: 10.5, color: Boli.peacock),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    semanticFeedback.isNotEmpty ? semanticFeedback : 'अर्थ अगदी योग्य आहे! (Meaning is correct!)',
                    style: Boli.body(13.5, weight: FontWeight.w700, color: Boli.ink),
                  ),
                  if (betterWay.isNotEmpty && betterWay != target) ...[
                    const SizedBox(height: 4),
                    Text(
                      'असाही बोलू शकता: "$betterWay"',
                      style: Boli.label(size: 11, color: Boli.peacock),
                    ),
                  ],
                ],
              ),
            ),
          ],

          // L1-Interference Articulatory Diagnostic Card
          if (diag != null && diag.hasL1Interference && !semanticMatch) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Boli.terracotta.withValues(alpha: .08),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: Boli.terracotta.withValues(alpha: .35)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.record_voice_over_rounded, size: 16, color: Boli.terracotta),
                      const SizedBox(width: 6),
                      Text(
                        'L1 INTERFERENCE DETECTED · उच्चार सुधारणा',
                        style: Boli.label(size: 10.5, color: Boli.terracotta),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    diag.articulatoryAdvice!,
                    style: Boli.body(13.5, weight: FontWeight.w700, color: Boli.ink),
                  ),
                  if (diag.phenomenon != null) ...[
                    const SizedBox(height: 4),
                    Text(
                      diag.phenomenon!,
                      style: Boli.label(size: 10, color: Boli.inkSoft),
                    ),
                  ],
                ],
              ),
            ),
          ],

          if (!ok) ...[
            const SizedBox(height: 12),
            HandloomBorder(color: Boli.sand, height: 8, dense: true),
            const SizedBox(height: 12),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(
                  Icons.lightbulb_outline_rounded,
                  size: 17,
                  color: Boli.terracotta,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'संदर्भ वाक्य (Reference): $target',
                    style: Boli.body(
                      15,
                      weight: FontWeight.w600,
                      color: Boli.terracotta,
                    ),
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

// ------------------------------------------------------------------ build ---

class _Build extends StatefulWidget {
  final Exercise ex;
  final bool locked;
  final void Function(bool, {String note}) onGrade;
  const _Build({required this.ex, required this.locked, required this.onGrade});
  @override
  State<_Build> createState() => _BuildState();
}

/// Sentence construction, using Duolingo's word-bank-onto-ruled-lines layout.
///
/// This is a deliberate, scoped borrow. The pattern is a genuinely good
/// solution to "assemble a sentence without a keyboard" — it is legible without
/// instructions and it works for someone who cannot type Devanagari. What is
/// NOT borrowed is the surrounding game: no hearts ride on this, and getting it
/// wrong still just files the phrase for review.
class _BuildState extends State<_Build> {
  final List<String> _picked = [];
  late final List<String> _pool = [...widget.ex.bank];

  static const _rowH = 58.0;
  static const _rows = 2;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _Instruction(
        widget.ex.prompt,
        subtext: widget.ex.promptL1.isNotEmpty
            ? widget.ex.promptL1
            : 'वाक्य बनवा (Build the sentence)',
      ),
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        decoration: BoxDecoration(
          color: Boli.peacock.withValues(alpha: .1),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            Expanded(
              child: Text(
                widget.ex.english,
                style: Boli.body(
                  17,
                  weight: FontWeight.w700,
                  color: Boli.peacock,
                ),
              ),
            ),
            GestureDetector(
              onTap: () => PhraseAudio.play(widget.ex.marathi),
              child: Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: Boli.peacock.withValues(alpha: .14),
                  borderRadius: BorderRadius.circular(22),
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
      ),
      const SizedBox(height: 26),

      // ---- the ruled answer area ----------------------------------------
      SizedBox(
        height: _rowH * _rows,
        child: Stack(
          children: [
            CustomPaint(
              size: Size(double.infinity, _rowH * _rows),
              painter: _RulePainter(rowHeight: _rowH, rows: _rows),
            ),
            Wrap(
              spacing: 8,
              runSpacing: _rowH - 46,
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
          ],
        ),
      ),
      const SizedBox(height: 30),

      // ---- the word bank -------------------------------------------------
      Wrap(
        spacing: 8,
        runSpacing: 10,
        alignment: WrapAlignment.center,
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
      const SizedBox(height: 32),
      if (!widget.locked)
        BigButton(
          label: 'Check',
          onTap: _picked.isEmpty
              ? null
              : () => widget.onGrade(
                  _picked.join(' ') == widget.ex.marathi,
                  note: widget.ex.marathi,
                ),
        ),
    ],
  );
}

/// The faint rules the assembled words sit on.
class _RulePainter extends CustomPainter {
  final double rowHeight;
  final int rows;
  _RulePainter({required this.rowHeight, required this.rows});

  @override
  void paint(Canvas canvas, Size size) {
    final p = Paint()
      ..color = Boli.sand
      ..strokeWidth = 2;
    for (int i = 0; i < rows; i++) {
      final y = rowHeight * (i + 1) - 6;
      canvas.drawLine(Offset(0, y), Offset(size.width, y), p);
    }
  }

  @override
  bool shouldRepaint(_RulePainter old) => false;
}

/// A word key. Solid bottom edge so it reads as physically pressable.
class _Tile extends StatefulWidget {
  final String label;
  final VoidCallback? onTap;
  const _Tile({required this.label, this.onTap});
  @override
  State<_Tile> createState() => _TileState();
}

class _TileState extends State<_Tile> {
  bool _down = false;

  @override
  Widget build(BuildContext context) {
    final on = widget.onTap != null;
    return GestureDetector(
      onTapDown: on ? (_) => setState(() => _down = true) : null,
      onTapUp: on ? (_) => setState(() => _down = false) : null,
      onTapCancel: on ? () => setState(() => _down = false) : null,
      onTap: widget.onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 70),
        transform: Matrix4.translationValues(0, _down ? 3 : 0, 0),
        height: 46,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        decoration: BoxDecoration(
          color: Boli.paper,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: Boli.sand, width: 2),
          boxShadow: _down
              ? null
              : [
                  const BoxShadow(
                    color: Boli.sand,
                    offset: Offset(0, 3),
                    blurRadius: 0,
                  ),
                ],
        ),
        // A Container with `alignment` set and no width expands to fill its
        // constraints, which inside a Wrap means full width. Sizing to the word
        // instead is what makes the tiles flow.
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [Text(widget.label, style: Boli.head(21, weight: 600))],
        ),
      ),
    );
  }
}

// ------------------------------------------------------------------ match ---

class _Match extends StatefulWidget {
  final Exercise ex;
  final bool locked;
  final void Function(bool, {String note}) onGrade;
  const _Match({required this.ex, required this.locked, required this.onGrade});
  @override
  State<_Match> createState() => _MatchState();
}

class _MatchState extends State<_Match> {
  late final List<String> _left = [for (final p in widget.ex.pairs) p[0]];
  late final List<String> _right = [for (final p in widget.ex.pairs) p[1]]
    ..shuffle();
  final Set<String> _done = {};
  String? _l, _r, _flash;

  void _check() {
    if (_l == null || _r == null) return;
    if (widget.ex.pairs.any((p) => p[0] == _l && p[1] == _r)) {
      setState(() {
        _done.addAll([_l!, _r!]);
        _l = null;
        _r = null;
      });
      if (_done.length == widget.ex.pairs.length * 2) widget.onGrade(true);
    } else {
      setState(() => _flash = _r);
      Future.delayed(const Duration(milliseconds: 400), () {
        if (mounted) {
          setState(() {
            _flash = null;
            _l = null;
            _r = null;
          });
        }
      });
    }
  }

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _Instruction(
        widget.ex.prompt,
        subtext: widget.ex.promptL1.isNotEmpty
            ? widget.ex.promptL1
            : 'जोड्या लावा (Match the pairs)',
      ),
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
                    on: _l == w,
                    wrong: false,
                    big: true,
                    onTap: _done.contains(w) || widget.locked
                        ? null
                        : () {
                            setState(() => _l = w);
                            _check();
                          },
                  ),
              ],
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              children: [
                for (final w in _right)
                  _MatchTile(
                    label: w,
                    done: _done.contains(w),
                    on: _r == w,
                    wrong: _flash == w,
                    big: false,
                    onTap: _done.contains(w) || widget.locked
                        ? null
                        : () {
                            setState(() => _r = w);
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

class _MatchTile extends StatelessWidget {
  final String label;
  final bool done, on, wrong, big;
  final VoidCallback? onTap;
  const _MatchTile({
    required this.label,
    required this.done,
    required this.on,
    required this.wrong,
    required this.big,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    var border = Boli.sand, fill = Boli.paper, text = Boli.ink;
    if (done) {
      border = Boli.leaf.withValues(alpha: .4);
      fill = Boli.leaf.withValues(alpha: .09);
      text = Boli.leaf;
    } else if (wrong) {
      border = Boli.madder;
      fill = Boli.madder.withValues(alpha: .1);
    } else if (on) {
      border = Boli.marigold;
      fill = Boli.marigold.withValues(alpha: .13);
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: GestureDetector(
        onTap: onTap,
        child: AnimatedOpacity(
          duration: const Duration(milliseconds: 240),
          opacity: done ? .5 : 1,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 170),
            height: Boli.tap + 6,
            alignment: Alignment.center,
            padding: const EdgeInsets.symmetric(horizontal: 10),
            decoration: BoxDecoration(
              color: fill,
              border: Border.all(color: border, width: 2.5),
              borderRadius: BorderRadius.circular(14),
            ),
            child: Text(
              label,
              textAlign: TextAlign.center,
              style: big
                  ? Boli.head(22, weight: 600, color: text)
                  : Boli.body(16.5, weight: FontWeight.w600, color: text),
            ),
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------- verdict ---

/// Replaces the springing feedback bar. Quiet, informational, and it never
/// blocks: "Keep going" is the only action either way.
class _Verdict extends StatelessWidget {
  final bool correct;
  final String note;
  final VoidCallback onNext;
  const _Verdict({
    required this.correct,
    required this.note,
    required this.onNext,
  });

  @override
  Widget build(BuildContext context) {
    final c = correct ? Boli.leaf : Boli.terracotta;
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: 1),
      duration: const Duration(milliseconds: 260),
      curve: Curves.easeOutCubic,
      builder: (_, v, child) => Transform.translate(
        offset: Offset(0, (1 - v) * 90),
        child: Opacity(opacity: v, child: child),
      ),
      child: Container(
        width: double.infinity,
        decoration: BoxDecoration(
          color: Boli.paper,
          border: Border(top: BorderSide(color: c, width: 3)),
        ),
        padding: const EdgeInsets.fromLTRB(20, 14, 20, 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(
                  correct
                      ? Icons.check_circle_rounded
                      : Icons.replay_circle_filled_rounded,
                  color: c,
                  size: 26,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    correct ? 'You can use this' : 'Saved for review',
                    style: Boli.body(16.5, weight: FontWeight.w800, color: c),
                  ),
                ),
              ],
            ),
            if (note.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 4, left: 36),
                child: Text(note, style: Boli.body(15, color: Boli.inkSoft)),
              ),
            const SizedBox(height: 12),
            BigButton(label: 'Keep going', color: c, onTap: onNext),
          ],
        ),
      ),
    );
  }
}
