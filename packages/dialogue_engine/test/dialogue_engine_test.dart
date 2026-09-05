// packages/dialogue_engine/test/dialogue_engine_test.dart
//
// Unit tests for dialogue graph navigation and intent matching.

import 'package:test/test.dart';
import 'package:boli_shared_models/shared_models.dart';
import 'package:boli_dialogue_engine/dialogue_engine.dart';

void main() {
  late Situation testSituation;

  setUp(() {
    testSituation = const Situation(
      situationId: 'test_wage_dispute',
      titleL1: 'मजदूरी विवाद',
      titleL2: 'கூலி விவாதம்',
      description: 'Test situation for unit testing',
      domain: Domain.construction,
      corridor: Corridor.bhojpuriTamil,
      personaName: 'Supervisor',
      entryNodeId: 'node_01',
      nodes: {
        'node_01': DialogueNode(
          nodeId: 'node_01',
          l2Text: 'வணக்கம்',
          transliteration: 'Vanakkam',
          l1Translation: 'नमस्ते',
          isPersonaTurn: false,
          fallbackNodeId: 'node_fallback',
          branches: [
            DialogueBranch(
              intentLabel: 'report_missing_wage',
              triggerKeywords: ['சம்பளம்', 'குறையுது', 'sambalam'],
              targetNodeId: 'node_02',
            )
          ],
        ),
        'node_02': DialogueNode(
          nodeId: 'node_02',
          l2Text: 'எந்த நாள் வரலை?',
          transliteration: 'Endha naal varalai?',
          l1Translation: 'किस दिन नहीं आए?',
          isPersonaTurn: true,
          branches: [],
        ),
        'node_fallback': DialogueNode(
          nodeId: 'node_fallback',
          l2Text: 'மறுபடியும் சொல்லுங்க',
          transliteration: 'Marupadiyum sollunga',
          l1Translation: 'फिर से बोलिए',
          isPersonaTurn: true,
          branches: [],
        ),
      },
    );
  });

  group('IntentMatcher', () {
    const matcher = IntentMatcher();

    test('exact keyword matches branch with 1.0 confidence', () {
      final branches = testSituation.nodes['node_01']!.branches;
      final match = matcher.findBestMatch('என் சம்பளம் வரல', branches);
      expect(match, isNotNull);
      expect(match!.branch.targetNodeId, equals('node_02'));
      expect(match.confidence, equals(1.0));
    });

    test('unrelated string returns null', () {
      final branches = testSituation.nodes['node_01']!.branches;
      final match = matcher.findBestMatch('தண்ணீர் குடிக்கணும்', branches);
      expect(match, isNull);
    });
  });

  group('DialogueSession', () {
    test('session advances correctly upon valid intent match', () {
      final session = DialogueSession(situation: testSituation);
      expect(session.currentNode.nodeId, equals('node_01'));
      expect(session.isComplete, isFalse);

      final res = session.processUserTurn('சம்பளம் குறையுது');
      expect(res.status, equals(TransitionStatus.success));
      expect(session.currentNode.nodeId, equals('node_02'));
      expect(
          session.isComplete, isTrue); // node_02 has no branches -> completed
    });

    test('session triggers fallback reprompt on unrecognized input', () {
      final session = DialogueSession(situation: testSituation);
      final res = session.processUserTurn('ஏதோ ஒன்னு');
      expect(res.status, equals(TransitionStatus.fallbackReprompt));
      expect(session.currentNode.nodeId, equals('node_fallback'));
    });
  });
}
