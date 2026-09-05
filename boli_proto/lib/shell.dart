import 'package:flutter/material.dart';
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
  final _readiness = <String, double>{};

  static const _day = 6, _ofDays = 21;

  List<Situation> get _visible {
    if (_filter == 0) return situations;
    final ctx = Ctx.values[_filter - 1];
    return situations.where((s) => s.ctx == ctx).toList();
  }

  double _read(Situation s) => _readiness[s.title] ?? s.readiness;

  double get _overall =>
      situations.map(_read).fold(0.0, (a, b) => a + b) / situations.length;

  Future<void> _open(Situation s) async {
    if (s.exercises.isEmpty) {
      _comingSoon(s);
      return;
    }
    final done = await Navigator.of(context).push<double>(
      PageRouteBuilder(
        transitionDuration: const Duration(milliseconds: 380),
        pageBuilder: (_, __, ___) => PracticeScreen(situation: s),
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
              'The Marathi pack on this phone covers four situations end to end; '
              'the rest ship with the full content build.',
              style: Boli.body(15, color: Boli.inkSoft),
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
      ),
    );
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
