import 'package:flutter/material.dart';
import 'data.dart';
import 'theme.dart';
import 'widgets.dart';

/// Phone-native capabilities, surfaced as tools rather than buried in settings.
///
/// Only the speaking path runs real inference in this prototype. Everything
/// here is an interface shell that shows the intended interaction, and each one
/// says so plainly rather than pretending.
class ToolsScreen extends StatelessWidget {
  final Lang l2;
  const ToolsScreen({super.key, required this.l2});

  @override
  Widget build(BuildContext context) {
    final tools = [
      _T(
        'Point the camera',
        'कॅमेरा',
        'Read a signboard, wage slip or prescription',
        Icons.photo_camera_rounded,
        Boli.terracotta,
      ),
      _T(
        'Listen around me',
        'आजूबाजूचे शब्द',
        'Collect words spoken near you into tomorrow\'s practice',
        Icons.hearing_rounded,
        Boli.peacock,
      ),
      _T(
        'Practise a conversation',
        'संवाद',
        'Talk to a supervisor, shopkeeper or clerk',
        Icons.forum_rounded,
        Boli.indigo,
      ),
      _T(
        'Commute mode',
        'प्रवासात',
        'Hands free, screen off, audio only',
        Icons.directions_bus_rounded,
        Boli.madder,
      ),
      _T(
        'Practise with someone',
        'सोबत सराव',
        'Tap two phones together, no internet',
        Icons.contactless_rounded,
        Boli.marigold,
      ),
      _T(
        'Write the letters',
        'अक्षरे',
        'Trace Devanagari with your finger',
        Icons.draw_rounded,
        Boli.leaf,
      ),
    ];

    return SafeArea(
      bottom: false,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
        children: [
          Text('Tools', style: Boli.head(30, weight: 700)),
          Text(
            'Things this phone can do that a website cannot.',
            style: Boli.body(15.5, color: Boli.inkSoft),
          ),
          const SizedBox(height: 20),
          const SectionHead('USES THE MICROPHONE OR CAMERA'),
          for (int i = 0; i < tools.length; i++) ...[
            _ToolCard(tool: tools[i], index: i),
            const SizedBox(height: 10),
          ],
          const SizedBox(height: 12),
          _PrivacyNote(),
        ],
      ),
    );
  }
}

class _T {
  final String title, native, subtitle;
  final IconData icon;
  final Color tint;
  _T(this.title, this.native, this.subtitle, this.icon, this.tint);
}

class _ToolCard extends StatelessWidget {
  final _T tool;
  final int index;
  const _ToolCard({required this.tool, required this.index});

  @override
  Widget build(BuildContext context) => TweenAnimationBuilder<double>(
    tween: Tween(begin: 0, end: 1),
    duration: Duration(milliseconds: 340 + index * 60),
    curve: Curves.easeOutCubic,
    builder: (_, v, child) => Opacity(
      opacity: v.clamp(0, 1),
      child: Transform.translate(offset: Offset(0, (1 - v) * 18), child: child),
    ),
    child: GestureDetector(
      onTap: () => showModalBottomSheet(
        context: context,
        backgroundColor: Colors.transparent,
        isScrollControlled: true,
        builder: (_) => _ToolSheet(tool: tool),
      ),
      child: Container(
        padding: const EdgeInsets.all(15),
        decoration: Boli.card(),
        child: Row(
          children: [
            Container(
              width: 54,
              height: 54,
              decoration: BoxDecoration(
                color: tool.tint.withValues(alpha: .12),
                borderRadius: BorderRadius.circular(14),
              ),
              child: Icon(tool.icon, color: tool.tint, size: 27),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(tool.title, style: Boli.head(19, weight: 600)),
                  Text(
                    tool.native,
                    style: Boli.body(14.5, color: Boli.inkSoft),
                  ),
                ],
              ),
            ),
            const Icon(
              Icons.chevron_right_rounded,
              color: Boli.inkSoft,
              size: 26,
            ),
          ],
        ),
      ),
    ),
  );
}

class _ToolSheet extends StatelessWidget {
  final _T tool;
  const _ToolSheet({required this.tool});

  @override
  Widget build(BuildContext context) => Container(
    decoration: const BoxDecoration(
      color: Boli.paper,
      borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
    ),
    padding: const EdgeInsets.fromLTRB(22, 14, 22, 28),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Center(
          child: Container(
            width: 42,
            height: 5,
            decoration: BoxDecoration(
              color: Boli.sand,
              borderRadius: BorderRadius.circular(4),
            ),
          ),
        ),
        const SizedBox(height: 20),
        Row(
          children: [
            Container(
              width: 56,
              height: 56,
              decoration: BoxDecoration(
                color: tool.tint.withValues(alpha: .12),
                borderRadius: BorderRadius.circular(15),
              ),
              child: Icon(tool.icon, color: tool.tint, size: 28),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(tool.title, style: Boli.head(23, weight: 700)),
                  Text(tool.native, style: Boli.body(16, color: Boli.inkSoft)),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 18),
        Text(tool.subtitle, style: Boli.body(16.5, height: 1.5)),
        const SizedBox(height: 18),
        Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: Boli.cream,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: Boli.sand, width: 1.5),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Icon(
                Icons.construction_rounded,
                size: 18,
                color: Boli.inkSoft,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  'Interface only in this prototype. The speech recognition behind '
                  '“Say it out loud” is real and runs on this phone.',
                  style: Boli.body(14, color: Boli.inkSoft),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 20),
        BigButton(
          label: 'Close',
          outline: true,
          color: Boli.inkSoft,
          onTap: () => Navigator.of(context).pop(),
        ),
      ],
    ),
  );
}

class _PrivacyNote extends StatelessWidget {
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(16),
    decoration: BoxDecoration(
      color: Boli.ink,
      borderRadius: BorderRadius.circular(18),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Icon(Icons.lock_rounded, size: 18, color: Boli.marigold),
            const SizedBox(width: 8),
            Text(
              'WHAT LEAVES THIS PHONE',
              style: Boli.label(color: Boli.marigold, size: 11.5),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Text('Nothing.', style: Boli.head(28, weight: 700, color: Boli.cream)),
        const SizedBox(height: 6),
        Text(
          'Your voice is turned into text on this device and the recording is '
          'discarded straight away. No audio is stored and none is sent anywhere. '
          'The app works with the radio switched off.',
          style: Boli.body(
            14.5,
            color: Boli.cream.withValues(alpha: .78),
            height: 1.5,
          ),
        ),
      ],
    ),
  );
}
