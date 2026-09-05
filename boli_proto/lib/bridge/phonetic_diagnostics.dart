import 'package:boli_g2p_indic/g2p_indic.dart';
import 'package:boli_l1_interference/l1_interference.dart';

/// Structured result of phonetic G2P and L1-interference diagnostic analysis.
class PhoneticDiagnosticResult {
  final double score;
  final String targetIpa;
  final String heardIpa;
  final List<String> weakPhonemes;
  final String? phenomenon;
  final String? articulatoryAdvice;
  final List<MinimalPair> minimalPairs;

  const PhoneticDiagnosticResult({
    required this.score,
    required this.targetIpa,
    required this.heardIpa,
    this.weakPhonemes = const [],
    this.phenomenon,
    this.articulatoryAdvice,
    this.minimalPairs = const [],
  });

  bool get hasL1Interference => articulatoryAdvice != null && articulatoryAdvice!.isNotEmpty;
}

/// Bridge service connecting Flutter UI to on-device G2P & L1-Interference engines.
class PhoneticDiagnosticService {
  static final G2PEngine _g2p = G2PEngine();
  static final CorridorConfusionSet _mrConfusion = CorridorConfusionSet.hindiMarathi();
  static final CorridorConfusionSet _taConfusion = CorridorConfusionSet.bhojpuriTamil();

  /// Analyzes spoken audio transcript against target text using phonemic G2P and
  /// L1 confusion sets to detect physiological speech substitutions.
  static PhoneticDiagnosticResult analyze({
    required String targetText,
    required String heardText,
    String targetLang = 'mr',
    String nativeLang = 'hi',
  }) {
    if (targetText.trim().isEmpty || heardText.trim().isEmpty) {
      return const PhoneticDiagnosticResult(
        score: 0.0,
        targetIpa: '',
        heardIpa: '',
      );
    }

    String targetIpa = '';
    String heardIpa = '';
    List<String> targetPhonemes = [];
    List<String> heardPhonemes = [];

    // Step 1: Run G2P on target and heard text
    try {
      final tResult = _g2p.convert(targetText, languageCode: targetLang);
      targetIpa = tResult.ipaString;
      targetPhonemes = tResult.phonemes.where((p) => p.trim().isNotEmpty).toList();
    } catch (_) {
      targetIpa = targetText;
    }

    try {
      final hResult = _g2p.convert(heardText, languageCode: targetLang);
      heardIpa = hResult.ipaString;
      heardPhonemes = hResult.phonemes.where((p) => p.trim().isNotEmpty).toList();
    } catch (_) {
      heardIpa = heardText;
    }

    // Step 2: Identify phonetic discrepancies
    final List<String> weakPhonemes = [];
    ConfusionPair? detectedPair;

    final confusionSet = targetLang == 'ta' ? _taConfusion : _mrConfusion;

    for (final ph in targetPhonemes) {
      // Check if this target phoneme is missing in heard phonemes
      if (!heardPhonemes.contains(ph)) {
        weakPhonemes.add(ph);
        final pair = confusionSet.findPair(ph);
        if (pair != null && heardPhonemes.contains(pair.confusedPhonemeIpa)) {
          detectedPair = pair;
        }
      }
    }

    // Direct grapheme check fallback for prominent retroflex ळ -> ल confusion
    if (detectedPair == null && targetText.contains('ळ') && heardText.contains('ल') && !heardText.contains('ळ')) {
      detectedPair = confusionSet.findPair('ɭ');
      if (!weakPhonemes.contains('ɭ')) weakPhonemes.add('ɭ');
    }

    // Step 3: Compute phoneme similarity score
    double score = 0.0;
    if (targetPhonemes.isNotEmpty) {
      int matched = 0;
      for (int i = 0; i < targetPhonemes.length; i++) {
        if (i < heardPhonemes.length && targetPhonemes[i] == heardPhonemes[i]) {
          matched++;
        } else if (heardPhonemes.contains(targetPhonemes[i])) {
          matched++;
        }
      }
      score = matched / targetPhonemes.length;
    }

    // Select articulatory advice according to native preference
    String? advice;
    if (detectedPair != null) {
      advice = nativeLang == 'bhojpuri'
          ? detectedPair.articulatoryCueBhojpuri
          : detectedPair.articulatoryCueHindi;
      if (advice.isEmpty) advice = detectedPair.articulatoryCueEn;
    }

    return PhoneticDiagnosticResult(
      score: score.clamp(0.0, 1.0),
      targetIpa: targetIpa,
      heardIpa: heardIpa,
      weakPhonemes: weakPhonemes,
      phenomenon: detectedPair?.phenomenon,
      articulatoryAdvice: advice,
      minimalPairs: detectedPair?.minimalPairs ?? const [],
    );
  }
}
