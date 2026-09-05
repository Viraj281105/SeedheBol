// packages/l1_interference/lib/src/confusion_matrix.dart
//
// L1-to-L2 Phonological Confusion Matrix Models.
//
// Represents systematic phonetic substitutions caused by native language (L1)
// phonological interference when speaking a target language (L2).
//
// Example: Bhojpuri/Hindi speakers frequently substitute dental stops (/t̪/, /d̪/)
// for Dravidian retroflex stops (/ʈ/, /ɖ/), and replace the Tamil retroflex
// approximant /ɻ/ (ழ) with an alveolar lateral /l/ (ल).

import 'package:meta/meta.dart';

/// Minimal pair contrast example demonstrating the confusion in context.
@immutable
class MinimalPair {
  final String targetPhrase;
  final String confusedPhrase;

  const MinimalPair({
    required this.targetPhrase,
    required this.confusedPhrase,
  });

  factory MinimalPair.fromJson(Map<String, dynamic> json) {
    return MinimalPair(
      targetPhrase: json['target'] as String,
      confusedPhrase: json['confused'] as String,
    );
  }

  Map<String, dynamic> toJson() => {
        'target': targetPhrase,
        'confused': confusedPhrase,
      };
}

/// A specific L1-interference phonetic substitution pattern.
@immutable
class ConfusionPair {
  /// Expected target phoneme IPA (e.g. 'ʈ').
  final String targetPhonemeIpa;

  /// Target language grapheme (e.g. 'ட').
  final String targetGrapheme;

  /// Substituted phoneme IPA produced by L1 interference (e.g. 't̪').
  final String confusedPhonemeIpa;

  /// Substituted language grapheme (e.g. 'त').
  final String confusedGrapheme;

  /// Descriptive linguistic phenomenon name.
  final String phenomenon;

  /// Empirical prior probability of substitution for this corridor.
  final double priorProbability;

  /// Minimal pairs contrasting this distinction.
  final List<MinimalPair> minimalPairs;

  /// Articulatory physiological guidance in L1 (Hindi).
  final String articulatoryCueHindi;

  /// Articulatory physiological guidance in L1 (Bhojpuri).
  final String articulatoryCueBhojpuri;

  /// Articulatory physiological guidance in English.
  final String articulatoryCueEn;

  const ConfusionPair({
    required this.targetPhonemeIpa,
    required this.targetGrapheme,
    required this.confusedPhonemeIpa,
    required this.confusedGrapheme,
    required this.phenomenon,
    required this.priorProbability,
    required this.minimalPairs,
    required this.articulatoryCueHindi,
    required this.articulatoryCueBhojpuri,
    required this.articulatoryCueEn,
  });

  factory ConfusionPair.fromJson(Map<String, dynamic> json) {
    return ConfusionPair(
      targetPhonemeIpa: json['target_phoneme_ipa'] as String,
      targetGrapheme: json['target_grapheme_ta'] as String? ??
          json['target_grapheme'] as String? ??
          '',
      confusedPhonemeIpa: json['confused_phoneme_ipa'] as String,
      confusedGrapheme: json['confused_grapheme_hi'] as String? ??
          json['confused_grapheme'] as String? ??
          '',
      phenomenon: json['phenomenon'] as String,
      priorProbability:
          (json['prior_confusion_probability'] as num?)?.toDouble() ?? 0.5,
      minimalPairs: (json['minimal_pairs'] as List<dynamic>?)
              ?.map((m) => MinimalPair.fromJson(m as Map<String, dynamic>))
              .toList() ??
          const [],
      articulatoryCueHindi: json['articulatory_cue_hindi'] as String? ?? '',
      articulatoryCueBhojpuri:
          json['articulatory_cue_bhojpuri'] as String? ?? '',
      articulatoryCueEn: json['articulatory_cue_en'] as String? ?? '',
    );
  }

  Map<String, dynamic> toJson() => {
        'target_phoneme_ipa': targetPhonemeIpa,
        'target_grapheme': targetGrapheme,
        'confused_phoneme_ipa': confusedPhonemeIpa,
        'confused_grapheme': confusedGrapheme,
        'phenomenon': phenomenon,
        'prior_confusion_probability': priorProbability,
        'minimal_pairs': minimalPairs.map((m) => m.toJson()).toList(),
        'articulatory_cue_hindi': articulatoryCueHindi,
        'articulatory_cue_bhojpuri': articulatoryCueBhojpuri,
        'articulatory_cue_en': articulatoryCueEn,
      };
}

/// Complete confusion dataset for a language corridor.
@immutable
class CorridorConfusionSet {
  final String corridorId;
  final String sourceLanguage;
  final String targetLanguage;
  final List<ConfusionPair> confusionPairs;
  final Map<String, ConfusionPair> _byTargetPhoneme;

  CorridorConfusionSet({
    required this.corridorId,
    required this.sourceLanguage,
    required this.targetLanguage,
    required this.confusionPairs,
  }) : _byTargetPhoneme = {
          for (final p in confusionPairs) p.targetPhonemeIpa: p
        };

  ConfusionPair? findPair(String targetIpa) => _byTargetPhoneme[targetIpa];

  factory CorridorConfusionSet.fromJson(Map<String, dynamic> json) {
    final pairs = (json['confusion_pairs'] as List<dynamic>?)
            ?.map((p) => ConfusionPair.fromJson(p as Map<String, dynamic>))
            .toList() ??
        const [];
    return CorridorConfusionSet(
      corridorId: json['corridor_id'] as String,
      sourceLanguage: json['source_language'] as String,
      targetLanguage: json['target_language'] as String,
      confusionPairs: pairs,
    );
  }

  Map<String, dynamic> toJson() => {
        'corridor_id': corridorId,
        'source_language': sourceLanguage,
        'target_language': targetLanguage,
        'confusion_pairs': confusionPairs.map((p) => p.toJson()).toList(),
      };
}
