import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'daily_mission_screen.dart';
import 'data.dart';
import 'practice_screen.dart';
import 'progress_screen.dart';
import 'theme.dart';
import 'tools_screen.dart';
import 'widgets.dart';

/// Three destinations, large targets, icon plus word. No hidden menus — a user
/// who reads haltingly should never have to discover anything behind a
/// hamburger.
class Shell extends StatefulWidget {
  final Lang l1, l2;
  final Job job;
  const Shell({
    super.key,
    required this.l1,
    required this.l2,
    required this.job,
  });

  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> {
  int _tab = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(
        index: _tab,
        children: [
          TodayScreen(l1: widget.l1, l2: widget.l2, job: widget.job),
          ToolsScreen(l2: widget.l2),
          ProgressScreen(l1: widget.l1, l2: widget.l2, job: widget.job),
        ],
      ),
      bottomNavigationBar: Container(
        decoration: BoxDecoration(
          color: Boli.paper,
          border: Border(top: BorderSide(color: Boli.sand, width: 2)),
        ),
        child: SafeArea(
          top: false,
          child: SizedBox(
            height: 68,
            child: Row(
              children: [
                _Tab(
                  icon: Icons.today_rounded,
                  label: 'Today',
                  on: _tab == 0,
                  onTap: () => setState(() => _tab = 0),
                ),
                _Tab(
                  icon: Icons.hub_rounded,
                  label: 'Tools',
                  on: _tab == 1,
                  onTap: () => setState(() => _tab = 1),
                ),
                _Tab(
                  icon: Icons.insights_rounded,
                  label: 'Progress',
                  on: _tab == 2,
                  onTap: () => setState(() => _tab = 2),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Tab extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool on;
  final VoidCallback onTap;
  const _Tab({
    required this.icon,
    required this.label,
    required this.on,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) => Expanded(
    child: GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(icon, size: 26, color: on ? Boli.terracotta : Boli.inkSoft),
          const SizedBox(height: 3),
          Text(
            label,
            style: Boli.body(
              12.5,
              weight: on ? FontWeight.w800 : FontWeight.w600,
              color: on ? Boli.terracotta : Boli.inkSoft,
            ),
          ),
        ],
      ),
    ),
  );
}

/// The board. Not a path — a set of situations you can enter in any order,
/// because someone who has to report an injury today cannot be told to finish
/// unit two first.
class TodayScreen extends StatefulWidget {
  final Lang l1, l2;
  final Job job;
  const TodayScreen({
    super.key,
    required this.l1,
    required this.l2,
    required this.job,
  });

  @override
  State<TodayScreen> createState() => _TodayScreenState();
}

class _TodayScreenState extends State<TodayScreen> {
  int _filter = 0;
  bool _voiceFirstMode = false;
  final _readiness = <String, double>{};

  static const _day = 6, _ofDays = 21;

  List<Situation> get _allSituations =>
      getSituationsFor(l2: widget.l2, job: widget.job);

  List<Situation> get _visible {
    final list = _allSituations;
    if (_filter == 0) return list;
    final ctx = Ctx.values[_filter - 1];
    return list.where((s) => s.ctx == ctx).toList();
  }

  double _read(Situation s) => _readiness[s.title] ?? s.readiness;

  double get _overall {
    final list = _allSituations;
    if (list.isEmpty) return 0.0;
    return list.map(_read).fold(0.0, (a, b) => a + b) / list.length;
  }

  Future<void> _open(Situation s) async {
    if (s.exercises.isEmpty) {
      _comingSoon(s);
      return;
    }
    final done = await Navigator.of(context).push<double>(
      PageRouteBuilder(
        transitionDuration: const Duration(milliseconds: 380),
        pageBuilder: (_, __, ___) => PracticeScreen(
          situation: s,
          isVoiceFirst: _voiceFirstMode,
        ),
        transitionsBuilder: (_, a, __, c) {
          final cu = CurvedAnimation(parent: a, curve: Curves.easeOutCubic);
          return FadeTransition(
            opacity: cu,
            child: SlideTransition(
              position: Tween(
                begin: const Offset(0, .06),
                end: Offset.zero,
              ).animate(cu),
              child: c,
            ),
          );
        },
      ),
    );
    if (done != null && mounted) {
      setState(() => _readiness[s.title] = done.clamp(_read(s), 1.0));
    }
  }

  void _comingSoon(Situation s) {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (_) => Container(
        decoration: const BoxDecoration(
          color: Boli.paper,
          borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
        ),
        padding: const EdgeInsets.fromLTRB(22, 16, 22, 28),
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
            const SizedBox(height: 18),
            Text(s.title, style: Boli.head(23, weight: 700)),
            const SizedBox(height: 2),
            Text(s.native, style: Boli.body(17, color: Boli.inkSoft)),
            const SizedBox(height: 16),
            HandloomBorder(color: Boli.sand, height: 8, dense: true),
            const SizedBox(height: 16),
            Text(
              '${s.phrases} phrases are written for this situation. '
              'Gemma on-device AI can generate personalized workplace drills right now.',
              style: Boli.body(15, color: Boli.inkSoft),
            ),
            const SizedBox(height: 20),
            BigButton(
              label: 'Gemma सराव सुरू करा · Start AI Drills',
              color: Boli.terracotta,
              onTap: () => _generateAndStartDrills(s),
            ),
            const SizedBox(height: 10),
            BigButton(
              label: 'Close',
              outline: true,
              color: Boli.inkSoft,
              onTap: () => Navigator.of(context).pop(),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _generateAndStartDrills(Situation s) async {
    Navigator.of(context).pop(); // Close sheet

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (_) => Center(
        child: Container(
          margin: const EdgeInsets.all(24),
          padding: const EdgeInsets.all(24),
          decoration: BoxDecoration(
            color: Boli.paper,
            borderRadius: BorderRadius.circular(18),
            boxShadow: Boli.lift(y: 4, blur: 16),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const SizedBox(
                width: 32,
                height: 32,
                child: CircularProgressIndicator(color: Boli.marigold, strokeWidth: 3),
              ),
              const SizedBox(height: 16),
              Text(
                'Gemma सराव तयार करत आहे…',
                style: Boli.head(17, weight: 700),
              ),
              const SizedBox(height: 6),
              Text(
                'Generating dynamic drills for ${widget.job.title}…',
                style: Boli.body(13, color: Boli.inkSoft),
              ),
            ],
          ),
        ),
      ),
    );

    try {
      final res = await const MethodChannel('boli/engine_methods').invokeMapMethod<String, dynamic>(
        'generatePracticeDrills',
        {'situation': s.title, 'domain': widget.job.title},
      );

      if (mounted) Navigator.of(context, rootNavigator: true).pop(); // dismiss loading

      final rawList = res?['exercises'] as List<dynamic>? ?? [];
      final generated = <Exercise>[];

      for (final item in rawList) {
        if (item is Map) {
          final kindStr = item['kind'] as String? ?? 'speak';
          final kind = kindStr == 'choice' ? Kind.choice : Kind.speak;
          final prompt = item['prompt'] as String? ?? '';
          final target = item['target_text'] as String? ?? '';
          final roman = item['roman'] as String? ?? '';
          final trans = item['translation'] as String? ?? '';
          final options = (item['options'] as List<dynamic>?)?.map((e) => e.toString()).toList() ?? [];
          final answer = item['answer_index'] as int? ?? 0;

          generated.add(Exercise(
            kind: kind,
            prompt: prompt,
            marathi: target,
            roman: roman,
            english: trans,
            options: options,
            answer: answer,
          ));
        }
      }

      if (generated.isNotEmpty && mounted) {
        final dynamicSit = s.copyWith(exercises: generated);
        _open(dynamicSit);
      }
    } catch (_) {
      if (mounted) Navigator.of(context, rootNavigator: true).pop();
    }
  }

  @override
  Widget build(BuildContext context) {
    final vis = _visible;
    return SafeArea(
      bottom: false,
      child: Column(
        children: [
          // ---- header: who you are, and how much time is left --------------
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
            child: Column(
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Text(
                                widget.l1.native,
                                style: Boli.body(15, weight: FontWeight.w700),
                              ),
                              const Padding(
                                padding: EdgeInsets.symmetric(horizontal: 7),
                                child: Icon(
                                  Icons.arrow_forward_rounded,
                                  size: 15,
                                  color: Boli.terracotta,
                                ),
                              ),
                              Text(
                                widget.l2.native,
                                style: Boli.body(
                                  15,
                                  weight: FontWeight.w700,
                                  color: Boli.terracotta,
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 2),
                          Text(
                            widget.job.title,
                            style: Boli.body(13.5, color: Boli.inkSoft),
                          ),
                        ],
                      ),
                    ),
                    GestureDetector(
                      onTap: () {
                        setState(() => _voiceFirstMode = !_voiceFirstMode);
                        HapticFeedback.lightImpact();
                      },
                      child: AnimatedContainer(
                        duration: const Duration(milliseconds: 200),
                        height: 44,
                        padding: const EdgeInsets.symmetric(horizontal: 11),
                        decoration: BoxDecoration(
                          color: _voiceFirstMode
                              ? Boli.peacock
                              : Boli.peacock.withValues(alpha: .10),
                          borderRadius: BorderRadius.circular(22),
                          border: Border.all(
                            color: _voiceFirstMode
                                ? Boli.peacock
                                : Boli.peacock.withValues(alpha: .3),
                            width: 1.5,
                          ),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              _voiceFirstMode
                                  ? Icons.record_voice_over_rounded
                                  : Icons.mic_none_rounded,
                              size: 16,
                              color:
                                  _voiceFirstMode ? Boli.cream : Boli.peacock,
                            ),
                            const SizedBox(width: 5),
                            Text(
                              _voiceFirstMode ? 'ध्वनी ON' : 'ध्वनी',
                              style: Boli.body(
                                12,
                                weight: FontWeight.w800,
                                color:
                                    _voiceFirstMode ? Boli.cream : Boli.peacock,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Container(
                      height: 44,
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      decoration: BoxDecoration(
                        color: Boli.leaf.withValues(alpha: .12),
                        borderRadius: BorderRadius.circular(22),
                      ),
                      child: Row(
                        children: [
                          const Icon(
                            Icons.cloud_off_rounded,
                            size: 16,
                            color: Boli.leaf,
                          ),
                          const SizedBox(width: 6),
                          Text(
                            'Offline',
                            style: Boli.body(
                              13.5,
                              weight: FontWeight.w800,
                              color: Boli.leaf,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                // ---- the deadline, stated plainly, never as a punishment ----
                Container(
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: Boli.ink,
                    borderRadius: BorderRadius.circular(18),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  'DAY $_day OF $_ofDays',
                                  style: Boli.label(
                                    color: Boli.marigold,
                                    size: 11.5,
                                  ),
                                ),
                                const SizedBox(height: 5),
                                Text(
                                  '${(_overall * 100).round()}% ready for work',
                                  style: Boli.head(
                                    25,
                                    weight: 700,
                                    color: Boli.cream,
                                  ),
                                ),
                                const SizedBox(height: 3),
                                Text(
                                  '${situations.where((s) => _read(s) >= 1).length} of ${situations.length} situations covered',
                                  style: Boli.body(
                                    13.5,
                                    color: Boli.cream.withValues(alpha: .7),
                                  ),
                                ),
                              ],
                            ),
                          ),
                          ReadinessRing(value: _overall, size: 62),
                        ],
                      ),
                      const SizedBox(height: 14),
                      // Practice rhythm — a record, not a streak to protect.
                      Row(
                        children: List.generate(7, (i) {
                          const done = [
                            true,
                            true,
                            false,
                            true,
                            true,
                            true,
                            false,
                          ];
                          const names = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
                          return Expanded(
                            child: Column(
                              children: [
                                Container(
                                  height: 6,
                                  margin: const EdgeInsets.symmetric(
                                    horizontal: 2.5,
                                  ),
                                  decoration: BoxDecoration(
                                    color: done[i]
                                        ? Boli.marigold
                                        : Boli.cream.withValues(alpha: .18),
                                    borderRadius: BorderRadius.circular(4),
                                  ),
                                ),
                                const SizedBox(height: 5),
                                Text(
                                  names[i],
                                  style: Boli.body(
                                    10.5,
                                    color: Boli.cream.withValues(alpha: .5),
                                  ),
                                ),
                              ],
                            ),
                          );
                        }),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 16),
              ],
            ),
          ),
          FilterChipRow(
            labels: ['All', ...Ctx.values.map((c) => c.label)],
            icons: [Icons.apps_rounded, ...Ctx.values.map((c) => c.icon)],
            selected: _filter,
            onSelect: (i) => setState(() => _filter = i),
          ),
          const SizedBox(height: 14),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
              children: [
                // ---- Hero: Daily Mission Card ----
                Container(
                  margin: const EdgeInsets.only(bottom: 20),
                  padding: const EdgeInsets.all(18),
                  decoration: BoxDecoration(
                    color: Boli.paper,
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: Boli.marigold.withValues(alpha: 0.4), width: 1.5),
                    boxShadow: Boli.lift(y: 4, blur: 14),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                            decoration: BoxDecoration(
                              color: Boli.marigold.withValues(alpha: 0.18),
                              borderRadius: BorderRadius.circular(10),
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                const Icon(Icons.flash_on_rounded, size: 14, color: Boli.terracotta),
                                const SizedBox(width: 4),
                                Text(
                                  'आजचे मिशन · DAILY MISSION',
                                  style: Boli.label(color: Boli.terracotta, size: 10.5),
                                ),
                              ],
                            ),
                          ),
                          const Spacer(),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                            decoration: BoxDecoration(
                              color: Boli.leaf.withValues(alpha: 0.12),
                              borderRadius: BorderRadius.circular(8),
                            ),
                            child: Text(
                              '2–3 min loop',
                              style: Boli.body(11, weight: FontWeight.w700, color: Boli.leaf),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      Text(
                        'आजचे वैयक्तिक आव्हान',
                        style: Boli.head(18, weight: 700),
                      ),
                      const SizedBox(height: 3),
                      Text(
                        'Personalized workplace mission based on your vocabulary, pronunciation & ${widget.job.title} scenarios.',
                        style: Boli.body(13, color: Boli.inkSoft),
                      ),
                      const SizedBox(height: 14),
                      Row(
                        children: [
                          Expanded(
                            child: Row(
                              children: [
                                const Icon(Icons.psychology_outlined, size: 16, color: Boli.terracotta),
                                const SizedBox(width: 6),
                                Expanded(
                                  child: Text(
                                    'Gemma 3n · IndicConformer · FastPitch',
                                    style: Boli.body(11.5, color: Boli.inkSoft),
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          ElevatedButton.icon(
                            onPressed: () {
                              Navigator.of(context).push(
                                MaterialPageRoute(
                                  builder: (_) => DailyMissionScreen(
                                    job: widget.job,
                                    l1: widget.l1,
                                    l2: widget.l2,
                                  ),
                                ),
                              );
                            },
                            icon: const Icon(Icons.play_arrow_rounded, size: 18),
                            label: const Text('सुरू करा'),
                            style: ElevatedButton.styleFrom(
                              backgroundColor: Boli.terracotta,
                              foregroundColor: Boli.cream,
                              elevation: 0,
                              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                              textStyle: Boli.body(13, weight: FontWeight.w700),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                const SectionHead('WHAT YOU CAN HANDLE'),
                for (int i = 0; i < vis.length; i++)
                  SituationCard(
                    key: ValueKey('${vis[i].title}_${_read(vis[i])}'),
                    index: i,
                    title: vis[i].title,
                    native: vis[i].native,
                    contextLabel: vis[i].ctx.label,
                    icon: vis[i].ctx.icon,
                    phrases: vis[i].phrases,
                    readiness: _read(vis[i]),
                    urgent: vis[i].urgent,
                    onTap: () => _open(vis[i]),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
