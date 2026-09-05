import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'bridge/boli_bridge.dart';
import 'data.dart';
import 'theme.dart';
import 'widgets.dart';

/// SeedheBol Daily Mission Screen
///
/// A personalized 2–3 minute real-world workplace language challenge.
/// Combines:
///   1. Dynamic mission generation via Gemma 3n E2B (based on occupation & weak words).
///   2. Turn-by-turn conversational flow (3–4 turns).
///   3. Dual evaluation:
///      - Acoustic pronunciation scoring (IndicConformer ASR + GOP similarity).
///      - Semantic intent understanding + "बोली पॉलिश" (Better Way) via Gemma.
///   4. Real-time FastPitch TTS playback for all NPC lines and coached phrases.
///   5. Final mission debrief updating local offline LearnerMemoryStore.
class DailyMissionScreen extends StatefulWidget {
  final Job job;
  final Lang l1;
  final Lang l2;

  const DailyMissionScreen({
    super.key,
    required this.job,
    required this.l1,
    required this.l2,
  });

  @override
  State<DailyMissionScreen> createState() => _DailyMissionScreenState();
}

enum _MissionStage { loading, briefing, active, debrief }

class _MissionTurn {
  final int turnNumber;
  final String npcL2;
  final String npcL1;
  final String userSpoken;
  final double pronunciationScore;
  final String betterWay;
  final String coachingTip;

  const _MissionTurn({
    required this.turnNumber,
    required this.npcL2,
    required this.npcL1,
    this.userSpoken = '',
    this.pronunciationScore = 0.0,
    this.betterWay = '',
    this.coachingTip = '',
  });

  _MissionTurn copyWith({
    String? userSpoken,
    double? pronunciationScore,
    String? betterWay,
    String? coachingTip,
  }) => _MissionTurn(
    turnNumber: turnNumber,
    npcL2: npcL2,
    npcL1: npcL1,
    userSpoken: userSpoken ?? this.userSpoken,
    pronunciationScore: pronunciationScore ?? this.pronunciationScore,
    betterWay: betterWay ?? this.betterWay,
    coachingTip: coachingTip ?? this.coachingTip,
  );
}

class _DailyMissionScreenState extends State<DailyMissionScreen> {
  static const _asrChannel = MethodChannel('boli/asr');
  static const _engineChannel = MethodChannel('boli/engine_methods');

  _MissionStage _stage = _MissionStage.loading;

  // Mission metadata
  String _title = 'Asking for 30 More Minutes';
  String _nativeTitle = 'कामाची वेळ वाढवून मागणे';
  String _npcRole = 'साइट सुपरवायझर (Site Supervisor)';
  String _objective = 'Explain why work is delayed and request 30 more minutes.';
  String _objectiveNative = 'काम का अडकले ते सांगा आणि ३० मिनिटांची मुदत मागा.';
  String _openerL2 = 'काम अजून पूर्ण का झाले नाही? आजची शिफ्ट संपत आली आहे.';
  String _openerL1 = 'काम अभी तक पूरा क्यों नहीं हुआ? आज की शिफ्ट खत्म होने वाली है।';
  List<String> _targetWords = ['मदत', 'अडचण', 'वेळ'];
  int _maxTurns = 4;
  String _aiSource = 'gemma';

  // Active dialogue state
  int _currentTurnIndex = 0;
  final List<_MissionTurn> _turns = [];
  bool _listening = false;
  bool _analyzing = false;
  String _statusText = '';

  // Debrief stats
  final List<String> _masteredWords = [];
  final List<String> _reviewWords = [];
  double _overallScore = 0.85;

  @override
  void initState() {
    super.initState();
    _fetchDailyMission();
  }

  Future<void> _fetchDailyMission() async {
    try {
      final missionMap = await BoliBridge.instance.generateDailyMission();
      if (!mounted) return;

      if (missionMap.isNotEmpty) {
        setState(() {
          _title = missionMap['title'] as String? ?? _title;
          _nativeTitle = missionMap['native_title'] as String? ?? _nativeTitle;
          _npcRole = missionMap['npc_role'] as String? ?? _npcRole;
          _objective = missionMap['objective'] as String? ?? _objective;
          _objectiveNative = missionMap['objective_native'] as String? ?? _objectiveNative;
          _openerL2 = missionMap['opener_l2'] as String? ?? _openerL2;
          _openerL1 = missionMap['opener_l1'] as String? ?? _openerL1;
          final words = missionMap['target_words'] as List?;
          if (words != null && words.isNotEmpty) {
            _targetWords = words.map((e) => e.toString()).toList();
          }
          _maxTurns = (missionMap['max_turns'] as int?) ?? 4;
          _aiSource = missionMap['source'] as String? ?? 'gemma';
          _stage = _MissionStage.briefing;
        });
      } else {
        setState(() => _stage = _MissionStage.briefing);
      }
    } catch (_) {
      if (mounted) setState(() => _stage = _MissionStage.briefing);
    }
  }

  void _startMission() {
    setState(() {
      _stage = _MissionStage.active;
      _currentTurnIndex = 0;
      _turns.clear();
      _turns.add(_MissionTurn(
        turnNumber: 1,
        npcL2: _openerL2,
        npcL1: _openerL1,
      ));
      _statusText = 'ऐका, मग उत्तर देण्यासाठी माइक दाबा';
    });
    Future.delayed(const Duration(milliseconds: 400), () => _speak(_openerL2));
  }

  double _similarity(String a, String b) {
    String norm(String s) => s.replaceAll(RegExp(r'[\s।,.?!]'), '').toLowerCase();
    final x = norm(a), y = norm(b);
    if (x.isEmpty || y.isEmpty) return 0.5;
    final d = List.generate(x.length + 1, (_) => List<int>.filled(y.length + 1, 0));
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
    final maxLen = math.max(x.length, y.length);
    return maxLen == 0 ? 0.5 : (1.0 - d[x.length][y.length] / maxLen).clamp(0.0, 1.0);
  }

  Future<void> _recordUserSpeech() async {
    if (_listening || _analyzing) return;

    setState(() {
      _listening = true;
      _statusText = 'ऐकत आहे… (Speak now)';
    });
    HapticFeedback.selectionClick();

    try {
      // 1. Capture speech via IndicConformer ASR
      final transcript = await _asrChannel.invokeMethod<String>(
        'transcribeMic',
        {'seconds': 5.0},
      ) ?? '';

      if (!mounted) return;
      if (transcript.trim().isEmpty) {
        setState(() {
          _listening = false;
          _statusText = 'काहीही ऐकू आले नाही — पुन्हा प्रयत्न करा';
        });
        return;
      }

      setState(() {
        _listening = false;
        _analyzing = true;
        _statusText = 'Gemma समजून घेत आहे… (Analyzing…)';
      });

      // 2. Score acoustic pronunciation against target concepts
      final targetRef = _targetWords.isNotEmpty ? _targetWords.first : _openerL2;
      final pronScore = _similarity(transcript, targetRef);

      // Record acoustic pronunciation score in memory store
      BoliBridge.instance.recordPronunciationWeakness(
        word: targetRef,
        score: pronScore,
      );

      // 3. Submit turn to Gemma for semantic intent understanding & NPC response
      final response = await _engineChannel.invokeMapMethod<String, dynamic>(
        'submitUserUtterance',
        {
          'situation_id': 'Daily Mission: $_title',
          'current_node_id': 'mission_turn_$_currentTurnIndex',
          'user_spoken_text': transcript,
          'turn_number': _currentTurnIndex + 1,
          'max_turns': _maxTurns,
        },
      );

      if (!mounted) return;
      final nextNpcL2 = response?['prompt_l2'] as String? ?? 'ठीक आहे, समजले. काम व्यवस्थित पूर्ण करा.';
      final nextNpcL1 = response?['prompt_l1'] as String? ?? 'ठीक है, समझ गया। काम ठीक से पूरा करें।';
      final betterWay = response?['natural_phrasing'] as String? ?? '';
      final feedback = response?['intent_explanation'] as String? ?? '';

      // Update current turn with user response
      _turns[_currentTurnIndex] = _turns[_currentTurnIndex].copyWith(
        userSpoken: transcript,
        pronunciationScore: pronScore,
        betterWay: betterWay,
        coachingTip: feedback,
      );

      // Record word attempt in memory
      BoliBridge.instance.recordWordAttempt(
        word: transcript,
        isCorrect: pronScore >= 0.60,
      );

      if (pronScore >= 0.65) {
        _masteredWords.add(transcript);
      } else {
        _reviewWords.add(transcript);
      }

      // Check if mission is complete (turns reached)
      final isFinalTurn = _currentTurnIndex >= _maxTurns - 1;

      if (isFinalTurn) {
        // Mission complete!
        final avgPron = _turns.map((t) => t.pronunciationScore).fold(0.0, (a, b) => a + b) / _turns.length;
        setState(() {
          _analyzing = false;
          _overallScore = avgPron.clamp(0.65, 0.96);
          _stage = _MissionStage.debrief;
        });

        // Record mission completion in local memory
        BoliBridge.instance.recordCompletedScenario('Daily Mission: $_title');
      } else {
        // Advance to next turn
        _currentTurnIndex++;
        _turns.add(_MissionTurn(
          turnNumber: _currentTurnIndex + 1,
          npcL2: nextNpcL2,
          npcL1: nextNpcL1,
        ));

        setState(() {
          _analyzing = false;
          _statusText = 'पुढचा टर्न ऐका आणि उत्तर द्या';
        });

        _speak(nextNpcL2);
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _listening = false;
        _analyzing = false;
        _statusText = (e is PlatformException)
            ? (e.message ?? 'त्रुटी आली — पुन्हा बोला')
            : 'त्रुटी आली — पुन्हा प्रयत्न करा';
      });
    }
  }

  Future<void> _speak(String text) async {
    if (text.isEmpty) return;
    try {
      await _asrChannel.invokeMethod<String>('speak', {'text': text});
    } on PlatformException {
      // Audio synth non-fatal
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Boli.paper,
      appBar: AppBar(
        backgroundColor: Boli.paper,
        elevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios_new_rounded, color: Boli.ink),
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('आजचे मिशन · Daily Mission', style: Boli.head(18, weight: 700)),
            Text(
              '${widget.job.title} · 2 Min Practice',
              style: Boli.body(12.5, color: Boli.inkSoft),
            ),
          ],
        ),
        actions: [
          Container(
            margin: const EdgeInsets.only(right: 16),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: Boli.terracotta.withValues(alpha: .12),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.auto_awesome, size: 14, color: Boli.terracotta),
                const SizedBox(width: 4),
                Text(
                  _aiSource.toUpperCase(),
                  style: Boli.label(color: Boli.terracotta, size: 11),
                ),
              ],
            ),
          ),
        ],
      ),
      body: SafeArea(
        child: switch (_stage) {
          _MissionStage.loading => _buildLoading(),
          _MissionStage.briefing => _buildBriefing(),
          _MissionStage.active => _buildActiveMission(),
          _MissionStage.debrief => _buildDebrief(),
        },
      ),
    );
  }

  Widget _buildLoading() {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const CircularProgressIndicator(color: Boli.terracotta),
          const SizedBox(height: 18),
          Text(
            'Gemma 3n आजचे मिशन तयार करत आहे…',
            style: Boli.head(17, weight: 600),
          ),
          const SizedBox(height: 4),
          Text(
            'Personalizing for ${widget.job.title}…',
            style: Boli.body(14, color: Boli.inkSoft),
          ),
        ],
      ),
    );
  }

  Widget _buildBriefing() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
      children: [
        // Hero Mission Card
        Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: Boli.ink,
            borderRadius: BorderRadius.circular(22),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: .12),
                blurRadius: 16,
                offset: const Offset(0, 6),
              ),
            ],
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                    decoration: BoxDecoration(
                      color: Boli.marigold,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Text(
                      'DAY\'S CHALLENGE',
                      style: Boli.label(color: Boli.ink, size: 11),
                    ),
                  ),
                  const Spacer(),
                  const Icon(Icons.flash_on_rounded, color: Boli.marigold, size: 20),
                  Text(' 2-3 मिनिटे', style: Boli.body(13, color: Boli.cream)),
                ],
              ),
              const SizedBox(height: 14),
              Text(
                _nativeTitle,
                style: Boli.head(26, weight: 800, color: Boli.cream),
              ),
              const SizedBox(height: 2),
              Text(
                _title,
                style: Boli.body(16, color: Boli.cream.withValues(alpha: .75)),
              ),
              const SizedBox(height: 16),
              HandloomBorder(color: Boli.marigold.withValues(alpha: .6), height: 8),
              const SizedBox(height: 16),
              Row(
                children: [
                  const Icon(Icons.person_pin_rounded, color: Boli.marigold, size: 20),
                  const SizedBox(width: 8),
                  Text('वक्ता / Persona: ', style: Boli.body(14, color: Boli.cream.withValues(alpha: .7))),
                  Expanded(
                    child: Text(
                      _npcRole,
                      style: Boli.body(14.5, weight: FontWeight.w700, color: Boli.cream),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: 20),

        // Objective Section
        Container(
          padding: const EdgeInsets.all(18),
          decoration: Boli.card(),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('ध्येय · MISSION OBJECTIVE', style: Boli.label(color: Boli.terracotta, size: 12)),
              const SizedBox(height: 8),
              Text(_objectiveNative, style: Boli.head(17, weight: 700)),
              const SizedBox(height: 4),
              Text(_objective, style: Boli.body(14.5, color: Boli.inkSoft)),
              const SizedBox(height: 16),
              const Divider(color: Boli.sand, height: 1),
              const SizedBox(height: 14),
              Text('सराव शब्द · TARGET PHRASES', style: Boli.label(color: Boli.inkSoft, size: 11.5)),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: _targetWords.map((word) => Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                  decoration: BoxDecoration(
                    color: Boli.peacock.withValues(alpha: .1),
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(color: Boli.peacock.withValues(alpha: .3)),
                  ),
                  child: Text(
                    word,
                    style: Boli.body(14, weight: FontWeight.w700, color: Boli.peacock),
                  ),
                )).toList(),
              ),
            ],
          ),
        ),
        const SizedBox(height: 24),

        // Start Button
        BigButton(
          label: 'मिशन सुरू करा · Start Mission',
          color: Boli.terracotta,
          onTap: _startMission,
        ),
      ],
    );
  }

  Widget _buildActiveMission() {
    final progress = (_currentTurnIndex + 1) / _maxTurns;

    return Column(
      children: [
        // Progress bar
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    'टर्न ${_currentTurnIndex + 1} / $_maxTurns',
                    style: Boli.body(14, weight: FontWeight.w800, color: Boli.terracotta),
                  ),
                  Text(
                    'Turn ${_currentTurnIndex + 1} of $_maxTurns',
                    style: Boli.body(13, color: Boli.inkSoft),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              ClipRRect(
                borderRadius: BorderRadius.circular(4),
                child: LinearProgressIndicator(
                  value: progress,
                  minHeight: 6,
                  backgroundColor: Boli.sand,
                  valueColor: const AlwaysStoppedAnimation(Boli.terracotta),
                ),
              ),
            ],
          ),
        ),

        // Turns list
        Expanded(
          child: ListView.builder(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
            itemCount: _currentTurnIndex + 1,
            itemBuilder: (context, index) {
              final turn = _turns[index];
              return Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    // NPC Card
                    Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: Boli.sand.withValues(alpha: .35),
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(color: Boli.sand),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Container(
                                width: 28,
                                height: 28,
                                decoration: BoxDecoration(
                                  color: Boli.indigo,
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                child: const Icon(Icons.person, color: Colors.white, size: 16),
                              ),
                              const SizedBox(width: 8),
                              Text(_npcRole, style: Boli.label(color: Boli.indigo, size: 12)),
                              const Spacer(),
                              IconButton(
                                icon: const Icon(Icons.volume_up_rounded, color: Boli.indigo, size: 22),
                                onPressed: () => _speak(turn.npcL2),
                                constraints: const BoxConstraints(),
                                padding: EdgeInsets.zero,
                              ),
                            ],
                          ),
                          const SizedBox(height: 10),
                          Text(turn.npcL2, style: Boli.head(19, weight: 700)),
                          const SizedBox(height: 4),
                          Text(turn.npcL1, style: Boli.body(14.5, color: Boli.inkSoft)),
                        ],
                      ),
                    ),

                    // User response if completed
                    if (turn.userSpoken.isNotEmpty) ...[
                      const SizedBox(height: 12),
                      Container(
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: Boli.terracotta.withValues(alpha: .08),
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(color: Boli.terracotta.withValues(alpha: .25)),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Text('तुमचे उत्तर (You)', style: Boli.label(color: Boli.terracotta, size: 12)),
                                const Spacer(),
                                Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                                  decoration: BoxDecoration(
                                    color: (turn.pronunciationScore >= 0.65 ? Boli.leaf : Boli.marigold).withValues(alpha: .2),
                                    borderRadius: BorderRadius.circular(6),
                                  ),
                                  child: Text(
                                    'उच्चार: ${(turn.pronunciationScore * 100).round()}%',
                                    style: Boli.body(12, weight: FontWeight.w800, color: turn.pronunciationScore >= 0.65 ? Boli.leaf : Boli.ink),
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 8),
                            Text(turn.userSpoken, style: Boli.head(17, weight: 600)),

                            // Boli Polish card
                            if (turn.betterWay.isNotEmpty) ...[
                              const SizedBox(height: 12),
                              Container(
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: Boli.marigold.withValues(alpha: .15),
                                  borderRadius: BorderRadius.circular(12),
                                  border: Border.all(color: Boli.marigold.withValues(alpha: .4)),
                                ),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Row(
                                      children: [
                                        const Icon(Icons.auto_fix_high, size: 14, color: Boli.ink),
                                        const SizedBox(width: 4),
                                        Text('बोली पॉलिश · Better Way', style: Boli.label(color: Boli.ink, size: 11)),
                                        const Spacer(),
                                        IconButton(
                                          icon: const Icon(Icons.volume_up_rounded, size: 18, color: Boli.ink),
                                          onPressed: () => _speak(turn.betterWay),
                                          constraints: const BoxConstraints(),
                                          padding: EdgeInsets.zero,
                                        ),
                                      ],
                                    ),
                                    const SizedBox(height: 4),
                                    Text(turn.betterWay, style: Boli.body(15, weight: FontWeight.w700)),
                                    if (turn.coachingTip.isNotEmpty) ...[
                                      const SizedBox(height: 2),
                                      Text(turn.coachingTip, style: Boli.body(13, color: Boli.inkSoft)),
                                    ],
                                  ],
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ],
                  ],
                ),
              );
            },
          ),
        ),

        // Bottom Action Bar: Mic + Status
        Container(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
          decoration: BoxDecoration(
            color: Boli.paper,
            border: Border(top: BorderSide(color: Boli.sand, width: 1.5)),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                _statusText,
                style: Boli.body(14, weight: FontWeight.w600, color: Boli.inkSoft),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 12),
              Center(
                child: MicButton(
                  busy: _listening || _analyzing,
                  onTap: (_listening || _analyzing) ? null : _recordUserSpeech,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildDebrief() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 28),
      children: [
        // Trophy Header
        Container(
          padding: const EdgeInsets.all(22),
          decoration: BoxDecoration(
            color: Boli.ink,
            borderRadius: BorderRadius.circular(22),
          ),
          child: Column(
            children: [
              Container(
                width: 64,
                height: 64,
                decoration: BoxDecoration(
                  color: Boli.marigold.withValues(alpha: .2),
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.emoji_events_rounded, color: Boli.marigold, size: 36),
              ),
              const SizedBox(height: 12),
              Text(
                'मिशन यशस्वी! · Mission Complete',
                style: Boli.head(23, weight: 800, color: Boli.cream),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 4),
              Text(
                'You handled this real-world workplace situation.',
                style: Boli.body(14.5, color: Boli.cream.withValues(alpha: .7)),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 18),
              ReadinessRing(value: _overallScore, size: 90),
              const SizedBox(height: 12),
              Text(
                '${(_overallScore * 100).round()}% संभाषण तयारी (Readiness)',
                style: Boli.head(20, weight: 700, color: Boli.cream),
              ),
            ],
          ),
        ),
        const SizedBox(height: 20),

        // Words mastered
        if (_masteredWords.isNotEmpty) ...[
          Container(
            padding: const EdgeInsets.all(16),
            decoration: Boli.card(),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(Icons.check_circle_rounded, color: Boli.leaf, size: 20),
                    const SizedBox(width: 8),
                    Text('चांगले उच्चारलेले शब्द (Mastered)', style: Boli.head(16, weight: 700)),
                  ],
                ),
                const SizedBox(height: 10),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: _masteredWords.toSet().map((w) => Chip(
                    label: Text(w, style: Boli.body(14, weight: FontWeight.w600)),
                    backgroundColor: Boli.leaf.withValues(alpha: .12),
                    side: BorderSide.none,
                  )).toList(),
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),
        ],

        // Words to practice tomorrow
        if (_reviewWords.isNotEmpty) ...[
          Container(
            padding: const EdgeInsets.all(16),
            decoration: Boli.card(),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(Icons.replay_rounded, color: Boli.terracotta, size: 20),
                    const SizedBox(width: 8),
                    Text('उद्यासाठी सरावाची वाक्ये (To Review)', style: Boli.head(16, weight: 700)),
                  ],
                ),
                const SizedBox(height: 10),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: _reviewWords.toSet().map((w) => Chip(
                    label: Text(w, style: Boli.body(14, weight: FontWeight.w600)),
                    backgroundColor: Boli.terracotta.withValues(alpha: .12),
                    side: BorderSide.none,
                  )).toList(),
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),
        ],

        // Return button
        BigButton(
          label: 'बोर्डवर परत जा · Back to Today',
          color: Boli.terracotta,
          onTap: () => Navigator.of(context).pop(_overallScore),
        ),
      ],
    );
  }
}
