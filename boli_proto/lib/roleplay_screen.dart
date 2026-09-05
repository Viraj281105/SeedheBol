import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'theme.dart';
import 'widgets.dart';

/// Gemma-powered conversational roleplay screen.
///
/// Roleplay scenarios simulate real situations a migrant worker encounters:
/// talking to a supervisor, ordering materials, explaining work done, etc.
///
/// Enhanced with Gemma 3n E2B:
///   - Semantic understanding: accepts broken grammar & rough pronunciation
///   - Personas: Supervisor, Shopkeeper, Security Guard, Coworker
///   - "Boli Polish" (Better Way): suggests native, polite phrasing for user's turn
///   - Immediate FastPitch TTS audio synthesis
class RoleplayScreen extends StatefulWidget {
  final String scenario;
  final String scenarioNative;

  const RoleplayScreen({
    super.key,
    required this.scenario,
    required this.scenarioNative,
  });

  @override
  State<RoleplayScreen> createState() => _RoleplayScreenState();
}

class _RoleplayPersona {
  final String id;
  final String title;
  final String titleNative;
  final IconData icon;
  final String openerL2;
  final String openerL1;

  const _RoleplayPersona({
    required this.id,
    required this.title,
    required this.titleNative,
    required this.icon,
    required this.openerL2,
    required this.openerL1,
  });
}

const _kPersonas = [
  _RoleplayPersona(
    id: 'supervisor',
    title: 'Supervisor',
    titleNative: 'सुपरवायझर',
    icon: Icons.engineering_rounded,
    openerL2: 'नमस्ते! आज काय काम चालू आहे?',
    openerL1: 'नमस्ते! आज क्या काम चल रहा है?',
  ),
  _RoleplayPersona(
    id: 'shopkeeper',
    title: 'Shopkeeper',
    titleNative: 'दुकानदार',
    icon: Icons.storefront_rounded,
    openerL2: 'नमस्ते, काय सामान पाहिजे?',
    openerL1: 'नमस्ते, क्या सामान चाहिए?',
  ),
  _RoleplayPersona(
    id: 'watchman',
    title: 'Security Guard',
    titleNative: 'वॉचमन',
    icon: Icons.shield_rounded,
    openerL2: 'थांबा! कोणाकडे जायचे आहे?',
    openerL1: 'रुको! किसके पास जाना है?',
  ),
  _RoleplayPersona(
    id: 'coworker',
    title: 'Coworker',
    titleNative: 'सोबती',
    icon: Icons.handshake_rounded,
    openerL2: 'भाऊ, चहा प्यायला जाऊया का?',
    openerL1: 'भाई, चाय पीने चलें क्या?',
  ),
];

class _RoleplayScreenState extends State<RoleplayScreen> {
  static const _asrChannel = MethodChannel('boli/asr');
  static const _engineChannel = MethodChannel('boli/engine_methods');

  final List<_ChatBubble> _bubbles = [];
  final ScrollController _scrollCtrl = ScrollController();

  late _RoleplayPersona _activePersona;
  bool _listening = false;
  bool _botThinking = false;
  bool _gemmaAvailable = false;
  String _statusText = 'बोलण्यासाठी माइक दाबा (Tap mic to speak)';

  // Session limits and fluency state
  static const int _maxTurns = 5;
  int _userTurnCount = 0;
  bool _sessionCompleted = false;

  @override
  void initState() {
    super.initState();
    _activePersona = _kPersonas.firstWhere(
      (p) => widget.scenario.toLowerCase().contains(p.id),
      orElse: () => _kPersonas.first,
    );
    _initRoleplay();
  }

  @override
  void dispose() {
    if (_bubbles.length > 2) {
      _engineChannel.invokeMethod('recordCompletedScenario', {
        'scenario_id': '${_activePersona.title}: ${widget.scenario}',
      });
    }
    _engineChannel.invokeMethod('clearConversationHistory');
    _scrollCtrl.dispose();
    super.dispose();
  }

  Future<void> _initRoleplay() async {
    try {
      final available =
          await _engineChannel.invokeMethod<bool>('isGemmaAvailable') ?? false;
      if (mounted) {
        setState(() => _gemmaAvailable = available);
      }
    } on PlatformException {
      // non-fatal
    }
    await _startConversationWithPersona(_activePersona);
  }

  Future<void> _startConversationWithPersona(_RoleplayPersona persona) async {
    if (!mounted) return;
    setState(() {
      _activePersona = persona;
      _bubbles.clear();
      _userTurnCount = 0;
      _sessionCompleted = false;
      _botThinking = true;
      _statusText = '${persona.title} बोलण्याची तयारी करत आहेत… (Preparing…)';
    });

    try {
      final res = await _engineChannel.invokeMapMethod<String, dynamic>(
        'generateRoleplayOpener',
        {
          'persona': persona.title,
          'scenario': widget.scenario,
          'fallback_l2': persona.openerL2,
          'fallback_l1': persona.openerL1,
        },
      );
      if (!mounted) return;
      final l2 = res?['opener_l2'] as String? ?? persona.openerL2;
      final l1 = res?['opener_l1'] as String? ?? persona.openerL1;
      final aiSource = res?['ai_source'] as String? ?? (_gemmaAvailable ? 'gemma' : 'fallback');

      setState(() {
        _bubbles.add(_ChatBubble.bot(
          l2Text: l2,
          l1Text: l1,
          speakerName: persona.title,
          aiSource: aiSource,
        ));
        _botThinking = false;
        _statusText = 'बोलण्यासाठी माइक दाबा (Tap mic to speak · 1/$_maxTurns)';
      });
      _scrollToBottom();
      _speak(l2);
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _bubbles.add(_ChatBubble.bot(
          l2Text: persona.openerL2,
          l1Text: persona.openerL1,
          speakerName: persona.title,
          aiSource: 'fallback',
        ));
        _botThinking = false;
        _statusText = 'बोलण्यासाठी माइक दाबा (Tap mic to speak · 1/$_maxTurns)';
      });
      _scrollToBottom();
      _speak(persona.openerL2);
    }
  }

  Future<void> _listen() async {
    if (_listening || _botThinking || _sessionCompleted) return;
    setState(() {
      _listening = true;
      _statusText = 'ऐकत आहे… (Listening…)';
    });
    HapticFeedback.selectionClick();

    try {
      final transcript = await _asrChannel.invokeMethod<String>(
            'transcribeMic',
            {'seconds': 5.0},
          ) ??
          '';

      if (!mounted) return;
      if (transcript.trim().isEmpty) {
        setState(() {
          _listening = false;
          _statusText = 'काहीही ऐकू आले नाही — पुन्हा बोला (Nothing heard, try again)';
        });
        return;
      }

      final nextTurnIndex = _userTurnCount + 1;
      final userBubbleIndex = _bubbles.length;
      setState(() {
        _bubbles.add(_ChatBubble.user(text: transcript));
        _userTurnCount = nextTurnIndex;
        _listening = false;
        _botThinking = true;
        _statusText = _gemmaAvailable ? 'Gemma विचार करत आहे… (Gemma is thinking…)' : 'उत्तर तयार होत आहे…';
      });
      _scrollToBottom();

      // Submit to BoliAiLayer (Gemma or fallback) with turn count
      final response = await _engineChannel.invokeMapMethod<String, dynamic>(
        'submitUserUtterance',
        {
          'situation_id': '${_activePersona.title}: ${widget.scenario}',
          'current_node_id': 'turn_$nextTurnIndex',
          'user_spoken_text': transcript,
          'turn_number': nextTurnIndex,
          'max_turns': _maxTurns,
        },
      );

      if (!mounted) return;
      final botL2 = response?['prompt_l2'] as String? ?? '';
      final botL1 = response?['prompt_l1'] as String? ?? '';
      final hint = response?['articulatory_hint'] as String? ?? '';
      final betterWay = response?['natural_phrasing'] as String? ?? '';
      final feedback = response?['intent_explanation'] as String? ?? '';
      final aiSource = response?['ai_source'] as String? ?? 'gemma';
      final fluencyScore = response?['fluency_score'] as int?;

      final isFinished = nextTurnIndex >= _maxTurns;

      setState(() {
        // Update user bubble with coaching polish and fluency score
        _bubbles[userBubbleIndex] = _bubbles[userBubbleIndex].copyWith(
          betterWay: betterWay.isNotEmpty ? betterWay : null,
          feedback: feedback.isNotEmpty ? feedback : null,
          fluencyScore: fluencyScore,
        );

        // Add bot reply bubble
        if (botL2.isNotEmpty) {
          _bubbles.add(_ChatBubble.bot(
            l2Text: botL2,
            l1Text: botL1,
            hint: hint,
            speakerName: _activePersona.title,
            aiSource: aiSource,
          ));
        }

        _botThinking = false;
        if (isFinished) {
          _sessionCompleted = true;
          _statusText = 'संभाषण पूर्ण झाले! (Roleplay completed)';
        } else {
          _statusText = 'उत्तर देण्यासाठी माइक दाबा (Turn ${nextTurnIndex + 1}/$_maxTurns)';
        }
      });
      _scrollToBottom();

      if (botL2.isNotEmpty) _speak(botL2);
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() {
        _listening = false;
        _botThinking = false;
        _statusText = e.message ?? 'त्रुटी आली — पुन्हा प्रयत्न करा';
      });
    }
  }

  Future<void> _speak(String text) async {
    if (text.isEmpty) return;
    try {
      await _asrChannel.invokeMethod<String>('speak', {'text': text});
    } on PlatformException {
      // Offline fallback phrase missing in synth vocab is non-fatal
    }
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollCtrl.hasClients) {
        _scrollCtrl.animateTo(
          _scrollCtrl.position.maxScrollExtent + 80,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  int get _averageFluency {
    final userBubblesWithScores = _bubbles
        .where((b) => b.isUser && b.fluencyScore != null)
        .map((b) => b.fluencyScore!)
        .toList();
    if (userBubblesWithScores.isEmpty) return 78;
    return (userBubblesWithScores.reduce((a, b) => a + b) / userBubblesWithScores.length).round();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Boli.paper,
      body: SafeArea(
        child: Column(
          children: [
            _buildHeader(),
            _buildPersonaStrip(),
            Expanded(
              child: ListView.builder(
                controller: _scrollCtrl,
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
                itemCount: _bubbles.length + (_botThinking ? 1 : 0) + (_sessionCompleted ? 1 : 0),
                itemBuilder: (_, i) {
                  if (i < _bubbles.length) {
                    return _BubbleWidget(
                      bubble: _bubbles[i],
                      onSpeak: _speak,
                    );
                  }
                  if (_botThinking && i == _bubbles.length) {
                    return _ThinkingBubble(persona: _activePersona);
                  }
                  if (_sessionCompleted) {
                    return _FluencyScorecard(
                      persona: _activePersona,
                      turnCount: _userTurnCount,
                      averageFluency: _averageFluency,
                      bubbles: _bubbles,
                      onRestart: () => _startConversationWithPersona(_activePersona),
                      onSpeak: _speak,
                    );
                  }
                  return const SizedBox.shrink();
                },
              ),
            ),
            _buildInputBar(),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader() {
    return Container(
      padding: const EdgeInsets.fromLTRB(8, 8, 16, 8),
      decoration: BoxDecoration(
        color: Boli.paper,
        border: Border(bottom: BorderSide(color: Boli.sand.withValues(alpha: .5))),
      ),
      child: Row(
        children: [
          IconButton(
            icon: const Icon(Icons.arrow_back_ios_new_rounded),
            onPressed: () => Navigator.of(context).pop(),
          ),
          const SizedBox(width: 4),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  widget.scenario,
                  style: Boli.head(18, weight: 700),
                  overflow: TextOverflow.ellipsis,
                ),
                Text(
                  '${_activePersona.titleNative} यांच्याशी संभाषण · AI Roleplay',
                  style: Boli.body(13, color: Boli.inkSoft),
                ),
              ],
            ),
          ),
          // Turn progress badge
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: _sessionCompleted
                  ? Boli.leaf.withValues(alpha: .15)
                  : Boli.marigold.withValues(alpha: .2),
              borderRadius: BorderRadius.circular(6),
              border: Border.all(
                color: _sessionCompleted
                    ? Boli.leaf.withValues(alpha: .4)
                    : Boli.marigold.withValues(alpha: .4),
                width: 1,
              ),
            ),
            child: Text(
              _sessionCompleted ? 'पूर्ण (Done)' : 'उत्तर $_userTurnCount/$_maxTurns',
              style: Boli.label(
                size: 11,
                color: _sessionCompleted ? Boli.leaf : Boli.ink,
              ),
            ),
          ),
          const SizedBox(width: 6),
          // Gemma badge
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: _gemmaAvailable
                  ? Boli.leaf.withValues(alpha: .12)
                  : Boli.sand.withValues(alpha: .4),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  Icons.psychology_rounded,
                  size: 13,
                  color: _gemmaAvailable ? Boli.leaf : Boli.inkSoft,
                ),
                const SizedBox(width: 4),
                Text(
                  _gemmaAvailable ? 'Gemma 3n' : 'Offline',
                  style: Boli.label(
                    size: 11,
                    color: _gemmaAvailable ? Boli.leaf : Boli.inkSoft,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPersonaStrip() {
    return Container(
      height: 48,
      padding: const EdgeInsets.symmetric(vertical: 6),
      decoration: BoxDecoration(
        color: Boli.cream.withValues(alpha: .5),
        border: Border(bottom: BorderSide(color: Boli.sand.withValues(alpha: .4))),
      ),
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        itemCount: _kPersonas.length,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (_, idx) {
          final p = _kPersonas[idx];
          final selected = p.id == _activePersona.id;
          return GestureDetector(
            onTap: () {
              if (selected || _listening || _botThinking) return;
              _startConversationWithPersona(p);
            },
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
              decoration: BoxDecoration(
                color: selected ? Boli.ink : Boli.paper,
                borderRadius: BorderRadius.circular(20),
                border: Border.all(
                  color: selected ? Boli.ink : Boli.sand,
                  width: 1.5,
                ),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    p.icon,
                    size: 14,
                    color: selected ? Boli.marigold : Boli.inkSoft,
                  ),
                  const SizedBox(width: 6),
                  Text(
                    p.titleNative,
                    style: Boli.body(
                      12.5,
                      weight: selected ? FontWeight.w700 : FontWeight.w500,
                      color: selected ? Boli.cream : Boli.ink,
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildInputBar() {
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
      decoration: BoxDecoration(
        color: Boli.paper,
        border: Border(top: BorderSide(color: Boli.sand.withValues(alpha: .5))),
        boxShadow: [BoxShadow(color: Colors.black.withValues(alpha: .05), blurRadius: 8)],
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(
              _statusText,
              style: Boli.body(14.5, color: Boli.inkSoft, weight: FontWeight.w600),
            ),
          ),
          const SizedBox(width: 12),
          MicButton(
            busy: _listening || _botThinking,
            onTap: (_listening || _botThinking) ? null : _listen,
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Chat Models
// ---------------------------------------------------------------------------

class _ChatBubble {
  final String text; // L2 (target language)
  final String l1Text; // L1 translation
  final String hint; // pronunciation tip
  final String betterWay; // "Boli Polish" suggested phrasing
  final String feedback; // intent diagnostic
  final String speakerName;
  final String aiSource;
  final bool isUser;
  final int? fluencyScore; // 0-100 fluency rating

  const _ChatBubble({
    required this.text,
    this.l1Text = '',
    this.hint = '',
    this.betterWay = '',
    this.feedback = '',
    this.speakerName = '',
    this.aiSource = 'gemma',
    required this.isUser,
    this.fluencyScore,
  });

  factory _ChatBubble.user({required String text, int? fluencyScore}) => _ChatBubble(
        text: text,
        isUser: true,
        fluencyScore: fluencyScore,
      );

  factory _ChatBubble.bot({
    required String l2Text,
    required String l1Text,
    String hint = '',
    String speakerName = '',
    String aiSource = 'gemma',
  }) =>
      _ChatBubble(
        text: l2Text,
        l1Text: l1Text,
        hint: hint,
        speakerName: speakerName,
        aiSource: aiSource,
        isUser: false,
      );

  _ChatBubble copyWith({
    String? text,
    String? l1Text,
    String? hint,
    String? betterWay,
    String? feedback,
    String? speakerName,
    String? aiSource,
    bool? isUser,
    int? fluencyScore,
  }) =>
      _ChatBubble(
        text: text ?? this.text,
        l1Text: l1Text ?? this.l1Text,
        hint: hint ?? this.hint,
        betterWay: betterWay ?? this.betterWay,
        feedback: feedback ?? this.feedback,
        speakerName: speakerName ?? this.speakerName,
        aiSource: aiSource ?? this.aiSource,
        isUser: isUser ?? this.isUser,
        fluencyScore: fluencyScore ?? this.fluencyScore,
      );
}

// ---------------------------------------------------------------------------
// Bubble Widget
// ---------------------------------------------------------------------------

class _BubbleWidget extends StatelessWidget {
  final _ChatBubble bubble;
  final void Function(String) onSpeak;

  const _BubbleWidget({required this.bubble, required this.onSpeak});

  @override
  Widget build(BuildContext context) {
    if (bubble.isUser) {
      return _buildUserBubble();
    } else {
      return _buildBotBubble();
    }
  }

  Widget _buildUserBubble() {
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          if (bubble.fluencyScore != null) ...[
            Container(
              margin: const EdgeInsets.only(bottom: 4, right: 40),
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: bubble.fluencyScore! >= 75
                    ? Boli.leaf.withValues(alpha: .14)
                    : Boli.marigold.withValues(alpha: .25),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(
                  color: bubble.fluencyScore! >= 75
                      ? Boli.leaf.withValues(alpha: .4)
                      : Boli.terracotta.withValues(alpha: .3),
                  width: 1,
                ),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    bubble.fluencyScore! >= 75 ? Icons.check_circle_rounded : Icons.speed_rounded,
                    size: 12,
                    color: bubble.fluencyScore! >= 75 ? Boli.leaf : Boli.terracotta,
                  ),
                  const SizedBox(width: 4),
                  Text(
                    '${bubble.fluencyScore}% प्रवाही (Fluency)',
                    style: Boli.label(
                      size: 11,
                      color: bubble.fluencyScore! >= 75 ? Boli.leaf : Boli.terracotta,
                    ),
                  ),
                ],
              ),
            ),
          ],
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Flexible(
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                  decoration: BoxDecoration(
                    color: Boli.marigold,
                    borderRadius: const BorderRadius.only(
                      topLeft: Radius.circular(18),
                      topRight: Radius.circular(18),
                      bottomLeft: Radius.circular(18),
                      bottomRight: Radius.circular(4),
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withValues(alpha: .06),
                        blurRadius: 4,
                        offset: const Offset(0, 2),
                      )
                    ],
                  ),
                  child: Text(
                    bubble.text,
                    style: Boli.body(16, weight: FontWeight.w600, color: Boli.ink),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Container(
                width: 32,
                height: 32,
                decoration: BoxDecoration(
                  color: Boli.marigold.withValues(alpha: .2),
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.person_rounded, color: Boli.ink, size: 18),
              ),
            ],
          ),
          // Boli Polish / Better Way card
          if (bubble.betterWay.isNotEmpty) ...[
            const SizedBox(height: 6),
            Container(
              margin: const EdgeInsets.only(right: 40, left: 24),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Boli.cream,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Boli.sand, width: 1.5),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.auto_awesome_rounded, size: 15, color: Boli.terracotta),
                      const SizedBox(width: 6),
                      Text(
                        'बोली पॉलिश · Better way to say this',
                        style: Boli.label(size: 11, color: Boli.terracotta),
                      ),
                      const Spacer(),
                      GestureDetector(
                        onTap: () => onSpeak(bubble.betterWay),
                        child: Container(
                          padding: const EdgeInsets.all(4),
                          decoration: BoxDecoration(
                            color: Boli.terracotta.withValues(alpha: .12),
                            shape: BoxShape.circle,
                          ),
                          child: const Icon(Icons.volume_up_rounded, size: 14, color: Boli.terracotta),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(
                    bubble.betterWay,
                    style: Boli.body(14.5, weight: FontWeight.w700, color: Boli.ink),
                  ),
                  if (bubble.feedback.isNotEmpty) ...[
                    const SizedBox(height: 3),
                    Text(
                      bubble.feedback,
                      style: Boli.body(12.5, color: Boli.inkSoft),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildBotBubble() {
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 34,
            height: 34,
            decoration: BoxDecoration(
              color: Boli.peacock.withValues(alpha: .12),
              shape: BoxShape.circle,
            ),
            child: const Icon(Icons.smart_toy_rounded, color: Boli.peacock, size: 18),
          ),
          const SizedBox(width: 8),
          Flexible(
            child: Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: Boli.cream,
                borderRadius: const BorderRadius.only(
                  topLeft: Radius.circular(4),
                  topRight: Radius.circular(18),
                  bottomLeft: Radius.circular(18),
                  bottomRight: Radius.circular(18),
                ),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: .06),
                    blurRadius: 4,
                    offset: const Offset(0, 2),
                  )
                ],
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (bubble.speakerName.isNotEmpty) ...[
                    Text(
                      bubble.speakerName,
                      style: Boli.label(size: 11, color: Boli.inkSoft),
                    ),
                    const SizedBox(height: 4),
                  ],
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: Text(
                          bubble.text,
                          style: Boli.body(16, weight: FontWeight.w600, color: Boli.ink),
                        ),
                      ),
                      const SizedBox(width: 8),
                      GestureDetector(
                        onTap: () => onSpeak(bubble.text),
                        child: Container(
                          padding: const EdgeInsets.all(5),
                          decoration: BoxDecoration(
                            color: Boli.peacock.withValues(alpha: .12),
                            shape: BoxShape.circle,
                          ),
                          child: const Icon(Icons.volume_up_rounded, size: 16, color: Boli.peacock),
                        ),
                      ),
                    ],
                  ),
                  if (bubble.l1Text.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Text(
                      bubble.l1Text,
                      style: Boli.body(13.5, color: Boli.inkSoft, height: 1.3),
                    ),
                  ],
                  if (bubble.hint.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                      decoration: BoxDecoration(
                        color: Boli.terracotta.withValues(alpha: .1),
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text(
                        '💡 ${bubble.hint}',
                        style: Boli.body(12, color: Boli.terracotta),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ThinkingBubble extends StatelessWidget {
  final _RoleplayPersona persona;
  const _ThinkingBubble({required this.persona});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Row(
        children: [
          Container(
            width: 32,
            height: 32,
            decoration: BoxDecoration(
              color: Boli.peacock.withValues(alpha: .12),
              shape: BoxShape.circle,
            ),
            child: const Icon(Icons.smart_toy_rounded, color: Boli.peacock, size: 18),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            decoration: BoxDecoration(
              color: Boli.cream,
              borderRadius: BorderRadius.circular(16),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Boli.peacock),
                ),
                const SizedBox(width: 8),
                Text(
                  '${persona.titleNative} उत्तर विचार करत आहेत…',
                  style: Boli.body(13, color: Boli.inkSoft),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Fluency Scorecard Widget (Presented after 5 turns)
// ---------------------------------------------------------------------------

class _FluencyScorecard extends StatelessWidget {
  final _RoleplayPersona persona;
  final int turnCount;
  final int averageFluency;
  final List<_ChatBubble> bubbles;
  final VoidCallback onRestart;
  final void Function(String) onSpeak;

  const _FluencyScorecard({
    required this.persona,
    required this.turnCount,
    required this.averageFluency,
    required this.bubbles,
    required this.onRestart,
    required this.onSpeak,
  });

  @override
  Widget build(BuildContext context) {
    final userBubbles = bubbles.where((b) => b.isUser).toList();
    final polishSuggestions = userBubbles.where((b) => b.betterWay.isNotEmpty).toList();

    return Container(
      margin: const EdgeInsets.symmetric(vertical: 16),
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: Boli.cream,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Boli.marigold, width: 2),
        boxShadow: [
          BoxShadow(
            color: Boli.ink.withValues(alpha: .08),
            blurRadius: 12,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: Boli.marigold.withValues(alpha: .2),
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.stars_rounded, color: Boli.marigold, size: 28),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'संभाषण प्रवाहीपणा अहवाल',
                      style: Boli.head(18, weight: 800),
                    ),
                    Text(
                      'Fluency & Naturalness Scorecard · $turnCount उत्तरे',
                      style: Boli.body(13, color: Boli.inkSoft),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),

          // Fluency Gauge Card
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Boli.paper,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: Boli.sand),
            ),
            child: Row(
              children: [
                Stack(
                  alignment: Alignment.center,
                  children: [
                    SizedBox(
                      width: 64,
                      height: 64,
                      child: CircularProgressIndicator(
                        value: averageFluency / 100.0,
                        strokeWidth: 6,
                        backgroundColor: Boli.sand.withValues(alpha: .4),
                        color: averageFluency >= 75 ? Boli.leaf : Boli.marigold,
                      ),
                    ),
                    Text(
                      '$averageFluency%',
                      style: Boli.head(16, weight: 800, color: Boli.ink),
                    ),
                  ],
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        averageFluency >= 85
                            ? 'उत्कृष्ट प्रवाहीपणा! (Highly Fluent)'
                            : averageFluency >= 70
                                ? 'चांगला संवाद! (Good Naturalness)'
                                : 'सराव सुरू ठेवा (Keep Practicing)',
                        style: Boli.head(15, weight: 700, color: Boli.ink),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'तुमची वाक्ये समजण्यासारखी आणि कामाच्या ठिकाणी योग्य होती.',
                        style: Boli.body(13, color: Boli.inkSoft),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // Key Better Way Polish items review
          if (polishSuggestions.isNotEmpty) ...[
            Text(
              'सुचवलेले अचूक उच्चार व वाक्यरचना:',
              style: Boli.body(14, weight: FontWeight.w700, color: Boli.ink),
            ),
            const SizedBox(height: 8),
            ...polishSuggestions.take(2).map(
              (b) => Container(
                margin: const EdgeInsets.only(bottom: 8),
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                decoration: BoxDecoration(
                  color: Boli.paper,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Boli.sand.withValues(alpha: .6)),
                ),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            b.betterWay,
                            style: Boli.body(14, weight: FontWeight.w700, color: Boli.ink),
                          ),
                          if (b.feedback.isNotEmpty) ...[
                            const SizedBox(height: 2),
                            Text(
                              b.feedback,
                              style: Boli.body(12, color: Boli.inkSoft),
                            ),
                          ],
                        ],
                      ),
                    ),
                    IconButton(
                      icon: const Icon(Icons.volume_up_rounded, color: Boli.terracotta, size: 20),
                      onPressed: () => onSpeak(b.betterWay),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
          ],

          // Action buttons: Try Again / Change Persona
          Row(
            children: [
              Expanded(
                child: ElevatedButton.icon(
                  onPressed: onRestart,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Boli.marigold,
                    foregroundColor: Boli.ink,
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  ),
                  icon: const Icon(Icons.replay_rounded, size: 18),
                  label: Text('पुन्हा बोला (Restart · 5 Turns)', style: Boli.body(14, weight: FontWeight.w700)),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
