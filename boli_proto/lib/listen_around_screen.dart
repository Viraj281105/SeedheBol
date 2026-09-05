import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'bridge/boli_bridge.dart';
import 'data.dart';
import 'theme.dart';
import 'widgets.dart';

/// "Listen Around Me" (आजूबाजूचे शब्द)
///
/// Captures or repeats a phrase heard in the workplace target language (e.g. Marathi),
/// transcribes it on-device via IndicConformer ASR, and analyzes it with Gemma 3n E2B:
///   • Meaning in worker's native language (L1)
///   • Communicative intent and emotional tone (warning, urgent, instruction, request)
///   • Key vocabulary breakdown
///   • A natural, practical reply the worker can say immediately
///   • 1-tap FastPitch TTS playback
///   • Spoken reply practice with acoustic pronunciation scoring
class ListenAroundScreen extends StatefulWidget {
  final Job? job;
  final Lang? l1;
  final Lang? l2;

  const ListenAroundScreen({
    super.key,
    this.job,
    this.l1,
    this.l2,
  });

  @override
  State<ListenAroundScreen> createState() => _ListenAroundScreenState();
}

class _ListenAroundScreenState extends State<ListenAroundScreen> with SingleTickerProviderStateMixin {
  static const MethodChannel _asrChannel = MethodChannel('boli/asr');
  final BoliBridge _bridge = BoliBridge.instance;
  final TextEditingController _textController = TextEditingController();

  late AnimationController _pulseController;
  late Animation<double> _pulseAnimation;

  bool _listening = false;
  bool _analyzing = false;
  bool _practicingReply = false;
  bool _showTextInput = false;
  String _statusText = 'खालील बटण दाबून ऐकलेले शब्द बोला किंवा निवडा';

  // Analysis result
  String? _heardPhrase;
  String? _meaningL1;
  String? _toneIntent;
  List<Map<String, String>> _importantWords = [];
  String? _suggestedReplyL2;
  String? _replyMeaningL1;
  String? _replyRoman;
  String? _source;
  int _latencyMs = 0;

  // Reply practice state
  double? _replyScore;
  String? _replyUserSpeech;

  final Set<String> _savedWords = {};

  // Sample phrases frequently overheard in blue-collar workplaces
  final List<String> _samplePhrases = const [
    'हे सामान कुठे ठेवायचं?',
    'तिकडे जाऊ नका, काम चालू आहे!',
    'लवकर करा, वेळ कमी आहे!',
    'पावती दाखवा आणि नोंद करा.',
    'उद्या सकाळी आठ वाजता या.',
  ];

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1000),
    );
    _pulseAnimation = Tween<double>(begin: 1.0, end: 1.22).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );
  }

  @override
  void dispose() {
    _pulseController.dispose();
    _textController.dispose();
    super.dispose();
  }

  double _similarity(String s1, String s2) {
    final x = s1.trim().toLowerCase();
    final y = s2.trim().toLowerCase();
    if (x == y) return 1.0;
    if (x.isEmpty || y.isEmpty) return 0.0;

    final d = List.generate(x.length + 1, (_) => List.filled(y.length + 1, 0));
    for (int i = 0; i <= x.length; i++) {
      d[i][0] = i;
    }
    for (int j = 0; j <= y.length; j++) {
      d[0][j] = j;
    }

    for (int i = 1; i <= x.length; i++) {
      for (int j = 1; j <= y.length; j++) {
        final cost = (x[i - 1] == y[j - 1]) ? 0 : 1;
        d[i][j] = math.min(
          math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
          d[i - 1][j - 1] + cost,
        );
      }
    }
    final maxLen = math.max(x.length, y.length);
    return maxLen == 0 ? 0.5 : (1.0 - d[x.length][y.length] / maxLen).clamp(0.0, 1.0);
  }

  Future<void> _speak(String text) async {
    if (text.trim().isEmpty) return;
    HapticFeedback.selectionClick();
    try {
      await _asrChannel.invokeMethod('speak', {'text': text});
    } catch (_) {}
  }

  Future<void> _captureHeardSpeech() async {
    if (_listening || _analyzing) return;

    setState(() {
      _listening = true;
      _statusText = 'ऐकत आहे… ऐकलेले शब्द आता मोठ्याने बोला (Speaking…)';
    });
    _pulseController.repeat(reverse: true);
    HapticFeedback.selectionClick();

    try {
      final transcript = await _asrChannel.invokeMethod<String>(
        'transcribeMic',
        {'seconds': 5.0},
      ) ?? '';

      _pulseController.stop();
      _pulseController.reset();

      if (!mounted) return;
      if (transcript.trim().isEmpty) {
        setState(() {
          _listening = false;
          _statusText = 'काहीही ऐकू आले नाही — कृपया पुन्हा प्रयत्न करा';
        });
        return;
      }

      await _analyzePhrase(transcript.trim());
    } catch (e) {
      _pulseController.stop();
      _pulseController.reset();
      if (mounted) {
        setState(() {
          _listening = false;
          _statusText = 'मायक्रोफोन एरर: $e';
        });
      }
    }
  }

  Future<void> _analyzePhrase(String phrase) async {
    setState(() {
      _listening = false;
      _analyzing = true;
      _statusText = 'Gemma 3n अर्थ आणि संदर्भ समजून घेत आहे… (Analyzing…)';
      _replyScore = null;
      _replyUserSpeech = null;
    });

    try {
      final res = await _bridge.analyzeHeardPhrase(phrase);
      if (!mounted) return;

      final rawWords = res['important_words'] as List<dynamic>? ?? [];
      final parsedWords = <Map<String, String>>[];
      for (final item in rawWords) {
        if (item is Map) {
          parsedWords.add({
            'word': item['word']?.toString() ?? '',
            'meaning': item['meaning']?.toString() ?? '',
          });
        }
      }

      setState(() {
        _analyzing = false;
        _heardPhrase = res['heard_phrase'] as String? ?? phrase;
        _meaningL1 = res['meaning_l1'] as String? ?? 'अर्थ उपलब्ध नाही';
        _toneIntent = res['tone_intent'] as String? ?? 'सूचना / Instruction';
        _importantWords = parsedWords;
        _suggestedReplyL2 = res['suggested_reply_l2'] as String? ?? 'हो, समजले.';
        _replyMeaningL1 = res['reply_meaning_l1'] as String? ?? 'हाँ, समझ गया।';
        _replyRoman = res['reply_roman'] as String? ?? '';
        _source = res['source'] as String? ?? 'gemma';
        _latencyMs = (res['latency_ms'] as num?)?.toInt() ?? 0;
        _statusText = 'विश्लेषण पूर्ण झाले!';
      });

      // Auto-read the suggested reply so the worker immediately hears how to answer
      if (_suggestedReplyL2 != null && _suggestedReplyL2!.isNotEmpty) {
        _speak(_suggestedReplyL2!);
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _analyzing = false;
          _statusText = 'विश्लेषण करताना त्रुटी आली: $e';
        });
      }
    }
  }

  Future<void> _practiceSuggestedReply() async {
    final target = _suggestedReplyL2;
    if (target == null || target.isEmpty || _practicingReply) return;

    setState(() {
      _practicingReply = true;
      _statusText = 'उत्तर मोठ्याने बोला: "$target"';
    });
    HapticFeedback.selectionClick();

    try {
      final userSpeech = await _asrChannel.invokeMethod<String>(
        'transcribeMic',
        {'seconds': 4.0},
      ) ?? '';

      if (!mounted) return;
      if (userSpeech.trim().isEmpty) {
        setState(() {
          _practicingReply = false;
          _statusText = 'काहीही ऐकू आले नाही — पुन्हा बोला';
        });
        return;
      }

      final score = _similarity(userSpeech, target);

      // Record word attempt and pronunciation in local offline memory
      _bridge.recordWordAttempt(word: target, isCorrect: score >= 0.7);
      _bridge.recordPronunciationWeakness(word: target, score: score);

      setState(() {
        _practicingReply = false;
        _replyScore = score;
        _replyUserSpeech = userSpeech;
        _statusText = score >= 0.8
            ? 'उत्कृष्ट उच्चार! Great job!'
            : (score >= 0.6 ? 'चांगला प्रयत्न! Good effort!' : 'पुन्हा सराव करा.');
      });
    } catch (_) {
      if (mounted) setState(() => _practicingReply = false);
    }
  }

  void _saveWord(String word) {
    if (word.isEmpty || _savedWords.contains(word)) return;
    _bridge.addLearnedVocab(word);
    setState(() => _savedWords.add(word));
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('"$word" शब्दसंग्रहात जोडले! (Saved to memory)'),
        backgroundColor: Boli.peacock,
        duration: const Duration(seconds: 2),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  void _reset() {
    setState(() {
      _heardPhrase = null;
      _meaningL1 = null;
      _toneIntent = null;
      _importantWords = [];
      _suggestedReplyL2 = null;
      _replyMeaningL1 = null;
      _replyRoman = null;
      _replyScore = null;
      _replyUserSpeech = null;
      _textController.clear();
      _statusText = 'नवीन शब्द ऐकण्यासाठी खालील बटण दाबा';
    });
  }

  @override
  Widget build(BuildContext context) {
    final currentJob = widget.job ?? jobs.first;
    final currentL1 = widget.l1 ?? languages[1]; // Hindi
    final currentL2 = widget.l2 ?? languages[0]; // Marathi

    return Scaffold(
      backgroundColor: Boli.paper,
      appBar: AppBar(
        backgroundColor: Boli.paper,
        elevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_rounded, color: Boli.ink),
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text('आजूबाजूचे शब्द', style: Boli.head(19, weight: 700)),
                const SizedBox(width: 8),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                  decoration: BoxDecoration(
                    color: Boli.peacock.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    'LISTEN',
                    style: Boli.label(color: Boli.peacock, size: 10),
                  ),
                ),
              ],
            ),
            Text(
              'Capture & understand workplace speech',
              style: Boli.body(12, color: Boli.inkSoft),
            ),
          ],
        ),
        actions: [
          Container(
            margin: const EdgeInsets.only(right: 16),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: Boli.leaf.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Row(
              children: [
                const Icon(Icons.cloud_off_rounded, size: 14, color: Boli.leaf),
                const SizedBox(width: 4),
                Text(
                  '100% Offline',
                  style: Boli.body(11, weight: FontWeight.w700, color: Boli.leaf),
                ),
              ],
            ),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 10, 20, 24),
          children: [
            // Context header banner
            Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: Boli.peacock.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: Boli.peacock.withValues(alpha: 0.2)),
              ),
              child: Row(
                children: [
                  Icon(currentJob.icon, color: Boli.peacock, size: 22),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '${currentJob.title} · ${currentJob.native}',
                          style: Boli.body(13, weight: FontWeight.w700, color: Boli.ink),
                        ),
                        Text(
                          'Overheard ${currentL2.native} → Instant ${currentL1.native} understanding',
                          style: Boli.body(11.5, color: Boli.inkSoft),
                        ),
                      ],
                    ),
                  ),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                    decoration: BoxDecoration(
                      color: Boli.paper,
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: Boli.sand),
                    ),
                    child: Text(
                      'Gemma 3n E2B',
                      style: Boli.label(color: Boli.inkSoft, size: 10),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 18),

            // If an analysis is active, show the detailed result card
            if (_heardPhrase != null) ...[
              _buildAnalysisResultCard(),
              const SizedBox(height: 20),
            ] else ...[
              // Prompt instruction
              Text(
                'कामाच्या ठिकाणी काय ऐकले?',
                style: Boli.head(22, weight: 700),
              ),
              const SizedBox(height: 4),
              Text(
                'What did you overhear? Speak or repeat the phrase you just heard from a supervisor, coworker, or customer.',
                style: Boli.body(14, color: Boli.inkSoft),
              ),
              const SizedBox(height: 24),

              // Big interactive recording microphone
              Center(
                child: Column(
                  children: [
                    AnimatedBuilder(
                      animation: _pulseAnimation,
                      builder: (context, child) {
                        return Transform.scale(
                          scale: _listening ? _pulseAnimation.value : 1.0,
                          child: GestureDetector(
                            onTap: _captureHeardSpeech,
                            child: Container(
                              width: 110,
                              height: 110,
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                color: _listening ? Boli.terracotta : Boli.peacock,
                                boxShadow: [
                                  BoxShadow(
                                    color: (_listening ? Boli.terracotta : Boli.peacock)
                                        .withValues(alpha: _listening ? 0.45 : 0.25),
                                    blurRadius: _listening ? 24 : 14,
                                    spreadRadius: _listening ? 4 : 0,
                                    offset: const Offset(0, 4),
                                  ),
                                ],
                              ),
                              child: Center(
                                child: Icon(
                                  _listening ? Icons.mic_rounded : Icons.hearing_rounded,
                                  size: 48,
                                  color: Boli.cream,
                                ),
                              ),
                            ),
                          ),
                        );
                      },
                    ),
                    const SizedBox(height: 14),
                    Text(
                      _listening ? 'ऐकत आहे… (Speak now)' : 'बटण दाबा आणि ऐकलेले बोला',
                      style: Boli.body(15, weight: FontWeight.w700, color: Boli.ink),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      _statusText,
                      textAlign: TextAlign.center,
                      style: Boli.body(13, color: _listening ? Boli.terracotta : Boli.inkSoft),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 32),

              // Quick sample overheard phrases
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const SectionHead('किंवा कामावरील शब्द निवडा · QUICK SAMPLES'),
                  TextButton.icon(
                    onPressed: () => setState(() => _showTextInput = !_showTextInput),
                    icon: Icon(_showTextInput ? Icons.keyboard_hide_rounded : Icons.edit_note_rounded, size: 16),
                    label: Text(_showTextInput ? 'Hide' : 'Type'),
                    style: TextButton.styleFrom(
                      foregroundColor: Boli.peacock,
                      textStyle: Boli.body(12, weight: FontWeight.w700),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),

              if (_showTextInput) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: Boli.paper,
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(color: Boli.sand),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: _textController,
                          style: Boli.body(14),
                          decoration: InputDecoration(
                            hintText: 'उदा. हे सामान आत ठेवा...',
                            hintStyle: Boli.body(14, color: Boli.inkSoft),
                            border: InputBorder.none,
                            isDense: true,
                          ),
                          onSubmitted: (val) {
                            if (val.trim().isNotEmpty) _analyzePhrase(val.trim());
                          },
                        ),
                      ),
                      IconButton(
                        icon: const Icon(Icons.arrow_forward_rounded, color: Boli.peacock),
                        onPressed: () {
                          final text = _textController.text.trim();
                          if (text.isNotEmpty) _analyzePhrase(text);
                        },
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 12),
              ],

              for (final phrase in _samplePhrases) ...[
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: InkWell(
                    onTap: () => _analyzePhrase(phrase),
                    borderRadius: BorderRadius.circular(14),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                      decoration: BoxDecoration(
                        color: Boli.cream,
                        borderRadius: BorderRadius.circular(14),
                        border: Border.all(color: Boli.sand.withValues(alpha: 0.6)),
                      ),
                      child: Row(
                        children: [
                          Container(
                            padding: const EdgeInsets.all(6),
                            decoration: BoxDecoration(
                              color: Boli.peacock.withValues(alpha: 0.12),
                              borderRadius: BorderRadius.circular(8),
                            ),
                            child: const Icon(Icons.record_voice_over_rounded, size: 16, color: Boli.peacock),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Text(
                              phrase,
                              style: Boli.body(15, weight: FontWeight.w600, color: Boli.ink),
                            ),
                          ),
                          const Icon(Icons.chevron_right_rounded, color: Boli.inkSoft, size: 20),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
            ],

            if (_analyzing) ...[
              const SizedBox(height: 24),
              Center(
                child: Column(
                  children: [
                    const SizedBox(
                      width: 36,
                      height: 36,
                      child: CircularProgressIndicator(color: Boli.peacock, strokeWidth: 3),
                    ),
                    const SizedBox(height: 14),
                    Text(
                      'Gemma 3n संदर्भ समजून घेत आहे…',
                      style: Boli.head(16, weight: 700),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Analyzing intent, tone & workplace response…',
                      style: Boli.body(13, color: Boli.inkSoft),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildAnalysisResultCard() {
    final tone = _toneIntent ?? 'सूचना / Instruction';
    Color toneColor = Boli.peacock;
    IconData toneIcon = Icons.info_outline_rounded;

    final lowerTone = tone.toLowerCase();
    if (lowerTone.contains('ताकीद') || lowerTone.contains('urgent') || lowerTone.contains('warning') || lowerTone.contains('चेतावणी')) {
      toneColor = Boli.madder;
      toneIcon = Icons.warning_amber_rounded;
    } else if (lowerTone.contains('विनंती') || lowerTone.contains('request')) {
      toneColor = Boli.marigold;
      toneIcon = Icons.question_answer_outlined;
    } else if (lowerTone.contains('विचारणा') || lowerTone.contains('inquiry')) {
      toneColor = Boli.peacock;
      toneIcon = Icons.help_outline_rounded;
    }

    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: Boli.paper,
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: Boli.peacock.withValues(alpha: 0.35), width: 1.5),
        boxShadow: Boli.lift(y: 4, blur: 14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header: Overheard badge + Tone badge
          Row(
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: Boli.peacock.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.hearing_rounded, size: 14, color: Boli.peacock),
                    const SizedBox(width: 4),
                    Text('ऐकलेले वाक्य · OVERHEARD', style: Boli.label(color: Boli.peacock, size: 10.5)),
                  ],
                ),
              ),
              const Spacer(),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
                decoration: BoxDecoration(
                  color: toneColor.withValues(alpha: 0.14),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(toneIcon, size: 13, color: toneColor),
                    const SizedBox(width: 4),
                    Text(
                      tone,
                      style: Boli.body(11.5, weight: FontWeight.w700, color: toneColor),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),

          // The Heard Phrase
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Text(
                  _heardPhrase ?? '',
                  style: Boli.head(22, weight: 700),
                ),
              ),
              IconButton(
                icon: const Icon(Icons.volume_up_rounded, color: Boli.peacock),
                tooltip: 'Listen to phrase',
                onPressed: () => _speak(_heardPhrase ?? ''),
              ),
            ],
          ),
          const SizedBox(height: 12),
          HandloomBorder(color: Boli.sand, height: 6, dense: true),
          const SizedBox(height: 14),

          // Meaning in Native Language (L1)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: Boli.cream,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: Boli.sand),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(Icons.translate_rounded, size: 15, color: Boli.inkSoft),
                    const SizedBox(width: 6),
                    Text(
                      'याचा अर्थ असा होतो · Meaning',
                      style: Boli.label(color: Boli.inkSoft, size: 11),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  _meaningL1 ?? '',
                  style: Boli.body(16, weight: FontWeight.w600, color: Boli.ink),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // Important Words Breakdown
          if (_importantWords.isNotEmpty) ...[
            Text('महत्वाचे शब्द · KEY WORDS', style: Boli.label(color: Boli.inkSoft, size: 11)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final item in _importantWords)
                  GestureDetector(
                    onTap: () => _saveWord(item['word'] ?? ''),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                      decoration: BoxDecoration(
                        color: Boli.paper,
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(
                          color: _savedWords.contains(item['word']) ? Boli.peacock : Boli.sand,
                          width: 1.2,
                        ),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            item['word'] ?? '',
                            style: Boli.body(14, weight: FontWeight.w700, color: Boli.ink),
                          ),
                          const SizedBox(width: 5),
                          Text(
                            '(${item['meaning']})',
                            style: Boli.body(12, color: Boli.inkSoft),
                          ),
                          const SizedBox(width: 6),
                          Icon(
                            _savedWords.contains(item['word']) ? Icons.check_circle_rounded : Icons.bookmark_add_outlined,
                            size: 14,
                            color: _savedWords.contains(item['word']) ? Boli.peacock : Boli.inkSoft,
                          ),
                        ],
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 18),
          ],

          // "तुम्ही काय उत्तर देऊ शकता · How You Can Reply"
          if (_suggestedReplyL2 != null && _suggestedReplyL2!.isNotEmpty) ...[
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Boli.marigold.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: Boli.marigold.withValues(alpha: 0.35)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.reply_rounded, size: 16, color: Boli.terracotta),
                      const SizedBox(width: 6),
                      Text(
                        'तुम्ही काय उत्तर देऊ शकता · NATURAL REPLY',
                        style: Boli.label(color: Boli.terracotta, size: 11),
                      ),
                      const Spacer(),
                      IconButton(
                        icon: const Icon(Icons.volume_up_rounded, color: Boli.terracotta, size: 20),
                        tooltip: 'Listen to reply',
                        padding: EdgeInsets.zero,
                        constraints: const BoxConstraints(),
                        onPressed: () => _speak(_suggestedReplyL2!),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(
                    _suggestedReplyL2!,
                    style: Boli.head(19, weight: 700, color: Boli.ink),
                  ),
                  if (_replyRoman != null && _replyRoman!.isNotEmpty) ...[
                    const SizedBox(height: 2),
                    Text(
                      _replyRoman!,
                      style: Boli.body(13, color: Boli.terracotta, weight: FontWeight.w600),
                    ),
                  ],
                  if (_replyMeaningL1 != null && _replyMeaningL1!.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(
                      'हिन्दी: ${_replyMeaningL1!}',
                      style: Boli.body(13.5, color: Boli.inkSoft),
                    ),
                  ],
                  const SizedBox(height: 14),

                  // Practice Saying This Button
                  Row(
                    children: [
                      Expanded(
                        child: ElevatedButton.icon(
                          onPressed: _practiceSuggestedReply,
                          icon: Icon(
                            _practicingReply ? Icons.mic_rounded : Icons.mic_none_rounded,
                            size: 18,
                          ),
                          label: Text(_practicingReply ? 'ऐकत आहे…' : 'सराव करा · Practice Saying This'),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: _practicingReply ? Boli.terracotta : Boli.peacock,
                            foregroundColor: Boli.cream,
                            elevation: 0,
                            padding: const EdgeInsets.symmetric(vertical: 10),
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                            textStyle: Boli.body(13, weight: FontWeight.w700),
                          ),
                        ),
                      ),
                    ],
                  ),

                  // Pronunciation score badge if user practiced
                  if (_replyScore != null) ...[
                    const SizedBox(height: 10),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                      decoration: BoxDecoration(
                        color: _replyScore! >= 0.75
                            ? Boli.leaf.withValues(alpha: 0.15)
                            : (_replyScore! >= 0.55
                                ? Boli.marigold.withValues(alpha: 0.18)
                                : Boli.madder.withValues(alpha: 0.12)),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Row(
                        children: [
                          Icon(
                            _replyScore! >= 0.75
                                ? Icons.check_circle_rounded
                                : (_replyScore! >= 0.55 ? Icons.star_half_rounded : Icons.info_outline_rounded),
                            size: 16,
                            color: _replyScore! >= 0.75
                                ? Boli.leaf
                                : (_replyScore! >= 0.55 ? Boli.marigold : Boli.madder),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  'उच्चार अचूकता: ${(_replyScore! * 100).round()}% · ${_replyScore! >= 0.75 ? "उत्कृष्ट!" : "चांगला प्रयत्न!"}',
                                  style: Boli.body(
                                    12.5,
                                    weight: FontWeight.w700,
                                    color: _replyScore! >= 0.75
                                        ? Boli.leaf
                                        : (_replyScore! >= 0.55 ? Boli.ink : Boli.madder),
                                  ),
                                ),
                                if (_replyUserSpeech != null && _replyUserSpeech!.isNotEmpty)
                                  Text(
                                    'तुम्ही म्हणालात: "$_replyUserSpeech"',
                                    style: Boli.body(11, color: Boli.inkSoft),
                                  ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(height: 18),
          ],

          // Footer: Listen to another phrase button
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'Source: ${_source ?? "on-device"} (${_latencyMs}ms)',
                style: Boli.body(11, color: Boli.inkSoft),
              ),
              OutlinedButton.icon(
                onPressed: _reset,
                icon: const Icon(Icons.refresh_rounded, size: 16),
                label: const Text('दुसरे वाक्य ऐका · Next'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: Boli.ink,
                  side: const BorderSide(color: Boli.sand),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                  textStyle: Boli.body(12, weight: FontWeight.w600),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
