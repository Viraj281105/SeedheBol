import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'theme.dart';

/// "Practise with someone" (सोबत सराव)
///
/// Designed for two people practicing together on a single phone (e.g. during a
/// lunch break or mentor-apprentice session).
///
/// Offline Architecture:
///   - Speaker 1 (Learner) or Speaker 2 (Partner/Mentor) speaks into the mic.
///   - On-device IndicConformer ASR transcribes the speech.
///   - On-device Gemma 3n E2B provides:
///       1. Real-time translation to the partner's language.
///       2. "Boli Polish" / Better way to phrase the thought.
///       3. Language coach tips (politeness, workplace register).
///       4. Next conversation prompt suggestion.
///   - FastPitch TTS allows immediate audio playback of any phrase.
class WithSomeoneScreen extends StatefulWidget {
  const WithSomeoneScreen({super.key});

  @override
  State<WithSomeoneScreen> createState() => _WithSomeoneScreenState();
}

class _PeerTurn {
  final String speakerRole; // "Learner" or "Partner"
  final String spokenText;
  final String translation;
  final String betterWay;
  final String coachTip;
  final String nextPrompt;

  const _PeerTurn({
    required this.speakerRole,
    required this.spokenText,
    required this.translation,
    this.betterWay = '',
    this.coachTip = '',
    this.nextPrompt = '',
  });
}

class _WithSomeoneScreenState extends State<WithSomeoneScreen> {
  static const _asrChannel = MethodChannel('boli/asr');
  static const _engineChannel = MethodChannel('boli/engine_methods');

  final List<_PeerTurn> _turns = [];
  final ScrollController _scrollCtrl = ScrollController();

  bool _listening = false;
  String _activeSpeaker = '';
  bool _thinking = false;
  String _statusMessage = 'सराव सुरू करण्यासाठी खालील माइक दाबा';
  String _suggestedTopic = 'कामाची वेळ आणि जेवणाची सुट्टी (Work hours & lunch)';

  @override
  void initState() {
    super.initState();
    _addInitialPrompt();
  }

  @override
  void dispose() {
    _scrollCtrl.dispose();
    super.dispose();
  }

  void _addInitialPrompt() {
    _turns.add(const _PeerTurn(
      speakerRole: 'AI Facilitator',
      spokenText: 'दोघांनी एकमेकांशी बोलायला सुरुवात करा. खालील माइक दाबा.',
      translation: 'दोनों एक दूसरे से बात करना शुरू करें। नीचे माइक दबाएं।',
      coachTip: 'एकमेकांना प्रश्न विचारा आणि नम्रतेने उत्तरे द्या.',
      nextPrompt: 'आज कामावर काय केले ते एकमेकांना सांगा.',
    ));
  }

  Future<void> _recordTurn(String role) async {
    if (_listening || _thinking) return;

    setState(() {
      _listening = true;
      _activeSpeaker = role;
      _statusMessage = '$role बोलत आहेत… (Listening…)';
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
          _activeSpeaker = '';
          _statusMessage = 'काहीही ऐकू आले नाही — पुन्हा बोला (Nothing heard)';
        });
        return;
      }

      setState(() {
        _listening = false;
        _thinking = true;
        _statusMessage = 'Gemma भाषांतर आणि सल्ला तयार करत आहे…';
      });
      _scrollToBottom();

      // Invoke Gemma peer coach
      final coachRes = await _engineChannel.invokeMapMethod<String, dynamic>(
        'coachPeerTurn',
        {
          'spoken_text': transcript,
          'speaker_role': role,
        },
      );

      if (!mounted) return;
      final trans = coachRes?['translation'] as String? ?? transcript;
      final better = coachRes?['better_way'] as String? ?? '';
      final tip = coachRes?['coach_tip'] as String? ?? '';
      final next = coachRes?['next_prompt'] as String? ?? '';

      setState(() {
        _turns.add(_PeerTurn(
          speakerRole: role,
          spokenText: transcript,
          translation: trans,
          betterWay: better,
          coachTip: tip,
          nextPrompt: next,
        ));
        _thinking = false;
        _activeSpeaker = '';
        _statusMessage = 'पुढील वक्ता माइक दाबून उत्तर देऊ शकतो';
        if (next.isNotEmpty) _suggestedTopic = next;
      });
      _scrollToBottom();
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() {
        _listening = false;
        _thinking = false;
        _activeSpeaker = '';
        _statusMessage = e.message ?? 'त्रुटी आली — पुन्हा प्रयत्न करा';
      });
    }
  }

  Future<void> _speak(String text) async {
    if (text.isEmpty) return;
    try {
      await _asrChannel.invokeMethod<String>('speak', {'text': text});
    } on PlatformException {
      // Audio synth miss is non-fatal
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Boli.paper,
      body: SafeArea(
        child: Column(
          children: [
            _buildAppBar(),
            _buildTopicBanner(),
            Expanded(
              child: ListView.builder(
                controller: _scrollCtrl,
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
                itemCount: _turns.length + (_thinking ? 1 : 0),
                itemBuilder: (_, idx) {
                  if (idx == _turns.length && _thinking) {
                    return _buildThinkingWidget();
                  }
                  return _buildTurnCard(_turns[idx]);
                },
              ),
            ),
            _buildDualMicBar(),
          ],
        ),
      ),
    );
  }

  Widget _buildAppBar() {
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
                Text('Practise with someone', style: Boli.head(18, weight: 700)),
                Text(
                  'सोबत सराव · Face-to-Face Peer Practice',
                  style: Boli.body(13, color: Boli.inkSoft),
                ),
              ],
            ),
          ),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: Boli.marigold.withValues(alpha: .15),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.people_alt_rounded, size: 14, color: Boli.ink),
                const SizedBox(width: 4),
                Text('2 People', style: Boli.label(size: 11, color: Boli.ink)),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTopicBanner() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      color: Boli.cream,
      child: Row(
        children: [
          const Icon(Icons.tips_and_updates_rounded, size: 16, color: Boli.terracotta),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              'सुचवलेला विषय: $_suggestedTopic',
              style: Boli.body(13, color: Boli.ink, weight: FontWeight.w600),
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTurnCard(_PeerTurn turn) {
    final isFacilitator = turn.speakerRole == 'AI Facilitator';
    final isLearner = turn.speakerRole == 'शिकणारा (Learner)';

    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: isFacilitator
            ? Boli.cream
            : (isLearner ? Boli.marigold.withValues(alpha: .08) : Boli.peacock.withValues(alpha: .08)),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: isFacilitator
              ? Boli.sand
              : (isLearner ? Boli.marigold.withValues(alpha: .4) : Boli.peacock.withValues(alpha: .4)),
          width: 1.5,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Speaker tag & Audio button
          Row(
            children: [
              Icon(
                isFacilitator
                    ? Icons.psychology_rounded
                    : (isLearner ? Icons.person_rounded : Icons.person_outline_rounded),
                size: 16,
                color: isLearner ? Boli.terracotta : Boli.peacock,
              ),
              const SizedBox(width: 6),
              Text(
                turn.speakerRole,
                style: Boli.label(
                  size: 12,
                  color: isLearner ? Boli.terracotta : Boli.peacock,
                ),
              ),
              const Spacer(),
              GestureDetector(
                onTap: () => _speak(turn.spokenText),
                child: Container(
                  padding: const EdgeInsets.all(4),
                  decoration: BoxDecoration(
                    color: Boli.sand.withValues(alpha: .5),
                    shape: BoxShape.circle,
                  ),
                  child: const Icon(Icons.volume_up_rounded, size: 16, color: Boli.ink),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          // Spoken Text
          Text(
            turn.spokenText,
            style: Boli.body(16, weight: FontWeight.w700, color: Boli.ink),
          ),
          // Translation
          if (turn.translation.isNotEmpty && turn.translation != turn.spokenText) ...[
            const SizedBox(height: 4),
            Text(
              turn.translation,
              style: Boli.body(14, color: Boli.inkSoft),
            ),
          ],
          // Better Way / Coach tip
          if (turn.betterWay.isNotEmpty) ...[
            const SizedBox(height: 8),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: Boli.paper,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Boli.sand),
              ),
              child: Row(
                children: [
                  const Icon(Icons.auto_awesome_rounded, size: 14, color: Boli.terracotta),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      'अधिक नैसर्गिक: ${turn.betterWay}',
                      style: Boli.body(13, weight: FontWeight.w600, color: Boli.ink),
                    ),
                  ),
                  GestureDetector(
                    onTap: () => _speak(turn.betterWay),
                    child: const Icon(Icons.play_circle_outline_rounded, size: 16, color: Boli.terracotta),
                  ),
                ],
              ),
            ),
          ],
          if (turn.coachTip.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              '💡 सल्ला: ${turn.coachTip}',
              style: Boli.body(12.5, color: Boli.terracotta),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildThinkingWidget() {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Boli.cream,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const SizedBox(
            width: 16,
            height: 16,
            child: CircularProgressIndicator(strokeWidth: 2, color: Boli.terracotta),
          ),
          const SizedBox(width: 10),
          Text(
            'Gemma संवाद समजावून घेत आहे…',
            style: Boli.body(13, color: Boli.inkSoft),
          ),
        ],
      ),
    );
  }

  Widget _buildDualMicBar() {
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
      decoration: BoxDecoration(
        color: Boli.paper,
        border: Border(top: BorderSide(color: Boli.sand.withValues(alpha: .5))),
        boxShadow: [BoxShadow(color: Colors.black.withValues(alpha: .06), blurRadius: 8)],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            _statusMessage,
            style: Boli.body(13.5, color: Boli.inkSoft, weight: FontWeight.w600),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              // Speaker 1 (Learner)
              Expanded(
                child: _SpeakerMicButton(
                  role: 'शिकणारा (Learner)',
                  nativeLabel: 'शिकणारा',
                  color: Boli.terracotta,
                  icon: Icons.mic_rounded,
                  busy: _listening && _activeSpeaker == 'शिकणारा (Learner)',
                  disabled: _listening || _thinking,
                  onTap: () => _recordTurn('शिकणारा (Learner)'),
                ),
              ),
              const SizedBox(width: 12),
              // Speaker 2 (Partner)
              Expanded(
                child: _SpeakerMicButton(
                  role: 'सोबती (Partner)',
                  nativeLabel: 'सोबती / मित्र',
                  color: Boli.peacock,
                  icon: Icons.mic_none_rounded,
                  busy: _listening && _activeSpeaker == 'सोबती (Partner)',
                  disabled: _listening || _thinking,
                  onTap: () => _recordTurn('सोबती (Partner)'),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _SpeakerMicButton extends StatelessWidget {
  final String role;
  final String nativeLabel;
  final Color color;
  final IconData icon;
  final bool busy;
  final bool disabled;
  final VoidCallback onTap;

  const _SpeakerMicButton({
    required this.role,
    required this.nativeLabel,
    required this.color,
    required this.icon,
    required this.busy,
    required this.disabled,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: disabled && !busy ? null : onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 10),
        decoration: BoxDecoration(
          color: busy ? color : color.withValues(alpha: .12),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: color, width: 1.5),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            if (busy)
              const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
              )
            else
              Icon(icon, size: 20, color: color),
            const SizedBox(width: 8),
            Text(
              nativeLabel,
              style: Boli.body(
                14,
                weight: FontWeight.w700,
                color: busy ? Colors.white : color,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
