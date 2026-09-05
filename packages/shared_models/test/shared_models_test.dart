// packages/shared_models/test/shared_models_test.dart
//
// Unit tests for Seedhebol shared domain models and JSON serialization.

import 'package:test/test.dart';
import 'package:boli_shared_models/shared_models.dart';

void main() {
  group('PhonemeInventory', () {
    test('hindiInventory contains retroflex and aspirated stops', () {
      final retroflexT = hindiInventory.lookup('ʈ');
      expect(retroflexT, isNotNull);
      expect(retroflexT!.isRetroflex, isTrue);
      expect(retroflexT.category, equals(PhonemeCategory.consonant));

      final aspK = hindiInventory.lookup('kʰ');
      expect(aspK, isNotNull);
      expect(aspK!.isAspirated, isTrue);
    });

    test('tamilInventory contains unique retroflex approximant zha', () {
      final zha = tamilInventory.lookup('ɻ');
      expect(zha, isNotNull);
      expect(zha!.label, contains('zha'));
    });
  });

  group('Situation Model JSON round-trip', () {
    test('Situation serializes and deserializes accurately', () {
      const situation = Situation(
        situationId: 'sit_001',
        titleL1: 'मजदूरी',
        titleL2: 'கூலி',
        description: 'Wage negotiation',
        domain: Domain.construction,
        corridor: Corridor.bhojpuriTamil,
        personaName: 'Murugan',
        entryNodeId: 'n1',
        nodes: {
          'n1': DialogueNode(
            nodeId: 'n1',
            l2Text: 'வணக்கம்',
            transliteration: 'Vanakkam',
            l1Translation: 'नमस्ते',
            isPersonaTurn: true,
            branches: [],
          )
        },
      );

      final jsonMap = situation.toJson();
      final restored = Situation.fromJson(jsonMap);

      expect(restored.situationId, equals(situation.situationId));
      expect(restored.entryNodeId, equals(situation.entryNodeId));
      expect(restored.nodes.length, equals(1));
      expect(restored.nodes['n1']!.l2Text, equals('வணக்கம்'));
    });
  });

  group('UserProfile Model JSON round-trip', () {
    test('UserProfile serializes and deserializes accurately', () {
      const profile = UserProfile(
        profileId: 'usr_1001',
        l1LanguageCode: 'hi',
        l2LanguageCode: 'ta',
        primaryDomain: Domain.construction,
        literacyLevel: LiteracyLevel.zeroLiteracy,
        corridor: Corridor.bhojpuriTamil,
        createdAtMs: 1700000000000,
      );

      final jsonMap = profile.toJson();
      final restored = UserProfile.fromJson(jsonMap);

      expect(restored.profileId, equals(profile.profileId));
      expect(restored.literacyLevel, equals(LiteracyLevel.zeroLiteracy));
    });
  });
}
