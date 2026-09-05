import 'dart:async';
import 'package:flutter/material.dart';
import 'bridge/boli_bridge.dart';
import 'camera_lesson_screen.dart';
import 'data.dart';
import 'listen_around_screen.dart';
import 'roleplay_screen.dart';
import 'theme.dart';
import 'widgets.dart';
import 'with_someone_screen.dart';

/// Phone-native capabilities, surfaced as interactive learning tools.
///
/// Powers on-device Gemma-2B LLM inference, IndicConformer ASR,
/// OCR vision, and hardware telemetry.
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
        'Vivo Office Kit & Telemetry',
        'ऑफिस किट व टेलिमेट्री',
        'Real-time NPU thermal headroom & student assessment sync',
        Icons.hub_rounded,
        Boli.peacock,
      ),
      _T(
        'Practise with someone',
        'सोबत सराव',
        'Tap two phones together, no internet',
        Icons.contactless_rounded,
        Boli.marigold,
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
          const SizedBox(height: 18),
          const _AmbientMiningServiceCard(),
          const SizedBox(height: 20),
          const SectionHead('PHONE-NATIVE ON-DEVICE CAPABILITIES'),
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
      onTap: () {
        if (index == 0) {
          // Camera → OCR → Gemma → MicroLesson
          Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => const CameraLessonScreen()),
          );
        } else if (index == 1) {
          // Listen around me — Gemma + IndicConformer + FastPitch
          Navigator.of(context).push(
            MaterialPageRoute(
              builder: (_) => const ListenAroundScreen(),
            ),
          );
        } else if (index == 2) {
          // Roleplay conversation — Gemma-powered
          Navigator.of(context).push(
            MaterialPageRoute(
              builder: (_) => const RoleplayScreen(
                scenario: 'Talking to a supervisor',
                scenarioNative: 'संवाद — सुपरवायझरशी',
              ),
            ),
          );
        } else if (index == 3) {
          // Vivo Office Kit & Hardware Telemetry Hub
          showModalBottomSheet(
            context: context,
            backgroundColor: Colors.transparent,
            isScrollControlled: true,
            builder: (_) => const _OfficeKitTelemetrySheet(),
          );
        } else if (index == 4) {
          // Practise with someone — Gemma-powered peer translation & coaching
          Navigator.of(context).push(
            MaterialPageRoute(
              builder: (_) => const WithSomeoneScreen(),
            ),
          );
        }
      },
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

class _OfficeKitTelemetrySheet extends StatefulWidget {
  const _OfficeKitTelemetrySheet();

  @override
  State<_OfficeKitTelemetrySheet> createState() => _OfficeKitTelemetrySheetState();
}

class _OfficeKitTelemetrySheetState extends State<_OfficeKitTelemetrySheet> {
  Map<String, dynamic>? _telemetry;
  bool _loading = true;
  bool _exporting = false;
  String? _exportPath;
  String? _exportError;

  @override
  void initState() {
    super.initState();
    _fetchTelemetry();
  }

  Future<void> _fetchTelemetry() async {
    setState(() => _loading = true);
    try {
      final t = await BoliBridge.instance.getHardwareTelemetry();
      if (mounted) {
        setState(() {
          _telemetry = t;
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _exportData() async {
    setState(() {
      _exporting = true;
      _exportError = null;
    });
    try {
      final res = await BoliBridge.instance.exportOfficeKitData();
      if (mounted) {
        setState(() {
          _exporting = false;
          _exportPath = res['file_path'] as String? ?? 'officekit_export.json';
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _exporting = false;
          _exportError = e.toString();
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = _telemetry ?? {};
    final soc = t['soc'] as String? ?? 'Unavailable';
    final npu = t['npu_provider'] as String? ?? 'Unavailable';
    final mem = t['runtime_memory_mb']?.toString() ?? 'Unavailable';
    final headroomRaw = (t['thermal_headroom'] as num?)?.toDouble();
    final headroomPct = headroomRaw != null ? (headroomRaw * 100).round() : null;
    final isAirplane = t['airplane_mode'] as bool? ?? false;

    return Container(
      decoration: const BoxDecoration(
        color: Boli.paper,
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: Container(
              width: 44,
              height: 4,
              decoration: BoxDecoration(
                color: Boli.sand,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: Boli.peacock.withValues(alpha: .12),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: const Icon(Icons.hub_rounded, size: 22, color: Boli.peacock),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Vivo Office Kit & Telemetry', style: Boli.head(20, weight: 700)),
                    Text('ऑफिस किट व हार्डवेअर टेलिमेट्री', style: Boli.label(size: 11, color: Boli.inkSoft)),
                  ],
                ),
              ),
              if (_loading)
                const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
              else
                IconButton(
                  icon: const Icon(Icons.refresh_rounded, size: 20, color: Boli.peacock),
                  onPressed: _fetchTelemetry,
                ),
            ],
          ),
          const SizedBox(height: 18),

          // Telemetry Cards Grid
          Container(
            padding: const EdgeInsets.all(16),
            decoration: Boli.card(fill: Boli.cream),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text('HARDWARE TELEMETRY', style: Boli.label(size: 11, color: Boli.inkSoft)),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(
                        color: isAirplane ? Boli.leaf.withValues(alpha: .15) : Boli.marigold.withValues(alpha: .15),
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(isAirplane ? Icons.airplanemode_active_rounded : Icons.wifi_rounded,
                              size: 13, color: isAirplane ? Boli.leaf : Boli.ink),
                          const SizedBox(width: 4),
                          Text(
                            isAirplane ? 'Airplane Mode (Offline)' : 'Network Active',
                            style: Boli.label(size: 10.5, color: isAirplane ? Boli.leaf : Boli.ink),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                _telemetryRow('SoC Architecture', soc, Icons.memory_rounded),
                const SizedBox(height: 8),
                _telemetryRow('Execution Engine', npu, Icons.bolt_rounded),
                const SizedBox(height: 8),
                _telemetryRow(
                  'RAM Heap Usage',
                  mem != 'Unavailable' ? '$mem MB on-device' : 'Unavailable',
                  Icons.storage_rounded,
                ),
                const SizedBox(height: 8),
                _telemetryRow(
                  'Thermal Headroom',
                  headroomPct != null ? '$headroomPct% (Nominal / Cooling OK)' : 'Unavailable',
                  Icons.thermostat_rounded,
                  color: headroomPct == null
                      ? Boli.inkSoft
                      : headroomPct < 70 ? Boli.leaf : Boli.terracotta,
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // Office Kit File Transfer & Remote Sync
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Boli.indigo.withValues(alpha: .08),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: Boli.indigo.withValues(alpha: .2)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(Icons.sync_alt_rounded, size: 18, color: Boli.indigo),
                    const SizedBox(width: 8),
                    Text(
                      'VIVO OFFICE KIT ASSESSMENT SYNC',
                      style: Boli.label(size: 11, color: Boli.indigo),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  'Exports student oral reading fluency, pronunciation diagnosis records, '
                  'and hardware logs for Free Transfer sync to laptop/PC.',
                  style: Boli.body(13, color: Boli.inkSoft, height: 1.4),
                ),
                if (_exportPath != null) ...[
                  const SizedBox(height: 10),
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: Boli.leaf.withValues(alpha: .12),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: Boli.leaf.withValues(alpha: .3)),
                    ),
                    child: Row(
                      children: [
                        const Icon(Icons.check_circle_rounded, size: 16, color: Boli.leaf),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            'Exported: $_exportPath\nCompatible with sync_assessments.py',
                            style: Boli.body(12, weight: FontWeight.w600, color: Boli.leaf),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
                if (_exportError != null) ...[
                  const SizedBox(height: 10),
                  Text(_exportError!, style: Boli.body(12, color: Boli.terracotta)),
                ],
                const SizedBox(height: 12),
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton.icon(
                    onPressed: _exporting ? null : _exportData,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Boli.indigo,
                      foregroundColor: Colors.white,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                    icon: _exporting
                        ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                        : const Icon(Icons.file_download_rounded, size: 18),
                    label: Text(
                      _exporting ? 'Exporting...' : 'Sync via Office Kit Free Transfer',
                      style: Boli.body(14, weight: FontWeight.w700, color: Colors.white),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
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

  Widget _telemetryRow(String label, String value, IconData icon, {Color? color}) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 15, color: color ?? Boli.peacock),
        const SizedBox(width: 8),
        Text('$label: ', style: Boli.body(13, color: Boli.inkSoft)),
        Expanded(
          child: Text(
            value,
            style: Boli.body(13, weight: FontWeight.w700, color: color ?? Boli.ink),
          ),
        ),
      ],
    );
  }
}

class _AmbientMiningServiceCard extends StatefulWidget {
  const _AmbientMiningServiceCard();

  @override
  State<_AmbientMiningServiceCard> createState() =>
      _AmbientMiningServiceCardState();
}

class _AmbientMiningServiceCardState extends State<_AmbientMiningServiceCard>
    with SingleTickerProviderStateMixin {
  bool _active = false;
  bool _loading = false;
  final List<AmbientMinedLemmaEvent> _minedList = [];
  StreamSubscription<AmbientMinedLemmaEvent>? _sub;
  late AnimationController _pulseController;
  late Animation<double> _pulseAnim;

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1200),
    )..repeat(reverse: true);
    _pulseAnim = Tween<double>(begin: 0.85, end: 1.15).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );

    _checkActive();
    _sub = BoliBridge.instance.onAmbientLemmaDiscovered.listen((event) {
      if (mounted) {
        setState(() {
          _minedList.insert(0, event);
          if (_minedList.length > 6) _minedList.removeLast();
        });
      }
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    _pulseController.dispose();
    super.dispose();
  }

  Future<void> _checkActive() async {
    final act = await BoliBridge.instance.isAmbientMiningActive();
    if (mounted) setState(() => _active = act);
  }

  Future<void> _toggle() async {
    setState(() => _loading = true);
    try {
      if (_active) {
        await BoliBridge.instance.stopAmbientMining();
        if (mounted) setState(() => _active = false);
      } else {
        await BoliBridge.instance.startAmbientMining();
        if (mounted) setState(() => _active = true);
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: _active ? Boli.paper : Boli.sand.withValues(alpha: .3),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(
          color: _active ? Boli.leaf : Boli.sand,
          width: _active ? 2.2 : 1.5,
        ),
        boxShadow: _active ? Boli.lift(y: 3, blur: 12) : null,
      ),
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              ScaleTransition(
                scale: _active ? _pulseAnim : const AlwaysStoppedAnimation(1.0),
                child: Container(
                  width: 44,
                  height: 44,
                  decoration: BoxDecoration(
                    color: _active
                        ? Boli.leaf.withValues(alpha: .15)
                        : Boli.inkSoft.withValues(alpha: .12),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    _active
                        ? Icons.hearing_rounded
                        : Icons.hearing_disabled_rounded,
                    color: _active ? Boli.leaf : Boli.inkSoft,
                    size: 24,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            'Ambient Vocabulary Miner',
                            style: Boli.head(17, weight: 700),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        const SizedBox(width: 6),
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 6,
                            vertical: 2,
                          ),
                          decoration: BoxDecoration(
                            color: _active
                                ? Boli.leaf.withValues(alpha: .15)
                                : Boli.sand,
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            _active ? 'LIVE MINING' : 'OFFLINE',
                            style: Boli.label(
                              size: 10,
                              color: _active ? Boli.leaf : Boli.inkSoft,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 2),
                    Text(
                      'पार्श्वभूमी शब्द संग्रह · DPDP Ephemeral RAM Buffer',
                      style: Boli.body(12.5, color: Boli.inkSoft),
                    ),
                  ],
                ),
              ),
              GestureDetector(
                onTap: _loading ? null : _toggle,
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 200),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 8,
                  ),
                  decoration: BoxDecoration(
                    color: _active ? Boli.madder : Boli.peacock,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: _loading
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Boli.cream,
                          ),
                        )
                      : Text(
                          _active ? 'STOP' : 'START',
                          style: Boli.body(
                            13,
                            weight: FontWeight.w800,
                            color: Boli.cream,
                          ),
                        ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          HandloomBorder(
            color: _active ? Boli.leaf.withValues(alpha: .4) : Boli.sand,
            height: 6,
            dense: true,
          ),
          const SizedBox(height: 10),
          if (!_active)
            Text(
              'Passively hears regional target vocabulary in busy canteens, buses, and sites. Ephemeral 30s circular buffer in RAM — zero raw audio persisted (DPDP 2023 compliant).',
              style: Boli.body(13, color: Boli.inkSoft),
            )
          else ...[
            Row(
              children: [
                const Icon(Icons.lock_rounded, size: 13, color: Boli.leaf),
                const SizedBox(width: 4),
                Text(
                  '100% Privacy Protected · Zero Audio Saved',
                  style: Boli.body(
                    12,
                    color: Boli.leaf,
                    weight: FontWeight.w700,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            if (_minedList.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: Row(
                  children: [
                    const SizedBox(
                      width: 14,
                      height: 14,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Boli.leaf,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Text(
                      'Listening nearby for workplace vocabulary…',
                      style: Boli.body(
                        13,
                        color: Boli.inkSoft,
                        weight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
              )
            else ...[
              Text(
                'Recently Mined Regional Words:',
                style: Boli.body(
                  12.5,
                  color: Boli.inkSoft,
                  weight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 6),
              for (final ev in _minedList.take(3))
                Container(
                  margin: const EdgeInsets.only(bottom: 6),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 8,
                  ),
                  decoration: BoxDecoration(
                    color: Boli.leaf.withValues(alpha: .07),
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(color: Boli.leaf.withValues(alpha: .2)),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Text(
                                  ev.lemma,
                                  style: Boli.head(17, weight: 700),
                                ),
                                const SizedBox(width: 6),
                                Text(
                                  '(${ev.transliteration}) · ${ev.translationL1}',
                                  style: Boli.body(13, color: Boli.inkSoft),
                                ),
                              ],
                            ),
                            if (ev.contextSentence.isNotEmpty) ...[
                              const SizedBox(height: 2),
                              Text(
                                '"${ev.contextSentence}"',
                                style: Boli.body(
                                  12,
                                  color: Boli.inkSoft,
                                ).copyWith(fontStyle: FontStyle.italic),
                              ),
                            ],
                          ],
                        ),
                      ),
                      GestureDetector(
                        onTap: () =>
                            BoliBridge.instance.speakPrompt(text: ev.lemma),
                        child: Container(
                          width: 34,
                          height: 34,
                          decoration: BoxDecoration(
                            color: Boli.peacock.withValues(alpha: .12),
                            borderRadius: BorderRadius.circular(17),
                          ),
                          child: const Icon(
                            Icons.volume_up_rounded,
                            size: 18,
                            color: Boli.peacock,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
            ],
          ],
        ],
      ),
    );
  }
}
