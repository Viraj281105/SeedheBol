import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'theme.dart';
import 'widgets.dart';

/// Gemma-powered conversational roleplay screen.
///
/// Roleplay scenarios simulate real situations a migrant worker encounters:
/// talking to a supervisor, ordering materials, explaining work done, etc.
///
/// Flow:
///   1. User taps mic → IndicConformer ASR (real inference, same channel as practice)
///   2. Transcript → 'submitUserUtterance' → BoliAiLayer.nextRoleplayTurn
///   3. Gemma generates a contextual next turn (or DeterministicFallback if unavailable)
///   4. Bot response displayed + FastPitch TTS auto-plays
///
/// The conversation history is maintained on the Kotlin side (BoliBridgePlugin)
/// across turns within one session. It resets when the user closes this screen
/// or calls initializeEngine again.
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

class _RoleplayScreenState extends State<RoleplayScreen> {
  static const _asrChannel = MethodChannel('boli/asr');
  static const _engineChannel = MethodChannel('boli/engine_methods');

  final List<_ChatBubble> _bubbles = [];
  final ScrollController _scrollCtrl = ScrollController();

  bool _listening = false;
  bool _botThinking = false;
  bool _gemmaAvailable = false;
  String _statusText = 'Tap the mic to start';

  @override
  void initState() {
    super.initState();
    _checkGemmaAvailability();
    _addBotGreeting();
  }

  @override
  void dispose() {
    _scrollCtrl.dispose();
    super.dispose();
  }

  Future<void> _checkGemmaAvailability() async {
    try {
      final available = await _engineChannel.invokeMethod<bool>('isGemmaAvailable') ?? false;
      if (mounted) setState(() => _gemmaAvailable = available);
    } on PlatformException {
      // non-fatal
    }
  }

  void _addBotGreeting() {
    // Start with a scene-setting opener
    final opener = _scenarioOpener(widget.scenario);
    setState(() {
      _bubbles.add(_ChatBubble.bot(
        l2Text: opener.l2,
        l1Text: opener.l1,
      ));
    });
    Future.delayed(const Duration(milliseconds: 400), () => _speak(opener.l2));
  }

  _Opening _scenarioOpener(String scenario) {
    // Small curated set for the demo domain; Gemma will generate the rest.
    if (scenario.contains('supervisor') || scenario.contains('काम')) {
      return _Opening(
        l2: 'नमस्ते! आज काय काम आहे?',
        l1: 'Hello! What work is there today?',
      );
    } else if (scenario.contains('shop') || scenario.contains('दुकान')) {
      return _Opening(
        l2: 'नमस्ते, काय पाहिजे?',
        l1: 'Hello, what do you need?',
      );
    }
    return _Opening(
      l2: 'नमस्ते! बोला.',
      l1: 'Hello! Speak.',
    );
  }

  Future<void> _listen() async {
    if (_listening || _botThinking) return;
    setState(() {
      _listening = true;
      _statusText = 'Listening…';
    });
    HapticFeedback.selectionClick();

    try {
      final transcript = await _asrChannel.invokeMethod<String>(
            'transcribeMic',
            {'seconds': 5.0},
          ) ??
          '';

      if (!mounted) return;
      if (transcript.isEmpty) {
        setState(() {
          _listening = false;
          _statusText = 'Nothing heard — try again';
        });
        return;
      }

      setState(() {
        _bubbles.add(_ChatBubble.user(text: transcript));
        _listening = false;
        _botThinking = true;
        _statusText = _gemmaAvailable ? 'Gemma is thinking…' : 'Generating response…';
      });
      _scrollToBottom();

      // Submit to BoliAiLayer (Gemma or fallback)
      final response = await _engineChannel.invokeMapMethod<String, dynamic>(
        'submitUserUtterance',
        {
          'situation_id': widget.scenario,
          'current_node_id': 'turn_${_bubbles.length}',
          'user_spoken_text': transcript,
        },
      );

      if (!mounted) return;
      final botL2 = response?['prompt_l2'] as String? ?? '';
      final botL1 = response?['prompt_l1'] as String? ?? '';
      final hint = response?['articulatory_hint'] as String? ?? '';
      final aiSource = response?['ai_source'] as String? ?? 'unknown';

      setState(() {
        _bubbles.add(_ChatBubble.bot(
          l2Text: botL2,
          l1Text: botL1,
          hint: hint,
          aiSource: aiSource,
        ));
        _botThinking = false;
        _statusText = 'Tap mic to reply';
      });
      _scrollToBottom();

      if (botL2.isNotEmpty) _speak(botL2);
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() {
        _listening = false;
        _botThinking = false;
        _statusText = e.message ?? 'Error — try again';
      });
    }
  }

  Future<void> _speak(String text) async {
    if (text.isEmpty) return;
    try {
      await _asrChannel.invokeMethod<String>('speak', {'text': text});
    } on PlatformException {
      // TTS vocab miss — not worth surfacing
    }
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollCtrl.hasClients) {
        _scrollCtrl.animateTo(
          _scrollCtrl.position.maxScrollExtent,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            _buildHeader(),
            Expanded(
              child: ListView.builder(
                controller: _scrollCtrl,
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
                itemCount: _bubbles.length + (_botThinking ? 1 : 0),
                itemBuilder: (_, i) {
                  if (i == _bubbles.length && _botThinking) {
                    return const _ThinkingBubble();
                  }
                  return _BubbleWidget(bubble: _bubbles[i]);
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
        border: Border(bottom: BorderSide(color: Boli.sand.withValues(alpha: .6))),
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
                Text(widget.scenario, style: Boli.head(18, weight: 700)),
                Text(widget.scenarioNative, style: Boli.body(13, color: Boli.inkSoft)),
              ],
            ),
          ),
          // Gemma indicator
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
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
                  _gemmaAvailable ? 'Gemma' : 'Offline',
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
              style: Boli.body(15, color: Boli.inkSoft, weight: FontWeight.w600),
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
// Data

class _Opening {
  final String l2, l1;
  _Opening({required this.l2, required this.l1});
}

class _ChatBubble {
  final String text;     // L2 (target language)
  final String l1Text;   // L1 translation
  final String hint;
  final String aiSource; // "gemma" | "fallback" | "user"
  final bool isUser;

  const _ChatBubble._({
    required this.text,
    required this.l1Text,
    required this.hint,
    required this.aiSource,
    required this.isUser,
  });

  factory _ChatBubble.user({required String text}) => _ChatBubble._(
        text: text,
        l1Text: '',
        hint: '',
        aiSource: 'user',
        isUser: true,
      );

  factory _ChatBubble.bot({
    required String l2Text,
    required String l1Text,
    String hint = '',
    String aiSource = 'fallback',
  }) => _ChatBubble._(
        text: l2Text,
        l1Text: l1Text,
        hint: hint,
        aiSource: aiSource,
        isUser: false,
      );
}

// ---------------------------------------------------------------------------
// Widgets

class _BubbleWidget extends StatelessWidget {
  final _ChatBubble bubble;
  const _BubbleWidget({required this.bubble});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Row(
        mainAxisAlignment:
            bubble.isUser ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          if (!bubble.isUser) ...[
            Container(
              width: 36,
              height: 36,
              decoration: BoxDecoration(
                color: Boli.peacock.withValues(alpha: .12),
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.smart_toy_rounded, color: Boli.peacock, size: 20),
            ),
            const SizedBox(width: 8),
          ],
          Flexible(
            child: Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: bubble.isUser ? Boli.marigold : Boli.cream,
                borderRadius: BorderRadius.only(
                  topLeft: const Radius.circular(18),
                  topRight: const Radius.circular(18),
                  bottomLeft: Radius.circular(bubble.isUser ? 18 : 4),
                  bottomRight: Radius.circular(bubble.isUser ? 4 : 18),
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
                  Text(
                    bubble.text,
                    style: Boli.body(
                      16,
                      weight: FontWeight.w600,
                      color: bubble.isUser ? Boli.ink : Boli.ink,
                    ),
                  ),
                  if (bubble.l1Text.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Text(
                      bubble.l1Text,
                      style: Boli.body(13, color: Boli.inkSoft, height: 1.4),
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
                  if (!bubble.isUser && bubble.aiSource == 'gemma') ...[
                    const SizedBox(height: 6),
                    Text(
                      'GEMMA',
                      style: Boli.label(size: 9, color: Boli.peacock.withValues(alpha: .6)),
                    ),
                  ],
                ],
              ),
            ),
          ),
          if (bubble.isUser) const SizedBox(width: 8),
        ],
      ),
    );
  }
}

class _ThinkingBubble extends StatelessWidget {
  const _ThinkingBubble();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Row(
        children: [
          Container(
            width: 36,
            height: 36,
            decoration: BoxDecoration(
              color: Boli.peacock.withValues(alpha: .12),
              shape: BoxShape.circle,
            ),
            child: const Icon(Icons.smart_toy_rounded, color: Boli.peacock, size: 20),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
            decoration: BoxDecoration(
              color: Boli.cream,
              borderRadius: const BorderRadius.only(
                topLeft: Radius.circular(18),
                topRight: Radius.circular(18),
                bottomRight: Radius.circular(18),
                bottomLeft: Radius.circular(4),
              ),
            ),
            child: const SizedBox(
              width: 32,
              height: 16,
              child: _TypingIndicator(),
            ),
          ),
        ],
      ),
    );
  }
}

class _TypingIndicator extends StatefulWidget {
  const _TypingIndicator();
  @override
  State<_TypingIndicator> createState() => _TypingIndicatorState();
}

class _TypingIndicatorState extends State<_TypingIndicator>
    with TickerProviderStateMixin {
  late AnimationController _ctrl;
  late Animation<double> _anim;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 900))
      ..repeat(reverse: true);
    _anim = Tween<double>(begin: .3, end: 1.0).animate(_ctrl);
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _anim,
      builder: (_, __) => Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: List.generate(3, (i) {
          final delay = i * .2;
          final v = ((_ctrl.value + delay) % 1.0);
          final opacity = 0.3 + (v < 0.5 ? v * 1.4 : (1 - v) * 1.4);
          return Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: Boli.inkSoft.withValues(alpha: opacity.clamp(0.3, 1.0)),
            ),
          );
        }),
      ),
    );
  }
}
