import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'theme.dart';
import 'widgets.dart';

/// Camera → OCR → Gemma → MicroLesson → TTS
///
/// This screen implements the primary demo flow described in the architecture:
///   1. CameraX live preview via [camera] Flutter plugin
///   2. Capture still → JPEG bytes → 'extractTextFromImage' platform channel
///   3. OCR text → 'generateLessonFromOcr' → [_MicroLessonCard]
///   4. Tap "Listen" on lesson or vocab word → 'speak' (FastPitch TTS)
///
/// The [_AiBadge] in the lesson card shows "GEMMA" or "FALLBACK" so the demo
/// audience can see which backend produced the response.
///
/// If the device has no camera or permission is denied, a graceful error state
/// is shown rather than crashing.
class CameraLessonScreen extends StatefulWidget {
  const CameraLessonScreen({super.key});

  @override
  State<CameraLessonScreen> createState() => _CameraLessonScreenState();
}

class _CameraLessonScreenState extends State<CameraLessonScreen>
    with WidgetsBindingObserver {
  static const _engineChannel = MethodChannel('boli/engine_methods');
  static const _asrChannel = MethodChannel('boli/asr');

  CameraController? _camera;
  List<CameraDescription> _cameras = [];
  String _cameraError = '';
  bool _cameraReady = false;

  // OCR state
  bool _scanning = false;
  String _ocrText = '';

  // Lesson state
  bool _generatingLesson = false;
  _LessonData? _lesson;
  String _lessonError = '';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _initCamera();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _camera?.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final cam = _camera;
    if (cam == null || !cam.value.isInitialized) return;
    if (state == AppLifecycleState.inactive) {
      cam.dispose();
    } else if (state == AppLifecycleState.resumed) {
      _initCamera();
    }
  }

  Future<void> _initCamera() async {
    try {
      _cameras = await availableCameras();
      if (_cameras.isEmpty) {
        setState(() => _cameraError = 'No cameras found on this device.');
        return;
      }
      final controller = CameraController(
        _cameras.first,
        ResolutionPreset.high,
        enableAudio: false,
        imageFormatGroup: ImageFormatGroup.jpeg,
      );
      await controller.initialize();
      if (!mounted) return;
      setState(() {
        _camera = controller;
        _cameraReady = true;
        _cameraError = '';
      });
    } on CameraException catch (e) {
      setState(() {
        _cameraError = _friendlyCameraError(e.code);
        _cameraReady = false;
      });
    }
  }

  Future<void> _captureAndProcess() async {
    final cam = _camera;
    if (cam == null || !cam.value.isInitialized || _scanning) return;

    setState(() {
      _scanning = true;
      _ocrText = '';
      _lesson = null;
      _lessonError = '';
    });
    HapticFeedback.mediumImpact();

    try {
      // 1. Capture JPEG
      final file = await cam.takePicture();
      final bytes = await file.readAsBytes();

      // 2. Run OCR
      final lines = await _engineChannel.invokeListMethod<String>(
        'extractTextFromImage',
        {'image_bytes': Uint8List.fromList(bytes)},
      ) ?? [];
      final rawText = lines.join('\n').trim();

      if (!mounted) return;
      setState(() {
        _ocrText = rawText;
        _scanning = false;
        _generatingLesson = rawText.isNotEmpty;
        _lessonError = rawText.isEmpty ? 'No text detected. Try pointing at a sign or label.' : '';
      });

      if (rawText.isEmpty) return;

      // 3. Generate lesson from OCR text via Gemma / fallback
      final lessonMap = await _engineChannel.invokeMapMethod<String, dynamic>(
        'generateLessonFromOcr',
        {'ocr_text': rawText},
      );

      if (!mounted) return;
      if (lessonMap != null) {
        setState(() {
          _lesson = _LessonData.fromMap(lessonMap);
          _generatingLesson = false;
        });
        // 4. Auto-play the practice prompt via FastPitch TTS
        final prompt = lessonMap['practice_prompt'] as String? ?? '';
        if (prompt.isNotEmpty) {
          _speak(prompt);
        }
      }
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() {
        _scanning = false;
        _generatingLesson = false;
        _lessonError = e.message ?? 'Something went wrong. Try again.';
      });
    }
  }

  Future<void> _speak(String text) async {
    if (text.isEmpty) return;
    try {
      await _asrChannel.invokeMethod<String>('speak', {'text': text});
    } on PlatformException {
      // Missing phrase in TTS vocab — not worth surfacing as error
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Boli.ink,
      body: SafeArea(
        child: Column(
          children: [
            _buildAppBar(),
            Expanded(
              child: _cameraError.isNotEmpty
                  ? _CameraErrorView(message: _cameraError, onRetry: _initCamera)
                  : _buildBody(),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAppBar() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 8, 16, 4),
      child: Row(
        children: [
          IconButton(
            icon: const Icon(Icons.arrow_back_ios_new_rounded, color: Boli.cream),
            onPressed: () => Navigator.of(context).pop(),
          ),
          const SizedBox(width: 4),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Point & Learn', style: Boli.head(20, weight: 700, color: Boli.cream)),
              Text(
                'Point at any text to get a lesson',
                style: Boli.body(13, color: Boli.cream.withValues(alpha: .6)),
              ),
            ],
          ),
          const Spacer(),
          if (!_scanning && !_generatingLesson && _lesson != null)
            GestureDetector(
              onTap: () => setState(() => _lesson = null),
              child: const Icon(Icons.refresh_rounded, color: Boli.cream, size: 26),
            ),
        ],
      ),
    );
  }

  Widget _buildBody() {
    return Stack(
      children: [
        // Camera preview fills the screen
        if (_cameraReady && _camera != null)
          Positioned.fill(child: CameraPreview(_camera!)),

        // Loading overlay
        if (_scanning || _generatingLesson)
          _ScanningOverlay(
            message: _scanning ? 'Reading text…' : 'Generating lesson…',
          ),

        // OCR hint text strip (shown after scan, before lesson ready)
        if (_ocrText.isNotEmpty && _lesson == null && !_generatingLesson)
          Positioned(
            top: 12,
            left: 16,
            right: 16,
            child: _OcrTextBadge(text: _ocrText),
          ),

        // Error message
        if (_lessonError.isNotEmpty)
          Positioned(
            top: 12,
            left: 16,
            right: 16,
            child: _ErrorBadge(message: _lessonError),
          ),

        // Lesson card — shown at bottom when ready
        if (_lesson != null)
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: _MicroLessonCard(
              lesson: _lesson!,
              onSpeak: _speak,
            ),
          ),

        // Capture button — bottom-centre when no lesson showing
        if (_lesson == null && !_scanning && !_generatingLesson)
          Positioned(
            bottom: 32,
            left: 0,
            right: 0,
            child: Center(
              child: _CaptureButton(
                onTap: _cameraReady ? _captureAndProcess : null,
              ),
            ),
          ),
      ],
    );
  }

  String _friendlyCameraError(String code) {
    switch (code) {
      case 'CameraAccessDenied':
        return 'Camera access denied. Allow it in Settings → Apps → SeedheBol.';
      case 'CameraAccessDeniedWithoutPrompt':
        return 'Camera access blocked. Enable it in device Settings.';
      default:
        return 'Camera unavailable ($code).';
    }
  }
}

// ---------------------------------------------------------------------------
// Data

class _VocabItem {
  final String l2Word, l1Meaning, romanization;
  const _VocabItem({
    required this.l2Word,
    required this.l1Meaning,
    required this.romanization,
  });
}

class _LessonData {
  final String topic, explanation, practicePrompt, source;
  final List<_VocabItem> vocabulary;
  final int latencyMs;

  const _LessonData({
    required this.topic,
    required this.explanation,
    required this.practicePrompt,
    required this.vocabulary,
    required this.source,
    required this.latencyMs,
  });

  factory _LessonData.fromMap(Map<String, dynamic> map) {
    final rawVocab = map['vocabulary'] as List? ?? [];
    final vocab = rawVocab
        .whereType<Map>()
        .map(
          (v) => _VocabItem(
            l2Word: v['l2_word'] as String? ?? '',
            l1Meaning: v['l1_meaning'] as String? ?? '',
            romanization: v['romanization'] as String? ?? '',
          ),
        )
        .where((v) => v.l2Word.isNotEmpty)
        .toList();

    return _LessonData(
      topic: map['topic'] as String? ?? '',
      explanation: map['explanation'] as String? ?? '',
      practicePrompt: map['practice_prompt'] as String? ?? '',
      vocabulary: vocab,
      source: map['source'] as String? ?? 'unknown',
      latencyMs: (map['latency_ms'] as num?)?.toInt() ?? 0,
    );
  }
}

// ---------------------------------------------------------------------------
// Widgets

class _CaptureButton extends StatelessWidget {
  final VoidCallback? onTap;
  const _CaptureButton({this.onTap});

  @override
  Widget build(BuildContext context) {
    final enabled = onTap != null;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 76,
        height: 76,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: enabled ? Boli.marigold : Boli.sand,
          boxShadow: enabled
              ? [BoxShadow(color: Boli.marigold.withValues(alpha: .4), blurRadius: 16, spreadRadius: 2)]
              : [],
        ),
        child: Icon(
          Icons.camera_alt_rounded,
          color: enabled ? Boli.ink : Boli.inkSoft,
          size: 34,
        ),
      ),
    );
  }
}

class _ScanningOverlay extends StatelessWidget {
  final String message;
  const _ScanningOverlay({required this.message});

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Boli.ink.withValues(alpha: .65),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const CircularProgressIndicator(color: Boli.marigold, strokeWidth: 3),
            const SizedBox(height: 16),
            Text(message, style: Boli.body(17, color: Boli.cream, weight: FontWeight.w700)),
          ],
        ),
      ),
    );
  }
}

class _OcrTextBadge extends StatelessWidget {
  final String text;
  const _OcrTextBadge({required this.text});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: Boli.ink.withValues(alpha: .85),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Boli.sand.withValues(alpha: .4)),
      ),
      child: Row(
        children: [
          const Icon(Icons.text_snippet_rounded, color: Boli.marigold, size: 16),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              text.length > 80 ? '${text.substring(0, 80)}…' : text,
              style: Boli.body(13, color: Boli.cream.withValues(alpha: .9)),
            ),
          ),
        ],
      ),
    );
  }
}

class _ErrorBadge extends StatelessWidget {
  final String message;
  const _ErrorBadge({required this.message});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Boli.madder.withValues(alpha: .9),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          const Icon(Icons.warning_amber_rounded, color: Boli.cream, size: 18),
          const SizedBox(width: 10),
          Expanded(child: Text(message, style: Boli.body(14, color: Boli.cream))),
        ],
      ),
    );
  }
}

class _CameraErrorView extends StatelessWidget {
  final String message;
  final VoidCallback onRetry;
  const _CameraErrorView({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Boli.ink,
      padding: const EdgeInsets.all(32),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.no_photography_rounded, color: Boli.sand, size: 56),
          const SizedBox(height: 20),
          Text(message, style: Boli.body(16, color: Boli.cream, height: 1.5), textAlign: TextAlign.center),
          const SizedBox(height: 28),
          BigButton(label: 'Try again', onTap: onRetry),
        ],
      ),
    );
  }
}

class _AiBadge extends StatelessWidget {
  final String source;
  final int latencyMs;
  const _AiBadge({required this.source, required this.latencyMs});

  @override
  Widget build(BuildContext context) {
    final isGemma = source == 'gemma';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: isGemma
            ? Boli.peacock.withValues(alpha: .15)
            : Boli.sand.withValues(alpha: .4),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(
          color: isGemma ? Boli.peacock.withValues(alpha: .4) : Boli.sand,
        ),
      ),
      child: Text(
        isGemma ? 'GEMMA · ${latencyMs}ms' : 'OFFLINE FALLBACK',
        style: Boli.label(
          size: 10,
          color: isGemma ? Boli.peacock : Boli.inkSoft,
        ),
      ),
    );
  }
}

class _MicroLessonCard extends StatelessWidget {
  final _LessonData lesson;
  final Future<void> Function(String) onSpeak;
  const _MicroLessonCard({required this.lesson, required this.onSpeak});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Boli.paper,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(22)),
        boxShadow: [
          BoxShadow(color: Colors.black.withValues(alpha: .35), blurRadius: 24, offset: const Offset(0, -4)),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Handle bar
          Container(
            margin: const EdgeInsets.only(top: 10),
            width: 40,
            height: 5,
            decoration: BoxDecoration(
              color: Boli.sand,
              borderRadius: BorderRadius.circular(4),
            ),
          ),

          Padding(
            padding: const EdgeInsets.fromLTRB(20, 14, 20, 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Header: topic + AI badge
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        lesson.topic,
                        style: Boli.head(21, weight: 700),
                      ),
                    ),
                    const SizedBox(width: 8),
                    _AiBadge(source: lesson.source, latencyMs: lesson.latencyMs),
                  ],
                ),

                if (lesson.explanation.isNotEmpty) ...[
                  const SizedBox(height: 10),
                  Text(lesson.explanation, style: Boli.body(15, height: 1.5)),
                ],

                // Vocabulary chips
                if (lesson.vocabulary.isNotEmpty) ...[
                  const SizedBox(height: 14),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: lesson.vocabulary
                        .map((v) => _VocabChip(item: v, onSpeak: onSpeak))
                        .toList(),
                  ),
                ],

                // Practice prompt with TTS
                if (lesson.practicePrompt.isNotEmpty) ...[
                  const SizedBox(height: 16),
                  Container(
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: Boli.peacock.withValues(alpha: .08),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(
                        color: Boli.peacock.withValues(alpha: .25),
                      ),
                    ),
                    child: Row(
                      children: [
                        const Icon(Icons.record_voice_over_rounded,
                            color: Boli.peacock, size: 20),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            lesson.practicePrompt,
                            style: Boli.body(15, color: Boli.peacock, weight: FontWeight.w600),
                          ),
                        ),
                        const SizedBox(width: 8),
                        GestureDetector(
                          onTap: () => onSpeak(lesson.practicePrompt),
                          child: const Icon(Icons.volume_up_rounded,
                              color: Boli.peacock, size: 22),
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _VocabChip extends StatelessWidget {
  final _VocabItem item;
  final Future<void> Function(String) onSpeak;
  const _VocabChip({required this.item, required this.onSpeak});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () => onSpeak(item.l2Word),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: Boli.cream,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: Boli.sand, width: 1.5),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(item.l2Word,
                    style: Boli.body(15, weight: FontWeight.w700)),
                const SizedBox(width: 4),
                const Icon(Icons.volume_up_rounded, size: 14, color: Boli.inkSoft),
              ],
            ),
            if (item.romanization.isNotEmpty) ...[
              const SizedBox(height: 2),
              Text(item.romanization,
                  style: Boli.body(12, color: Boli.inkSoft)
                      .copyWith(fontStyle: FontStyle.italic)),
            ],
            const SizedBox(height: 2),
            Text(item.l1Meaning,
                style: Boli.body(13, color: Boli.inkSoft)),
          ],
        ),
      ),
    );
  }
}
