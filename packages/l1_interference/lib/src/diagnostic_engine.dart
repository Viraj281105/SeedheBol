// packages/l1_interference/lib/src/diagnostic_engine.dart
//
// Diagnostic Feedback Generator for Articulatory Guidance.
//
// Matches detected phonological errors (low GOP scores) against the
// L1-interference confusion dataset to produce actionable physiological advice
// for the learner in their native dialect.

import 'package:meta/meta.dart';
import 'confusion_matrix.dart';
import 'gop_scorer.dart';

@immutable
class ArticulatoryFeedback {
  final String phonemeIpa;
  final String phenomenon;
  final String primaryAdvice;
  final List<MinimalPair> practicePairs;

  const ArticulatoryFeedback({
    required this.phonemeIpa,
    required this.phenomenon,
    required this.primaryAdvice,
    this.practicePairs = const [],
  });
}

class DiagnosticEngine {
  final CorridorConfusionSet confusionSet;

  DiagnosticEngine({required this.confusionSet});

  /// Generates actionable articulatory feedback for all weak phonemes identified
  /// in a GOP assessment.
  List<ArticulatoryFeedback> generateFeedback(
    GOPAssessment assessment, {
    String languagePreference = 'hi',
  }) {
    final List<ArticulatoryFeedback> feedbackList = [];

    for (final weakPh in assessment.weakPhonemes) {
      final pair = confusionSet.findPair(weakPh);
      if (pair != null) {
        final advice = languagePreference == 'bhojpuri'
            ? pair.articulatoryCueBhojpuri
            : pair.articulatoryCueHindi;

        feedbackList.add(ArticulatoryFeedback(
          phonemeIpa: weakPh,
          phenomenon: pair.phenomenon,
          primaryAdvice: advice.isNotEmpty ? advice : pair.articulatoryCueEn,
          practicePairs: pair.minimalPairs,
        ));
      }
    }

    return feedbackList;
  }
}
