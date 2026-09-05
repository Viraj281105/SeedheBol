import 'dart:math' as math;
import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'theme.dart';
import 'widgets.dart';

/// Camera → Understand → Learn
///
/// Hero feature flow for SeedheBol:
///   1. Camera Viewfinder: High-contrast framing guide designed for Indian signboards.
///   2. ML Kit OCR: Detects Devanagari, Latin, and regional text on-device.
///   3. Gemma 3n E2B:
///      - Contextual Translation: Translated to worker's L1 (e.g. Hindi/Bhojpuri).
///      - Workplace Explanation: Why this sign matters on the job.
///      - "LEARN THIS" Vocabulary: 3–5 key words with L2 text, Roman pronunciation, and L1 meaning.
///   4. 🔊 Listen (TTS): Immediate on-device speech playback for every word and sentence.
///   5. 🎤 "Now say it" Voice Practice: Real-time on-device ASR microphone capture with
///      phonetic similarity scoring and encouraging readiness feedback.
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

  static const double _boxWidthFraction = 0.86;
  static const double _boxAspectRatio = 0.52; // height = width * 0.52

  Map<String, double> _calculateCropRect() {
    final cam = _camera;
    if (cam == null || !cam.value.isInitialized) {
      return {'left': 0.0, 'top': 0.0, 'width': 1.0, 'height': 1.0};
    }
    // Sensor aspect ratio in portrait: width / height (e.g. 9/16 = 0.5625)
    final camPortraitRatio = 1 / cam.value.aspectRatio;
    final cropWidth = _boxWidthFraction;
    final cropHeight = (_boxWidthFraction * _boxAspectRatio * camPortraitRatio).clamp(0.05, 0.95);
    final cropLeft = (1.0 - cropWidth) / 2.0;
    final cropTop = (1.0 - cropHeight) / 2.0;
    return {
      'left': cropLeft,
      'top': cropTop,
      'width': cropWidth,
      'height': cropHeight,
    };
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
      // 1. Capture picture from CameraX
      final file = await cam.takePicture();
      final bytes = await file.readAsBytes();

      // 2. Run on-device ML Kit OCR strictly cropped to the viewfinder bounding box
      final cropRect = _calculateCropRect();
      final lines = await _engineChannel.invokeListMethod<String>(
        'extractTextFromImage',
        {
          'image_bytes': Uint8List.fromList(bytes),
          'crop_rect': cropRect,
        },
      ) ?? [];
      final rawText = lines.join('\n').trim();

      if (!mounted) return;
      setState(() {
        _ocrText = rawText;
        _scanning = false;
        _generatingLesson = rawText.isNotEmpty;
        _lessonError = rawText.isEmpty
            ? 'पाटीवर मजकूर आढळला नाही. पाटी व्यवस्थित फ्रेममध्ये धरा.\n(No text detected on signboard. Hold steady inside frame.)'
            : '';
      });

      if (rawText.isEmpty) return;

      // 3. Generate structured lesson via Gemma 3n E2B (or deterministic fallback)
      final lessonMap = await _engineChannel.invokeMapMethod<String, dynamic>(
        'generateLessonFromOcr',
        {'ocr_text': rawText},
      );

      if (!mounted) return;
      if (lessonMap != null) {
        final lesson = _LessonData.fromMap(lessonMap);
        setState(() {
          _lesson = lesson;
          _generatingLesson = false;
        });

        // 4. Auto-play the practice prompt or topic via FastPitch TTS
        final speechText = lesson.practicePrompt.isNotEmpty
            ? lesson.practicePrompt
            : (lesson.vocabulary.isNotEmpty ? lesson.vocabulary.first.l2Word : lesson.topic);
        if (speechText.isNotEmpty) {
          _speak(speechText);
        }
      }
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() {
        _scanning = false;
        _generatingLesson = false;
        _lessonError = e.message ?? 'कॅमेरा प्रक्रिया अयशस्वी झाली. पुन्हा प्रयत्न करा.';
      });
    }
  }

  Future<void> _speak(String text) async {
    if (text.isEmpty) return;
    // Strip fallback error markers like "[Offline translation unavailable ...]"
    // so TTS never reads internal error messages aloud.
    final cleaned = text
        .split('\n')
        .where((line) => line.isNotEmpty && !line.startsWith('['))
        .join(' ')
        .trim();
    if (cleaned.isEmpty) return;
    try {
      await _asrChannel.invokeMethod<String>('speak', {'text': cleaned});
    } on PlatformException {
      // Offline fallback phrase missing in synth vocab is non-fatal
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
    return Container(
      color: Boli.ink,
      padding: const EdgeInsets.fromLTRB(8, 8, 16, 6),
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
              Text('Camera · Understand · Learn', style: Boli.head(19, weight: 700, color: Boli.cream)),
              Text(
                'पाटी वाचा आणि बोलायला शिका · Point & Speak',
                style: Boli.body(12.5, color: Boli.cream.withValues(alpha: .7)),
              ),
            ],
          ),
          const Spacer(),
          if (!_scanning && !_generatingLesson && _lesson != null)
            IconButton(
              icon: const Icon(Icons.refresh_rounded, color: Boli.marigold, size: 26),
              tooltip: 'Scan another sign',
              onPressed: () {
                setState(() {
                  _lesson = null;
                  _ocrText = '';
                  _lessonError = '';
                });
              },
            ),
        ],
      ),
    );
  }

  Widget _buildBody() {
    final cam = _camera;
    final hasCamera = _cameraReady && cam != null && cam.value.isInitialized;

    return LayoutBuilder(
      builder: (context, constraints) {
        // Calculate preview layout matching AspectRatio
        double previewW = constraints.maxWidth;
        double previewH = constraints.maxHeight;
        double previewX = 0;
        double previewY = 0;

        if (hasCamera) {
          final camPortraitRatio = 1 / cam.value.aspectRatio;
          final screenRatio = constraints.maxWidth / constraints.maxHeight;
          if (screenRatio < camPortraitRatio) {
            previewW = constraints.maxWidth;
            previewH = previewW / camPortraitRatio;
            previewX = 0;
            previewY = (constraints.maxHeight - previewH) / 2;
          } else {
            previewH = constraints.maxHeight;
            previewW = previewH * camPortraitRatio;
            previewX = (constraints.maxWidth - previewW) / 2;
            previewY = 0;
          }
        }

        final boxW = previewW * _boxWidthFraction;
        final boxH = boxW * _boxAspectRatio;
        final boxLeft = previewX + (previewW - boxW) / 2;
        final boxTop = previewY + (previewH - boxH) / 2;
        final cutoutRect = Rect.fromLTWH(boxLeft, boxTop, boxW, boxH);

        return Stack(
          children: [
            // Camera preview
            if (hasCamera)
              Positioned.fill(child: CameraPreview(cam)),

            // Viewfinder cutout overlay with dimmed surroundings
            if (_lesson == null && !_scanning && !_generatingLesson)
              Positioned.fill(
                child: _ViewfinderOverlay(cutoutRect: cutoutRect),
              ),

            // Scanning / Generating overlay
            if (_scanning || _generatingLesson)
              _ScanningOverlay(
                message: _scanning
                    ? 'पाटी वाचत आहे… (Reading text…)'
                    : 'Gemma अर्थ समजावून सांगत आहे…\n(Understanding sign & creating lesson…)',
              ),

            // Error message
            if (_lessonError.isNotEmpty && _lesson == null)
              Positioned(
                top: 16,
                left: 16,
                right: 16,
                child: _ErrorBadge(message: _lessonError),
              ),

            // Capture button — bottom-centre when no lesson showing
            if (_lesson == null && !_scanning && !_generatingLesson)
              Positioned(
                bottom: 28,
                left: 0,
                right: 0,
                child: Center(
                  child: _CaptureButton(
                    onTap: _cameraReady ? _captureAndProcess : null,
                  ),
                ),
              ),

            // Hero Lesson Sheet — when lesson is ready
            if (_lesson != null)
              Positioned.fill(
                child: _HeroLessonSheet(
                  lesson: _lesson!,
                  ocrText: _ocrText,
                  onSpeak: _speak,
                  onRescan: () {
                    setState(() {
                      _lesson = null;
                      _ocrText = '';
                    });
                  },
                ),
              ),
          ],
        );
      },
    );
  }

  String _friendlyCameraError(String code) {
    switch (code) {
      case 'CameraAccessDenied':
        return 'कॅमेरा परवानगी नाकारली. कृपया फोन सेटिंग्जमध्ये परवानगी द्या.\n(Camera access denied in Settings).';
      case 'CameraAccessDeniedWithoutPrompt':
        return 'कॅमेरा परवानगी बंद आहे. कृपया सेटिंग्जमधून चालू करा.';
      default:
        return 'कॅमेरा सुरू करता आला नाही ($code).';
    }
  }
}

// ---------------------------------------------------------------------------
// Data Models
// ---------------------------------------------------------------------------

class _VocabItem {
  final String l2Word;
  final String l1Meaning;
  final String romanization;
  const _VocabItem({
    required this.l2Word,
    required this.l1Meaning,
    required this.romanization,
  });
}

class _LessonData {
  final String topic;
  final String translation;
  final String explanation;
  final String practicePrompt;
  final String source;
  final List<_VocabItem> vocabulary;
  final int latencyMs;

  const _LessonData({
    required this.topic,
    required this.translation,
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
      translation: map['translation'] as String? ?? '',
      explanation: map['explanation'] as String? ?? '',
      practicePrompt: map['practice_prompt'] as String? ?? '',
      vocabulary: vocab,
      source: map['source'] as String? ?? 'unknown',
      latencyMs: (map['latency_ms'] as num?)?.toInt() ?? 0,
    );
  }
}

// ---------------------------------------------------------------------------
// Viewfinder Overlay
// ---------------------------------------------------------------------------

class _ViewfinderOverlay extends StatelessWidget {
  final Rect cutoutRect;
  const _ViewfinderOverlay({required this.cutoutRect});

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        // Darkened mask outside the cutout window
        Positioned.fill(
          child: CustomPaint(
            painter: _ViewfinderCutoutPainter(
              cutoutRect: cutoutRect,
              borderRadius: 16,
              scrimColor: Colors.black.withValues(alpha: .55),
            ),
          ),
        ),

        // Framing reticle box with bright gold border
        Positioned(
          left: cutoutRect.left,
          top: cutoutRect.top,
          width: cutoutRect.width,
          height: cutoutRect.height,
          child: Container(
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(16),
              border: Border.all(
                color: Boli.marigold.withValues(alpha: .85),
                width: 2.5,
              ),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: .35),
                  blurRadius: 16,
                ),
              ],
            ),
            child: Stack(
              children: const [
                Positioned(
                  top: -1,
                  left: -1,
                  child: _CornerBracket(isTop: true, isLeft: true),
                ),
                Positioned(
                  top: -1,
                  right: -1,
                  child: _CornerBracket(isTop: true, isLeft: false),
                ),
                Positioned(
                  bottom: -1,
                  left: -1,
                  child: _CornerBracket(isTop: false, isLeft: true),
                ),
                Positioned(
                  bottom: -1,
                  right: -1,
                  child: _CornerBracket(isTop: false, isLeft: false),
                ),
              ],
            ),
          ),
        ),

        // Instructional banner above frame
        Positioned(
          top: math.max(16.0, cutoutRect.top - 54.0),
          left: 0,
          right: 0,
          child: Center(
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              decoration: BoxDecoration(
                color: Boli.ink.withValues(alpha: .90),
                borderRadius: BorderRadius.circular(20),
                border: Border.all(color: Boli.sand.withValues(alpha: .4)),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: .3),
                    blurRadius: 8,
                  ),
                ],
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.crop_free_rounded, color: Boli.marigold, size: 18),
                  const SizedBox(width: 8),
                  Text(
                    'केवळ चौकटीतील मजकूर वाचला जाईल · Inside box only',
                    style: Boli.body(12.5, color: Boli.cream, weight: FontWeight.w600),
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _ViewfinderCutoutPainter extends CustomPainter {
  final Rect cutoutRect;
  final double borderRadius;
  final Color scrimColor;

  _ViewfinderCutoutPainter({
    required this.cutoutRect,
    required this.borderRadius,
    required this.scrimColor,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final backgroundPath = Path()
      ..addRect(Rect.fromLTWH(0, 0, size.width, size.height));
    final cutoutPath = Path()
      ..addRRect(RRect.fromRectAndRadius(cutoutRect, Radius.circular(borderRadius)));

    final overlayPath = Path.combine(PathOperation.difference, backgroundPath, cutoutPath);
    final paint = Paint()
      ..color = scrimColor
      ..style = PaintingStyle.fill;

    canvas.drawPath(overlayPath, paint);
  }

  @override
  bool shouldRepaint(covariant _ViewfinderCutoutPainter oldDelegate) {
    return oldDelegate.cutoutRect != cutoutRect ||
        oldDelegate.borderRadius != borderRadius ||
        oldDelegate.scrimColor != scrimColor;
  }
}

class _CornerBracket extends StatelessWidget {
  final bool isTop;
  final bool isLeft;
  const _CornerBracket({required this.isTop, required this.isLeft});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 22,
      height: 22,
      decoration: BoxDecoration(
        color: Colors.transparent,
        border: Border(
          top: isTop ? const BorderSide(color: Boli.cream, width: 4.5) : BorderSide.none,
          bottom: !isTop ? const BorderSide(color: Boli.cream, width: 4.5) : BorderSide.none,
          left: isLeft ? const BorderSide(color: Boli.cream, width: 4.5) : BorderSide.none,
          right: !isLeft ? const BorderSide(color: Boli.cream, width: 4.5) : BorderSide.none,
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Hero Lesson Sheet: Understand → Learn This → Now Say It
// ---------------------------------------------------------------------------

class _HeroLessonSheet extends StatefulWidget {
  final _LessonData lesson;
  final String ocrText;
  final Future<void> Function(String) onSpeak;
  final VoidCallback onRescan;

  const _HeroLessonSheet({
    required this.lesson,
    required this.ocrText,
    required this.onSpeak,
    required this.onRescan,
  });

  @override
  State<_HeroLessonSheet> createState() => _HeroLessonSheetState();
}

class _HeroLessonSheetState extends State<_HeroLessonSheet> {
  static const _asrChannel = MethodChannel('boli/asr');
  static const _engineChannel = MethodChannel('boli/engine_methods');

  // Practice state
  String _activeTarget = '';
  String _activeRoman = '';
  String _activeMeaning = '';
  bool _micBusy = false;
  String _heard = '';
  double _score = 0.0;
  String _micError = '';

  @override
  void initState() {
    super.initState();
    // Default practice target: the first vocabulary word or the practice prompt
    if (widget.lesson.vocabulary.isNotEmpty) {
      final first = widget.lesson.vocabulary.first;
      _activeTarget = first.l2Word;
      _activeRoman = first.romanization;
      _activeMeaning = first.l1Meaning;
    } else if (widget.lesson.practicePrompt.isNotEmpty) {
      _activeTarget = widget.lesson.practicePrompt;
      _activeRoman = '';
      _activeMeaning = widget.lesson.translation;
    } else {
      _activeTarget = widget.lesson.topic;
    }
  }

  void _selectPracticeTarget(String target, String roman, String meaning) {
    setState(() {
      _activeTarget = target;
      _activeRoman = roman;
      _activeMeaning = meaning;
      _heard = '';
      _score = 0.0;
      _micError = '';
    });
    widget.onSpeak(target);
  }

  double _calculateSimilarity(String a, String b) {
    String norm(String s) => s.replaceAll(RegExp(r'[\s।,.?!]'), '').toLowerCase();
    final x = norm(a), y = norm(b);
    if (x.isEmpty || y.isEmpty) return 0.0;
    final d = List.generate(
      x.length + 1,
      (_) => List<int>.filled(y.length + 1, 0),
    );
    for (var i = 0; i <= x.length; i++) {
      d[i][0] = i;
    }
    for (var j = 0; j <= y.length; j++) {
      d[0][j] = j;
    }
    for (var i = 1; i <= x.length; i++) {
      for (var j = 1; j <= y.length; j++) {
        final cost = x[i - 1] == y[j - 1] ? 0 : 1;
        d[i][j] = math.min(
          math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
          d[i - 1][j - 1] + cost,
        );
      }
    }
    final maxLen = math.max(x.length, y.length);
    return maxLen == 0 ? 0.0 : (1.0 - d[x.length][y.length] / maxLen).clamp(0.0, 1.0);
  }

  Future<void> _startVoicePractice() async {
    if (_micBusy || _activeTarget.isEmpty) return;

    setState(() {
      _micBusy = true;
      _heard = '';
      _micError = '';
    });
    HapticFeedback.selectionClick();

    try {
      final text = await _asrChannel.invokeMethod<String>('transcribeMic', {
        'seconds': 4.0,
      }) ?? '';

      if (!mounted) return;
      final sim = _calculateSimilarity(text, _activeTarget);

      if (sim >= 0.70) {
        HapticFeedback.heavyImpact();
      } else if (sim >= 0.40) {
        HapticFeedback.mediumImpact();
      } else {
        HapticFeedback.lightImpact();
      }

      setState(() {
        _heard = text.isEmpty ? '—' : text;
        _score = sim;
        _micBusy = false;
      });

      // Track attempt and pronunciation in offline learner memory
      if (_activeTarget.isNotEmpty) {
        _engineChannel.invokeMethod('recordWordAttempt', {
          'word': _activeTarget,
          'is_correct': sim >= 0.65,
        });
        _engineChannel.invokeMethod('recordPronunciationWeakness', {
          'word': _activeTarget,
          'score': sim,
        });
      }
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() {
        _micBusy = false;
        _micError = e.message ?? 'मायक्रोफोन उपलब्ध नाही (Microphone error)';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final effectiveTranslation = widget.lesson.translation.isNotEmpty
        ? widget.lesson.translation
        : widget.lesson.explanation;

    return DraggableScrollableSheet(
      initialChildSize: 0.82,
      minChildSize: 0.40,
      maxChildSize: 0.96,
      builder: (context, scrollController) {
        return Container(
          decoration: BoxDecoration(
            color: Boli.paper,
            borderRadius: const BorderRadius.vertical(top: Radius.circular(26)),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: .4),
                blurRadius: 28,
                offset: const Offset(0, -6),
              ),
            ],
          ),
          child: ListView(
            controller: scrollController,
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 36),
            children: [
              // Sheet handle bar
              Center(
                child: Container(
                  width: 44,
                  height: 5,
                  decoration: BoxDecoration(
                    color: Boli.sand,
                    borderRadius: BorderRadius.circular(4),
                  ),
                ),
              ),
              const SizedBox(height: 14),

              // 1. Signboard Header + AI Badge
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          widget.lesson.topic,
                          style: Boli.head(23, weight: 700),
                        ),
                        if (widget.ocrText.isNotEmpty) ...[
                          const SizedBox(height: 6),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                            decoration: BoxDecoration(
                              color: Boli.cream,
                              borderRadius: BorderRadius.circular(8),
                              border: Border.all(color: Boli.sand),
                            ),
                            child: Row(
                              children: [
                                const Icon(Icons.document_scanner_rounded, size: 14, color: Boli.inkSoft),
                                const SizedBox(width: 6),
                                Expanded(
                                  child: Text(
                                    widget.ocrText.replaceAll('\n', ' · '),
                                    style: Boli.body(12, color: Boli.inkSoft, weight: FontWeight.w600),
                                    maxLines: 2,
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  _AiBadge(source: widget.lesson.source, latencyMs: widget.lesson.latencyMs),
                ],
              ),
              const SizedBox(height: 18),

              // 2. UNDERSTAND: Contextual Translation & Meaning
              Container(
                padding: const EdgeInsets.all(16),
                decoration: Boli.card(fill: Boli.cream),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        const Icon(Icons.g_translate_rounded, size: 17, color: Boli.peacock),
                        const SizedBox(width: 8),
                        Text('UNDERSTAND · अर्थ', style: Boli.label(color: Boli.peacock, size: 11)),
                        const Spacer(),
                        InkWell(
                          onTap: () => widget.onSpeak(effectiveTranslation),
                          borderRadius: BorderRadius.circular(20),
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                            decoration: BoxDecoration(
                              color: Boli.peacock.withValues(alpha: .12),
                              borderRadius: BorderRadius.circular(16),
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                const Icon(Icons.volume_up_rounded, size: 15, color: Boli.peacock),
                                const SizedBox(width: 4),
                                Text('Listen', style: Boli.label(color: Boli.peacock, size: 10)),
                              ],
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    Text(
                      effectiveTranslation,
                      style: Boli.head(20, weight: 700, color: Boli.ink),
                    ),
                    if (widget.lesson.explanation.isNotEmpty &&
                        widget.lesson.explanation != effectiveTranslation) ...[
                      const SizedBox(height: 8),
                      Text(
                        widget.lesson.explanation,
                        style: Boli.body(14.5, color: Boli.inkSoft, height: 1.45),
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(height: 22),

              // Handloom Border divider
              const HandloomBorder(color: Boli.terracotta, height: 9),
              const SizedBox(height: 18),

              // 3. "LEARN THIS" Vocabulary Section
              Row(
                children: [
                  const Icon(Icons.school_rounded, color: Boli.terracotta, size: 18),
                  const SizedBox(width: 8),
                  Text('LEARN THIS', style: Boli.label(color: Boli.terracotta, size: 12)),
                  const SizedBox(width: 8),
                  Text('— महत्त्वाचे शब्द (3–5 Useful Words)', style: Boli.body(13, color: Boli.inkSoft, weight: FontWeight.w600)),
                ],
              ),
              const SizedBox(height: 12),

              // Vocabulary word tiles
              if (widget.lesson.vocabulary.isNotEmpty)
                ...widget.lesson.vocabulary.map((vocab) {
                  final isSelected = _activeTarget == vocab.l2Word;
                  return _WordTile(
                    item: vocab,
                    isSelected: isSelected,
                    onListen: () => widget.onSpeak(vocab.l2Word),
                    onSelectPractice: () => _selectPracticeTarget(
                      vocab.l2Word,
                      vocab.romanization,
                      vocab.l1Meaning,
                    ),
                  );
                })
              else
                Container(
                  padding: const EdgeInsets.all(14),
                  decoration: Boli.card(fill: Boli.cream),
                  child: Text(
                    'या पाटीतील शब्द समजून घ्या आणि सरावासाठी माईक वापरा.',
                    style: Boli.body(14, color: Boli.inkSoft),
                  ),
                ),

              const SizedBox(height: 22),

              // Handloom Border divider
              const HandloomBorder(color: Boli.peacock, height: 9),
              const SizedBox(height: 18),

              // 4. "NOW SAY IT" Voice Practice (Interactive Pronunciation Trainer)
              _VoicePracticeCard(
                targetWord: _activeTarget,
                romanization: _activeRoman,
                meaning: _activeMeaning,
                busy: _micBusy,
                heard: _heard,
                score: _score,
                error: _micError,
                onListen: () => widget.onSpeak(_activeTarget),
                onMicTap: _startVoicePractice,
              ),

              const SizedBox(height: 20),

              // Rescan Button
              OutlinedButton.icon(
                onPressed: widget.onRescan,
                icon: const Icon(Icons.camera_alt_outlined, color: Boli.inkSoft),
                label: Text('दुसरी पाटी स्कॅन करा (Scan another sign)', style: Boli.body(15, color: Boli.inkSoft, weight: FontWeight.w600)),
                style: OutlinedButton.styleFrom(
                  minimumSize: const Size.fromHeight(56),
                  side: const BorderSide(color: Boli.sand, width: 1.5),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

// ---------------------------------------------------------------------------
// Word Tile with 🔊 Listen and 🎤 Say It buttons
// ---------------------------------------------------------------------------

class _WordTile extends StatelessWidget {
  final _VocabItem item;
  final bool isSelected;
  final VoidCallback onListen;
  final VoidCallback onSelectPractice;

  const _WordTile({
    required this.item,
    required this.isSelected,
    required this.onListen,
    required this.onSelectPractice,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: isSelected ? Boli.marigold.withValues(alpha: .12) : Boli.paper,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: isSelected ? Boli.marigold : Boli.sand,
          width: isSelected ? 2.2 : 1.5,
        ),
      ),
      child: Row(
        children: [
          // Word & Meaning
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(
                      item.l2Word,
                      style: Boli.head(21, weight: 700, color: Boli.ink),
                    ),
                    if (item.romanization.isNotEmpty) ...[
                      const SizedBox(width: 8),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                        decoration: BoxDecoration(
                          color: Boli.peacock.withValues(alpha: .1),
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: Text(
                          item.romanization,
                          style: Boli.body(12, color: Boli.peacock, weight: FontWeight.w700),
                        ),
                      ),
                    ],
                  ],
                ),
                const SizedBox(height: 3),
                Text(
                  'अर्थ: ${item.l1Meaning}',
                  style: Boli.body(14, color: Boli.inkSoft, weight: FontWeight.w600),
                ),
              ],
            ),
          ),

          // 🔊 Listen Button
          IconButton(
            icon: const Icon(Icons.volume_up_rounded, color: Boli.peacock, size: 24),
            tooltip: 'Listen to word',
            onPressed: onListen,
          ),

          // 🎤 "Say it" Button
          InkWell(
            onTap: onSelectPractice,
            borderRadius: BorderRadius.circular(12),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: isSelected ? Boli.marigold : Boli.cream,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Boli.marigold),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    Icons.mic_rounded,
                    size: 16,
                    color: isSelected ? Boli.ink : Boli.marigold,
                  ),
                  const SizedBox(width: 4),
                  Text(
                    'Say it',
                    style: Boli.label(
                      color: isSelected ? Boli.ink : Boli.marigold,
                      size: 11,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// "NOW SAY IT" Voice Practice Card with Real-time Scoring
// ---------------------------------------------------------------------------

class _VoicePracticeCard extends StatelessWidget {
  final String targetWord;
  final String romanization;
  final String meaning;
  final bool busy;
  final String heard;
  final double score;
  final String error;
  final VoidCallback onListen;
  final VoidCallback onMicTap;

  const _VoicePracticeCard({
    required this.targetWord,
    required this.romanization,
    required this.meaning,
    required this.busy,
    required this.heard,
    required this.score,
    required this.error,
    required this.onListen,
    required this.onMicTap,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: Boli.card(fill: Boli.cream),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Section Title
          Row(
            children: [
              const Icon(Icons.mic_rounded, color: Boli.indigo, size: 18),
              const SizedBox(width: 8),
              Text('NOW SAY IT · आता बोलून बघा', style: Boli.label(color: Boli.indigo, size: 12)),
            ],
          ),
          const SizedBox(height: 12),

          // Target word prompt box
          Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: Boli.paper,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: Boli.sand),
            ),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        targetWord,
                        style: Boli.head(22, weight: 700, color: Boli.ink),
                      ),
                      if (romanization.isNotEmpty)
                        Text(
                          romanization,
                          style: Boli.body(13, color: Boli.peacock, weight: FontWeight.w700),
                        ),
                      if (meaning.isNotEmpty)
                        Text(
                          meaning,
                          style: Boli.body(13.5, color: Boli.inkSoft),
                        ),
                    ],
                  ),
                ),
                // 🔊 "Listen first"
                IconButton.filledTonal(
                  icon: const Icon(Icons.volume_up_rounded, color: Boli.peacock, size: 22),
                  tooltip: 'Listen to pronunciation',
                  onPressed: onListen,
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),

          // Mic Button
          Center(
            child: MicButton(
              busy: busy,
              size: 80,
              onTap: busy ? null : onMicTap,
            ),
          ),
          const SizedBox(height: 8),

          // Instructions / Status
          Center(
            child: Text(
              busy
                  ? 'ऐकत आहे… मोठ्याने बोला (Listening…)'
                  : (error.isNotEmpty
                      ? error
                      : 'माईक दाबा आणि हा शब्द बोला (Tap mic & say it)'),
              style: Boli.body(
                14,
                weight: FontWeight.w700,
                color: error.isNotEmpty ? Boli.madder : Boli.inkSoft,
              ),
              textAlign: TextAlign.center,
            ),
          ),

          // Pronunciation Feedback Panel
          if (heard.isNotEmpty) ...[
            const SizedBox(height: 18),
            _PronunciationFeedbackPanel(
              heard: heard,
              target: targetWord,
              score: score,
              onReListen: onListen,
            ),
          ],
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Pronunciation Feedback Panel
// ---------------------------------------------------------------------------

class _PronunciationFeedbackPanel extends StatelessWidget {
  final String heard;
  final String target;
  final double score;
  final VoidCallback onReListen;

  const _PronunciationFeedbackPanel({
    required this.heard,
    required this.target,
    required this.score,
    required this.onReListen,
  });

  @override
  Widget build(BuildContext context) {
    final isGood = score >= 0.70;
    final isMid = score >= 0.40 && score < 0.70;

    final badgeColor = isGood ? Boli.leaf : (isMid ? Boli.marigold : Boli.terracotta);
    final feedbackText = isGood
        ? 'उत्कृष्ट उच्चार! (Great pronunciation! 🎯)'
        : (isMid
            ? 'जवळपास बरोबर! पुन्हा प्रयत्न करा (Almost there! 🔁)'
            : 'पुन्हा ऐका आणि प्रयत्न करा (Listen again, then speak 🔊)');

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Boli.paper,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: badgeColor, width: 2),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                isGood ? Icons.check_circle_rounded : (isMid ? Icons.auto_awesome_rounded : Icons.info_outline_rounded),
                size: 18,
                color: badgeColor,
              ),
              const SizedBox(width: 8),
              Text(
                feedbackText,
                style: Boli.body(13.5, weight: FontWeight.w800, color: badgeColor),
              ),
              const Spacer(),
              Text(
                '${(score * 100).round()}% match',
                style: Boli.head(15, weight: 700, color: badgeColor),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text(
            'फोनने ऐकले (Heard): "$heard"',
            style: Boli.head(19, weight: 600, color: Boli.ink),
          ),
          if (!isGood) ...[
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: Text(
                    'अपेक्षित उच्चार (Expected): "$target"',
                    style: Boli.body(13.5, color: Boli.inkSoft, weight: FontWeight.w600),
                  ),
                ),
                TextButton.icon(
                  onPressed: onReListen,
                  icon: const Icon(Icons.volume_up_rounded, size: 16, color: Boli.peacock),
                  label: Text('Listen', style: Boli.label(color: Boli.peacock, size: 11)),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Common Widgets
// ---------------------------------------------------------------------------

class _CaptureButton extends StatelessWidget {
  final VoidCallback? onTap;
  const _CaptureButton({this.onTap});

  @override
  Widget build(BuildContext context) {
    final enabled = onTap != null;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 78,
        height: 78,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: enabled ? Boli.marigold : Boli.sand,
          border: Border.all(color: Colors.white, width: 4.5),
          boxShadow: enabled
              ? [BoxShadow(color: Boli.marigold.withValues(alpha: .45), blurRadius: 20, spreadRadius: 3)]
              : [],
        ),
        child: Icon(
          Icons.camera_alt_rounded,
          color: enabled ? Boli.ink : Boli.inkSoft,
          size: 36,
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
      color: Boli.ink.withValues(alpha: .75),
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const CircularProgressIndicator(color: Boli.marigold, strokeWidth: 3.5),
            const SizedBox(height: 20),
            Text(
              message,
              style: Boli.body(16.5, color: Boli.cream, weight: FontWeight.w700),
              textAlign: TextAlign.center,
            ),
          ],
        ),
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
        color: Boli.madder.withValues(alpha: .95),
        borderRadius: BorderRadius.circular(14),
        boxShadow: [
          BoxShadow(color: Colors.black.withValues(alpha: .25), blurRadius: 10),
        ],
      ),
      child: Row(
        children: [
          const Icon(Icons.warning_amber_rounded, color: Boli.cream, size: 20),
          const SizedBox(width: 10),
          Expanded(child: Text(message, style: Boli.body(13.5, color: Boli.cream))),
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
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
      decoration: BoxDecoration(
        color: isGemma
            ? Boli.peacock.withValues(alpha: .15)
            : Boli.sand.withValues(alpha: .4),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: isGemma ? Boli.peacock.withValues(alpha: .4) : Boli.sand,
        ),
      ),
      child: Text(
        isGemma ? 'GEMMA · ${latencyMs}ms' : 'OFFLINE',
        style: Boli.label(
          size: 10.5,
          color: isGemma ? Boli.peacock : Boli.inkSoft,
        ),
      ),
    );
  }
}
