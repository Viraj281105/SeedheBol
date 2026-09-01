import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'data.dart';
import 'lesson_screen.dart';
import 'theme.dart';
import 'widgets.dart';

/// The lesson map: units stacked vertically, lessons as nodes on a winding
/// path. Progress is held in memory only — this is a demo, not a product.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with TickerProviderStateMixin {
  int xp = 240;
  int streak = 5;
  int hearts = 3;

  /// Flat index of the next unfinished lesson across all units.
  int completed = 3;

  late final AnimationController _enter =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 900))..forward();

  @override
  void dispose() {
    _enter.dispose();
    super.dispose();
  }

  List<Lesson> get _allLessons => [for (final u in units) ...u.lessons];

  Future<void> _open(int flatIndex, Lesson lesson) async {
    final result = await Navigator.of(context).push<LessonResult>(
      PageRouteBuilder(
        transitionDuration: const Duration(milliseconds: 420),
        reverseTransitionDuration: const Duration(milliseconds: 300),
        pageBuilder: (_, __, ___) => LessonScreen(lesson: lesson, hearts: hearts),
        transitionsBuilder: (_, anim, __, child) {
          final curved = CurvedAnimation(parent: anim, curve: Curves.easeOutCubic);
          return FadeTransition(
            opacity: curved,
            child: ScaleTransition(scale: Tween(begin: .94, end: 1.0).animate(curved), child: child),
          );
        },
      ),
    );
    if (result == null || !mounted) return;
    setState(() {
      xp += result.xp;
      hearts = result.heartsLeft;
      if (result.passed && flatIndex == completed) completed++;
      if (result.passed) streak = math.max(streak, streak);
    });
  }

  @override
  Widget build(BuildContext context) {
    final lessons = _allLessons;
    return Scaffold(
      body: Stack(
        children: [
          const Positioned.fill(child: RangoliBackdrop()),
          SafeArea(
            child: Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 12, 20, 8),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      StatBar(hearts: hearts, streak: streak, xp: xp),
                      const SizedBox(height: 16),
                      Row(
                        children: [
                          Container(
                            padding: const EdgeInsets.all(10),
                            decoration: BoxDecoration(
                              gradient: Desi.gold,
                              borderRadius: BorderRadius.circular(14),
                              boxShadow: Desi.lift(y: 4, blur: 10),
                            ),
                            child: const Icon(Icons.graphic_eq_rounded, color: Colors.white, size: 22),
                          ),
                          const SizedBox(width: 12),
                          Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              const Text('बोली',
                                  style: TextStyle(
                                      fontSize: 26, fontWeight: FontWeight.w800, color: Desi.indigo, height: 1.1)),
                              Text('Marathi · works offline',
                                  style: TextStyle(
                                      fontSize: 12.5,
                                      fontWeight: FontWeight.w600,
                                      color: Desi.ink.withValues(alpha: .55))),
                            ],
                          ),
                          const Spacer(),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                            decoration: BoxDecoration(
                              color: Desi.leaf.withValues(alpha: .14),
                              borderRadius: BorderRadius.circular(20),
                            ),
                            child: const Row(mainAxisSize: MainAxisSize.min, children: [
                              Icon(Icons.airplanemode_active_rounded, size: 14, color: Desi.leaf),
                              SizedBox(width: 4),
                              Text('On-device',
                                  style: TextStyle(
                                      fontSize: 11, fontWeight: FontWeight.w800, color: Desi.leaf)),
                            ]),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                Expanded(
                  child: ListView(
                    padding: const EdgeInsets.fromLTRB(20, 8, 20, 40),
                    children: [
                      for (int u = 0; u < units.length; u++) ...[
                        _UnitBanner(unit: units[u], index: u, enter: _enter),
                        const SizedBox(height: 8),
                        for (int l = 0; l < units[u].lessons.length; l++)
                          _PathNode(
                            lesson: units[u].lessons[l],
                            flatIndex: lessons.indexOf(units[u].lessons[l]),
                            completed: completed,
                            enter: _enter,
                            onTap: _open,
                          ),
                        const SizedBox(height: 18),
                      ],
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _UnitBanner extends StatelessWidget {
  final Unit unit;
  final int index;
  final Animation<double> enter;
  const _UnitBanner({required this.unit, required this.index, required this.enter});

  @override
  Widget build(BuildContext context) {
    final a = CurvedAnimation(
      parent: enter,
      curve: Interval((index * .12).clamp(0, .8), 1, curve: Curves.easeOutCubic),
    );
    return FadeTransition(
      opacity: a,
      child: SlideTransition(
        position: Tween(begin: const Offset(0, .18), end: Offset.zero).animate(a),
        child: Container(
          margin: const EdgeInsets.only(top: 14, bottom: 6),
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
          decoration: BoxDecoration(
            gradient: unit.gradient,
            borderRadius: BorderRadius.circular(20),
            boxShadow: Desi.lift(),
          ),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(unit.subtitle.toUpperCase(),
                        style: TextStyle(
                            fontSize: 10.5,
                            letterSpacing: 1.4,
                            fontWeight: FontWeight.w800,
                            color: Colors.white.withValues(alpha: .82))),
                    const SizedBox(height: 3),
                    Text(unit.title,
                        style: const TextStyle(
                            fontSize: 22, fontWeight: FontWeight.w800, color: Colors.white, height: 1.2)),
                  ],
                ),
              ),
              Icon(Icons.auto_awesome_rounded, color: Colors.white.withValues(alpha: .55), size: 26),
            ],
          ),
        ),
      ),
    );
  }
}

/// One circular lesson node, offset left/right to suggest a winding path.
class _PathNode extends StatelessWidget {
  final Lesson lesson;
  final int flatIndex, completed;
  final Animation<double> enter;
  final void Function(int, Lesson) onTap;

  const _PathNode({
    required this.lesson,
    required this.flatIndex,
    required this.completed,
    required this.enter,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final done = flatIndex < completed;
    final current = flatIndex == completed;
    final locked = flatIndex > completed;

    final a = CurvedAnimation(
      parent: enter,
      curve: Interval((.1 + flatIndex * .09).clamp(0, .9), 1, curve: Curves.easeOutBack),
    );

    // Gentle serpentine: -1, 0, +1, 0, -1 ...
    final shift = math.sin(flatIndex * 1.05) * 52;

    final face = done ? Desi.leaf : (current ? Desi.marigold : Desi.sand);

    return ScaleTransition(
      scale: a,
      child: FadeTransition(
        opacity: enter,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 10),
          child: Transform.translate(
            offset: Offset(shift, 0),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Column(
                  children: [
                    _Node(
                      face: face,
                      locked: locked,
                      current: current,
                      done: done,
                      icon: lesson.icon,
                      onTap: locked ? null : () => onTap(flatIndex, lesson),
                    ),
                    const SizedBox(height: 8),
                    SizedBox(
                      width: 130,
                      child: Text(
                        lesson.title,
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                          color: locked ? Desi.ink.withValues(alpha: .35) : Desi.ink,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Node extends StatefulWidget {
  final Color face;
  final bool locked, current, done;
  final IconData icon;
  final VoidCallback? onTap;
  const _Node({
    required this.face,
    required this.locked,
    required this.current,
    required this.done,
    required this.icon,
    this.onTap,
  });

  @override
  State<_Node> createState() => _NodeState();
}

class _NodeState extends State<_Node> with SingleTickerProviderStateMixin {
  late final AnimationController _pulse =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 1500))..repeat(reverse: true);

  @override
  void dispose() {
    _pulse.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: widget.onTap,
      child: AnimatedBuilder(
        animation: _pulse,
        builder: (_, __) {
          final halo = widget.current ? 6 + _pulse.value * 8 : 0.0;
          return Container(
            width: 78,
            height: 78,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: widget.face,
              border: Border.all(
                color: widget.face == Desi.sand ? Desi.sand : Colors.white.withValues(alpha: .5),
                width: 3,
              ),
              boxShadow: [
                if (widget.current)
                  BoxShadow(
                      color: Desi.marigold.withValues(alpha: .38), blurRadius: halo, spreadRadius: halo * .4),
                BoxShadow(
                    color: Desi.indigo.withValues(alpha: widget.locked ? .06 : .22),
                    offset: const Offset(0, 5),
                    blurRadius: 12),
              ],
            ),
            child: Icon(
              widget.locked
                  ? Icons.lock_rounded
                  : (widget.done ? Icons.check_rounded : widget.icon),
              color: widget.locked ? Desi.ink.withValues(alpha: .3) : Colors.white,
              size: 32,
            ),
          );
        },
      ),
    );
  }
}
