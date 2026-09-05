// packages/l1_interference/test/l1_interference_test.dart
//
// Unit tests for GOP scoring and diagnostic feedback generation.

import 'package:test/test.dart';
import 'package:boli_l1_interference/l1_interference.dart';

void main() {
  group('DartGOPScorer', () {
    late DartGOPScorer scorer;

    setUp(() {
      scorer = const DartGOPScorer(gopThreshold: -2.5);
    });

    test('high confidence posteriors pass with high score', () {
      final vocab = {'k': 1, 'a': 2, 'm': 3};
      final target = ['k', 'a', 'm'];

      // Synthetic 30 frames with strong confidence on target tokens
      final logProbs = List.generate(
        30,
        (i) {
          final row = List.filled(5, -10.0);
          if (i < 10)
            row[1] = 0.0;
          else if (i < 20)
            row[2] = 0.0;
          else
            row[3] = 0.0;
          return row;
        },
      );

      final assessment = scorer.scoreUtterance(
        ctcLogProbs: logProbs,
        vocabMap: vocab,
        targetPhonemes: target,
        targetText: 'kam',
      );

      expect(assessment.overallScore, greaterThan(90.0));
      expect(assessment.weakPhonemes, isEmpty);
      expect(assessment.phonemeScores.every((s) => s.isPass), isTrue);
    });

    test('substitutions are flagged in weakPhonemes', () {
      final vocab = {'p': 1, 'a': 2, 'ʈ': 3, 't̪': 4};
      final target = ['p', 'a', 'ʈ'];

      final logProbs = List.generate(
        30,
        (i) {
          final row = List.filled(5, -10.0);
          if (i < 10)
            row[1] = 0.0;
          else if (i < 20)
            row[2] = 0.0;
          else
            row[4] = 0.0; // Substituted dental t̪ instead of retroflex ʈ
          return row;
        },
      );

      final assessment = scorer.scoreUtterance(
        ctcLogProbs: logProbs,
        vocabMap: vocab,
        targetPhonemes: target,
        targetText: 'paʈ',
      );

      expect(assessment.weakPhonemes, contains('ʈ'));
    });
  });

  group('DiagnosticEngine', () {
    test('generates articulatory guidance for weak retroflex phonemes', () {
      final confusionSet = CorridorConfusionSet(
        corridorId: 'bhojpuri_tamil',
        sourceLanguage: 'Hindi',
        targetLanguage: 'Tamil',
        confusionPairs: [
          const ConfusionPair(
            targetPhonemeIpa: 'ʈ',
            targetGrapheme: 'ட',
            confusedPhonemeIpa: 't̪',
            confusedGrapheme: 'त',
            phenomenon: 'Dentalization of Retroflex',
            priorProbability: 0.42,
            minimalPairs: [],
            articulatoryCueHindi: 'अपनी जीभ को थोड़ा पीछे मोड़ें।',
            articulatoryCueBhojpuri: 'जीभ के पाछे मोड़ीं।',
            articulatoryCueEn: 'Curl tongue back.',
          )
        ],
      );

      final engine = DiagnosticEngine(confusionSet: confusionSet);
      const assessment = GOPAssessment(
        targetText: 'paʈ',
        targetPhonemes: ['p', 'a', 'ʈ'],
        overallScore: 65.0,
        phonemeScores: [],
        weakPhonemes: ['ʈ'],
      );

      final feedback =
          engine.generateFeedback(assessment, languagePreference: 'hi');
      expect(feedback.length, equals(1));
      expect(feedback.first.phonemeIpa, equals('ʈ'));
      expect(feedback.first.primaryAdvice, contains('जीभ'));
    });
  });
}
