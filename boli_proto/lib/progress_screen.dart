import 'package:flutter/material.dart';
import 'bridge/boli_bridge.dart';
import 'data.dart';
import 'theme.dart';
import 'widgets.dart';

/// Progress framed as employability, not as points.
///
/// The headline number is how much of the work you can now handle in the target
/// language. Below it: the sounds you are still getting wrong (which is where
/// the pronunciation scorer will land), the languages on the device, and a
/// signed attestation an employer could actually check.
class ProgressScreen extends StatelessWidget {
  final Lang l1, l2;
  final Job job;
  const ProgressScreen({
    super.key,
    required this.l1,
    required this.l2,
    required this.job,
  });

  @override
  Widget build(BuildContext context) {
    final covered = situations.where((s) => s.readiness >= 1).length;
    final overall =
        situations.map((s) => s.readiness).fold(0.0, (a, b) => a + b) /
        situations.length;

    return SafeArea(
      bottom: false,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
        children: [
          Text('Progress', style: Boli.head(30, weight: 700)),
          Text(
            '${l1.english} to ${l2.english} · ${job.title}',
            style: Boli.body(15.5, color: Boli.inkSoft),
          ),
          const SizedBox(height: 20),

          // ---- headline -----------------------------------------------------
          Container(
            padding: const EdgeInsets.all(18),
            decoration: BoxDecoration(
              color: Boli.paper,
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: Boli.sand, width: 2),
            ),
            child: Column(
              children: [
                ReadinessRing(value: overall, size: 104),
                const SizedBox(height: 14),
                Text(
                  '${(overall * 100).round()}% ready for work',
                  style: Boli.head(25, weight: 700),
                ),
                const SizedBox(height: 4),
                Text(
                  '$covered of ${situations.length} situations you can handle unaided',
                  textAlign: TextAlign.center,
                  style: Boli.body(15, color: Boli.inkSoft),
                ),
                const SizedBox(height: 16),
                HandloomBorder(
                  color: Boli.marigold.withValues(alpha: .55),
                  height: 10,
                ),
                const SizedBox(height: 16),
                Row(
                  children: [
                    _Stat(value: '37', label: 'PHRASES\nYOU CAN USE'),
                    _Stat(value: '6', label: 'DAYS\nPRACTISED'),
                    _Stat(value: '12', label: 'SAVED FOR\nREVIEW'),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 24),

          // ---- pronunciation ------------------------------------------------
          const SectionHead('SOUNDS TO WORK ON'),
          Container(
            padding: const EdgeInsets.all(16),
            decoration: Boli.card(),
            child: Column(
              children: [
                _Sound(
                  a: 'ट',
                  b: 'त',
                  label: 'Retroflex vs dental',
                  score: .42,
                ),
                const SizedBox(height: 14),
                _Sound(a: 'ख', b: 'क', label: 'Aspirated vs plain', score: .68),
                const SizedBox(height: 14),
                _Sound(a: 'ळ', b: 'ल', label: 'Retroflex lateral', score: .81),
                const SizedBox(height: 14),
                Container(
                  padding: const EdgeInsets.all(13),
                  decoration: BoxDecoration(
                    color: Boli.cream,
                    borderRadius: BorderRadius.circular(11),
                  ),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Icon(
                        Icons.lightbulb_rounded,
                        size: 17,
                        color: Boli.marigold,
                      ),
                      const SizedBox(width: 9),
                      Expanded(
                        child: Text(
                          'Hindi speakers learning Marathi commonly replace ळ with ल. '
                          'These drills are chosen for that pair, not from a general list.',
                          style: Boli.body(
                            13.5,
                            color: Boli.inkSoft,
                            height: 1.45,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 24),

          // ---- languages ----------------------------------------------------
          SectionHead('LANGUAGES ON THIS PHONE', trailing: 'MANAGE'),
          Container(
            decoration: Boli.card(),
            child: Column(
              children: [
                for (int i = 0; i < 5; i++) ...[
                  if (i > 0)
                    Divider(height: 1, color: Boli.sand, thickness: 1.5),
                  _LangRow(lang: targetLanguages[i]),
                ],
              ],
            ),
          ),
          const SizedBox(height: 24),

          // ---- attestation --------------------------------------------------
          const SectionHead('PROOF FOR AN EMPLOYER'),
          Container(
            padding: const EdgeInsets.all(18),
            decoration: BoxDecoration(
              color: Boli.indigo,
              borderRadius: BorderRadius.circular(20),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(
                      Icons.verified_rounded,
                      color: Boli.marigold,
                      size: 26,
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        'Skill certificate',
                        style: Boli.head(21, weight: 700, color: Boli.cream),
                      ),
                    ),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 5,
                      ),
                      decoration: BoxDecoration(
                        color: Boli.marigold.withValues(alpha: .2),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(
                        'LEVEL 2',
                        style: Boli.label(color: Boli.marigold, size: 11),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Text(
                  'Functional ${l2.english} · ${job.title} register',
                  style: Boli.body(
                    16,
                    weight: FontWeight.w700,
                    color: Boli.cream,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  'Signed by this device and checkable by an employer without an account. '
                  'Unlocks once you can handle 8 of ${situations.length} situations.',
                  style: Boli.body(
                    14,
                    color: Boli.cream.withValues(alpha: .72),
                    height: 1.45,
                  ),
                ),
                const SizedBox(height: 16),
                Row(
                  children: [
                    Expanded(
                      child: Container(
                        height: 8,
                        decoration: BoxDecoration(
                          color: Boli.cream.withValues(alpha: .18),
                          borderRadius: BorderRadius.circular(5),
                        ),
                        child: FractionallySizedBox(
                          alignment: Alignment.centerLeft,
                          widthFactor: covered / 8,
                          child: Container(
                            decoration: BoxDecoration(
                              color: Boli.marigold,
                              borderRadius: BorderRadius.circular(5),
                            ),
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Text(
                      '$covered / 8',
                      style: Boli.body(
                        14,
                        weight: FontWeight.w800,
                        color: Boli.cream,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),

          // ---- accessibility -------------------------------------------------
          const SectionHead('HOW YOU USE THE APP'),
          Container(
            decoration: Boli.card(),
            child: Column(
              children: [
                _Toggle(
                  icon: Icons.record_voice_over_rounded,
                  title: 'Voice only',
                  sub: 'No reading needed anywhere',
                  on: false,
                ),
                Divider(height: 1, color: Boli.sand, thickness: 1.5),
                _Toggle(
                  icon: Icons.text_fields_rounded,
                  title: 'Bigger text',
                  sub: 'Easier in bright sun',
                  on: true,
                ),
                Divider(height: 1, color: Boli.sand, thickness: 1.5),
                _Toggle(
                  icon: Icons.slow_motion_video_rounded,
                  title: 'Slower speech',
                  sub: 'Play audio at 0.7×',
                  on: true,
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
  const _Stat({required this.value, required this.label});
  @override
  Widget build(BuildContext context) => Expanded(
    child: Column(
      children: [
        Text(value, style: Boli.head(27, weight: 700, color: Boli.terracotta)),
        const SizedBox(height: 3),
        Text(
          label,
          textAlign: TextAlign.center,
          style: Boli.label(size: 10, color: Boli.inkSoft),
        ),
      ],
    ),
  );
}

class _Sound extends StatelessWidget {
  final String a, b, label;
  final double score;
  const _Sound({
    required this.a,
    required this.b,
    required this.label,
    required this.score,
  });

  @override
  Widget build(BuildContext context) {
    final c = Boli.forReadiness(score);
    return Row(
      children: [
        Container(
          width: 56,
          height: 56,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: c.withValues(alpha: .12),
            borderRadius: BorderRadius.circular(14),
          ),
          child: Text('$a/$b', style: Boli.head(21, weight: 600, color: c)),
        ),
        const SizedBox(width: 14),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label, style: Boli.body(15.5, weight: FontWeight.w700)),
              const SizedBox(height: 6),
              LayoutBuilder(
                builder: (_, box) => Container(
                  height: 7,
                  decoration: BoxDecoration(
                    color: Boli.sand,
                    borderRadius: BorderRadius.circular(5),
                  ),
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: Container(
                      width: box.maxWidth * score,
                      decoration: BoxDecoration(
                        color: c,
                        borderRadius: BorderRadius.circular(5),
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 12),
        Text(
          '${(score * 100).round()}%',
          style: Boli.body(14, weight: FontWeight.w800, color: c),
        ),
      ],
    );
  }
}

class _LangRow extends StatefulWidget {
  final Lang lang;
  const _LangRow({required this.lang});

  @override
  State<_LangRow> createState() => _LangRowState();
}

class _LangRowState extends State<_LangRow> {
  bool _isInstalled = false;
  bool _isDownloading = false;
  double _progress = 0.0;

  @override
  void initState() {
    super.initState();
    _checkStatus();
  }

  Future<void> _checkStatus() async {
    if (widget.lang.code == 'mr') {
      setState(() => _isInstalled = true);
      return;
    }
    final res = await BoliBridge.instance.checkLanguageInstalled(widget.lang.code);
    if (mounted) {
      setState(() {
        _isInstalled = (res['installed'] as bool?) ?? false;
      });
    }
  }

  Future<void> _onTap() async {
    if (_isDownloading) return;

    if (_isInstalled) {
      await BoliBridge.instance.setActiveLanguage(widget.lang.code);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            '${widget.lang.english} (${widget.lang.native}) is now active on Qualcomm NPU',
            style: Boli.body(14, color: Colors.white),
          ),
          backgroundColor: Boli.indigo,
          duration: const Duration(seconds: 2),
        ),
      );
      return;
    }

    // Trigger on-demand download
    setState(() {
      _isDownloading = true;
      _progress = 0.05;
    });

    final success = await BoliBridge.instance.downloadLanguage(
      widget.lang.code,
      onProgress: (p, s) {
        if (mounted) {
          setState(() {
            _progress = p;
          });
        }
      },
    );

    if (mounted) {
      setState(() {
        _isDownloading = false;
        _isInstalled = success;
      });
      if (success) {
        await BoliBridge.instance.setActiveLanguage(widget.lang.code);
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '${widget.lang.english} model pack downloaded and activated!',
              style: Boli.body(14, color: Colors.white),
            ),
            backgroundColor: Boli.leaf,
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) => InkWell(
    onTap: _onTap,
    child: SizedBox(
      height: Boli.tap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: Row(
          children: [
            SizedBox(
              width: 82,
              child: Text(widget.lang.native, style: Boli.head(21, weight: 600)),
            ),
            Expanded(
              child: Text(
                widget.lang.english,
                style: Boli.body(15, color: Boli.inkSoft),
              ),
            ),
            if (_isDownloading)
              Row(
                children: [
                  SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      value: _progress > 0.05 ? _progress : null,
                      color: Boli.marigold,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    '${(_progress * 100).toInt()}%',
                    style: Boli.body(13, weight: FontWeight.w700, color: Boli.marigold),
                  ),
                ],
              )
            else if (_isInstalled)
              Row(
                children: [
                  const Icon(
                    Icons.offline_pin_rounded,
                    size: 19,
                    color: Boli.leaf,
                  ),
                  const SizedBox(width: 6),
                  Text(
                    'On phone',
                    style: Boli.body(
                      13.5,
                      weight: FontWeight.w700,
                      color: Boli.leaf,
                    ),
                  ),
                ],
              )
            else
              Row(
                children: [
                  Text(
                    '${widget.lang.mb} MB',
                    style: Boli.body(13.5, color: Boli.inkSoft),
                  ),
                  const SizedBox(width: 8),
                  const Icon(
                    Icons.download_rounded,
                    size: 20,
                    color: Boli.inkSoft,
                  ),
                ],
              ),
          ],
        ),
      ),
    ),
  );
}

class _Toggle extends StatefulWidget {
  final IconData icon;
  final String title, sub;
  final bool on;
  const _Toggle({
    required this.icon,
    required this.title,
    required this.sub,
    required this.on,
  });
  @override
  State<_Toggle> createState() => _ToggleState();
}

class _ToggleState extends State<_Toggle> {
  late bool _on = widget.on;
  @override
  Widget build(BuildContext context) => SizedBox(
    height: 70,
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Row(
        children: [
          Icon(widget.icon, size: 23, color: _on ? Boli.peacock : Boli.inkSoft),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  widget.title,
                  style: Boli.body(16, weight: FontWeight.w700),
                ),
                Text(widget.sub, style: Boli.body(13.5, color: Boli.inkSoft)),
              ],
            ),
          ),
          Switch(
            value: _on,
            activeThumbColor: Boli.paper,
            activeTrackColor: Boli.peacock,
            onChanged: (v) => setState(() => _on = v),
          ),
        ],
      ),
    ),
  );
}
