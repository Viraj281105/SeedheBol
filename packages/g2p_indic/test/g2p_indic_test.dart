// packages/g2p_indic/test/g2p_indic_test.dart
//
// Unit tests for Indic G2P engine (Devanagari, Tamil, Hindi schwa deletion).

import 'package:test/test.dart';
import 'package:boli_g2p_indic/g2p_indic.dart';

void main() {
  late G2PEngine engine;

  setUp(() {
    engine = G2PEngine();
  });

  group('Hindi G2P & Schwa Deletion', () {
    test('deletes word-final inherent schwa in multi-syllabic words', () {
      final res = engine.convert('कमल', languageCode: 'hi');
      // 'ka-ma-la' -> /k ə m ə l/ (word-final schwa deleted)
      expect(res.phonemes.last, equals('l'));
      expect(res.phonemes, contains('ə'));
      expect(res.languageCode, equals('hi'));
    });

    test('preserves aspiration on voiced/voiceless aspirated stops', () {
      final res = engine.convert('भारत', languageCode: 'hi');
      expect(res.phonemes.first, equals('bʰ'));
      expect(res.phonemes[1], equals('aː'));
    });

    test('replaces inherent schwa when matra is present', () {
      final res = engine.convert('किताब', languageCode: 'hi');
      expect(res.phonemes[0], equals('k'));
      expect(res.phonemes[1], equals('ɪ'));
    });
  });

  group('Tamil G2P & Allophony', () {
    test('maps unique Tamil retroflex approximant zha /ɻ/', () {
      final res = engine.convert('பழம்', languageCode: 'ta');
      expect(res.phonemes, contains('ɻ'));
      expect(res.languageCode, equals('ta'));
    });

    test('voices intervocalic stops in surface phonology', () {
      final res = engine.convert('படம்', languageCode: 'ta');
      // /p a ɖ a m/ -> 'ட' between vowels is voiced /ɖ/
      expect(res.phonemes, contains('ɖ'));
    });

    test('voices post-nasal stops', () {
      final res = engine.convert('தம்பி', languageCode: 'ta');
      // /t̪ a m b i/ -> 'ப' after 'ம' is voiced /b/
      expect(res.phonemes, contains('b'));
    });

    test('keeps word-initial stops voiceless', () {
      final res = engine.convert('காசு', languageCode: 'ta');
      expect(res.phonemes.first, equals('k'));
    });
  });
}
