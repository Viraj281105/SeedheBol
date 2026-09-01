import 'package:flutter/material.dart';
import 'data.dart';
import 'shell.dart';
import 'theme.dart';
import 'widgets.dart';

/// Three steps: the language you speak, the language you need, and the work you
/// do. Every step is answerable by tapping a word in the user's own script, so
/// nothing here requires reading English.
class Onboarding extends StatefulWidget {
  const Onboarding({super.key});

  @override
  State<Onboarding> createState() => _OnboardingState();
}

class _OnboardingState extends State<Onboarding> {
  int _step = 0;
  int _l1 = 1; // Hindi
  int _l2 = 0; // Marathi — the pair the prototype actually runs
  int _job = 0;

  void _next() {
    if (_step < 2) {
      setState(() => _step++);
    } else {
      Navigator.of(context).pushReplacement(PageRouteBuilder(
        transitionDuration: const Duration(milliseconds: 500),
        pageBuilder: (_, __, ___) => Shell(l1: languages[_l1], l2: languages[_l2], job: jobs[_job]),
        transitionsBuilder: (_, a, __, c) => FadeTransition(opacity: a, child: c),
      ));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 14, 20, 0),
              child: Row(
                children: [
                  if (_step > 0)
                    GestureDetector(
                      onTap: () => setState(() => _step--),
                      child: const SizedBox(
                          width: 44, height: 44, child: Icon(Icons.arrow_back_rounded, size: 26)),
                    )
                  else
                    const SizedBox(width: 44, height: 44),
                  Expanded(
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: List.generate(
                        3,
                        (i) => AnimatedContainer(
                          duration: const Duration(milliseconds: 260),
                          margin: const EdgeInsets.symmetric(horizontal: 4),
                          width: i == _step ? 26 : 9,
                          height: 9,
                          decoration: BoxDecoration(
                            color: i <= _step ? Boli.marigold : Boli.sand,
                            borderRadius: BorderRadius.circular(6),
                          ),
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 44),
                ],
              ),
            ),
            Expanded(
              child: AnimatedSwitcher(
                duration: const Duration(milliseconds: 300),
                transitionBuilder: (child, a) => FadeTransition(
                  opacity: a,
                  child: SlideTransition(
                      position: Tween(begin: const Offset(.08, 0), end: Offset.zero).animate(a),
                      child: child),
                ),
                child: switch (_step) {
                  0 => _LangStep(
                      key: const ValueKey(0),
                      title: 'Which language do you speak?',
                      caption: 'तुम्ही कोणती भाषा बोलता?',
                      selected: _l1,
                      onSelect: (i) => setState(() => _l1 = i),
                      showInstall: false,
                    ),
                  1 => _LangStep(
                      key: const ValueKey(1),
                      title: 'Which language do you need?',
                      caption: 'तुम्हाला कोणती भाषा हवी आहे?',
                      selected: _l2,
                      onSelect: (i) => setState(() => _l2 = i),
                      showInstall: true,
                    ),
                  _ => _JobStep(
                      key: const ValueKey(2),
                      selected: _job,
                      onSelect: (i) => setState(() => _job = i),
                    ),
                },
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 18),
              child: Column(
                children: [
                  if (_step == 1 && !languages[_l2].installed)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Row(children: [
                        const Icon(Icons.download_rounded, size: 18, color: Boli.inkSoft),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            '${languages[_l2].english} needs a ${languages[_l2].mb} MB download. Marathi is already on this phone.',
                            style: Boli.body(13.5, color: Boli.inkSoft),
                          ),
                        ),
                      ]),
                    ),
                  BigButton(
                    label: _step == 2 ? 'Start' : 'Next',
                    icon: Icons.arrow_forward_rounded,
                    onTap: _next,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _LangStep extends StatelessWidget {
  final String title, caption;
  final int selected;
  final ValueChanged<int> onSelect;
  final bool showInstall;
  const _LangStep({
    super.key,
    required this.title,
    required this.caption,
    required this.selected,
    required this.onSelect,
    required this.showInstall,
  });

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: Boli.head(27, weight: 700)),
                const SizedBox(height: 4),
                Text(caption, style: Boli.body(17, color: Boli.inkSoft)),
                const SizedBox(height: 16),
              ],
            ),
          ),
          Expanded(
            child: GridView.builder(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 12),
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 2,
                childAspectRatio: 2.0,
                crossAxisSpacing: 10,
                mainAxisSpacing: 10,
              ),
              itemCount: languages.length,
              itemBuilder: (_, i) {
                final l = languages[i];
                final on = i == selected;
                return GestureDetector(
                  onTap: () => onSelect(i),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 170),
                    padding: const EdgeInsets.symmetric(horizontal: 14),
                    decoration: BoxDecoration(
                      color: on ? Boli.ink : Boli.paper,
                      border: Border.all(color: on ? Boli.ink : Boli.sand, width: 2.5),
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(l.native,
                            style: Boli.head(24, weight: 600, color: on ? Boli.cream : Boli.ink)),
                        const SizedBox(height: 1),
                        Row(children: [
                          Flexible(
                            child: Text(l.english,
                                overflow: TextOverflow.ellipsis,
                                style: Boli.body(13,
                                    color: on ? Boli.cream.withValues(alpha: .75) : Boli.inkSoft)),
                          ),
                          if (showInstall && l.installed) ...[
                            const SizedBox(width: 6),
                            Icon(Icons.offline_pin_rounded,
                                size: 15, color: on ? Boli.marigold : Boli.leaf),
                          ],
                        ]),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      );
}

class _JobStep extends StatelessWidget {
  final int selected;
  final ValueChanged<int> onSelect;
  const _JobStep({super.key, required this.selected, required this.onSelect});

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('What work do you do?', style: Boli.head(27, weight: 700)),
                const SizedBox(height: 4),
                Text('This decides which phrases come first.',
                    style: Boli.body(17, color: Boli.inkSoft)),
                const SizedBox(height: 16),
              ],
            ),
          ),
          Expanded(
            child: ListView.separated(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 12),
              itemCount: jobs.length,
              separatorBuilder: (_, __) => const SizedBox(height: 9),
              itemBuilder: (_, i) {
                final j = jobs[i];
                final on = i == selected;
                return GestureDetector(
                  onTap: () => onSelect(i),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 170),
                    height: 70,
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    decoration: BoxDecoration(
                      color: on ? Boli.ink : Boli.paper,
                      border: Border.all(color: on ? Boli.ink : Boli.sand, width: 2.5),
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Row(children: [
                      Icon(j.icon, size: 26, color: on ? Boli.marigold : Boli.peacock),
                      const SizedBox(width: 14),
                      Expanded(
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(j.title,
                                style: Boli.body(16.5,
                                    weight: FontWeight.w700, color: on ? Boli.cream : Boli.ink)),
                            Text(j.native,
                                style: Boli.body(14,
                                    color: on ? Boli.cream.withValues(alpha: .7) : Boli.inkSoft)),
                          ],
                        ),
                      ),
                      if (on) const Icon(Icons.check_circle_rounded, color: Boli.marigold, size: 24),
                    ]),
                  ),
                );
              },
            ),
          ),
        ],
      );
}
