// packages/g2p_indic/lib/src/tamil_g2p.dart
//
// Tamil-specific Grapheme-to-Phoneme allophonic rules.
//
// Tamil has no phonemic voicing or aspiration contrast in stops.
// Voicing is entirely allophonic, governed by positional rules:
//   - Word-initial stops are voiceless: படம் /paʈam/
//   - Intervocalic stops are voiced:    படம் → /paɖam/ (ட → [ɖ] between vowels)
//   - Post-nasal stops are voiced:       தம்பி → /t̪ambi/ (ப → [b] after ம)
//
// These rules are critical for pronunciation assessment: a Hindi speaker
// producing [paʈam] (all voiceless) is actually correct in Tamil phonology,
// while producing [baɖam] (initial voiced) would be an error.

import 'unicode_decomposer.dart';

/// Applies Tamil allophonic voicing rules to a decomposed phoneme sequence.
///
/// This does NOT change the underlying phonemic representation (Tamil has
/// no phonemic voicing contrast). Instead, it produces the expected surface
/// pronunciation that the GOP scorer should compare against.
class TamilAllophoneProcessor {
  /// Voiceless → voiced allophone mappings for Tamil stops.
  static const Map<String, String> _voicedAllophones = {
    'k': 'ɡ',
    'tʃ': 'dʒ',
    'ʈ': 'ɖ',
    't̪': 'd̪',
    'p': 'b',
  };

  /// Apply Tamil allophonic rules to produce surface pronunciation.
  ///
  /// Returns a new list with voicing adjustments applied.
  List<PhonemeElement> applySurfaceRules(List<PhonemeElement> elements) {
    if (elements.isEmpty) return elements;

    final result = <PhonemeElement>[];

    for (int i = 0; i < elements.length; i++) {
      final current = elements[i];

      // Only process stops that have voiced allophones
      if (!_voicedAllophones.containsKey(current.ipa)) {
        result.add(current);
        continue;
      }

      // Rule 1: Word-initial position → voiceless (no change)
      if (_isWordInitial(elements, i)) {
        result.add(current);
        continue;
      }

      // Rule 2: Intervocalic position → voiced
      if (_isIntervocalic(elements, i)) {
        result.add(PhonemeElement(
          grapheme: current.grapheme,
          ipa: _voicedAllophones[current.ipa]!,
          offset: current.offset,
          isInherentVowel: current.isInherentVowel,
        ));
        continue;
      }

      // Rule 3: Post-nasal position → voiced
      if (_isPostNasal(elements, i)) {
        result.add(PhonemeElement(
          grapheme: current.grapheme,
          ipa: _voicedAllophones[current.ipa]!,
          offset: current.offset,
          isInherentVowel: current.isInherentVowel,
        ));
        continue;
      }

      // Rule 4: Geminate (doubled) consonants remain voiceless
      // This is already handled by no modification.

      // Default: voiceless
      result.add(current);
    }

    return result;
  }

  /// Check if the element at [idx] is at word-initial position.
  bool _isWordInitial(List<PhonemeElement> elements, int idx) {
    if (idx == 0) return true;
    // Check if preceded by a space
    for (int j = idx - 1; j >= 0; j--) {
      if (elements[j].ipa == ' ') return true;
      if (_isVowel(elements[j].ipa) || _isConsonant(elements[j].ipa)) {
        return false;
      }
    }
    return true;
  }

  /// Check if the element at [idx] is between two vowels (intervocalic).
  bool _isIntervocalic(List<PhonemeElement> elements, int idx) {
    // Find preceding sound
    String? prevSound;
    for (int j = idx - 1; j >= 0; j--) {
      if (elements[j].ipa == ' ') break;
      if (_isVowel(elements[j].ipa)) {
        prevSound = elements[j].ipa;
        break;
      }
      if (_isConsonant(elements[j].ipa)) break;
    }

    // Find following sound
    String? nextSound;
    for (int j = idx + 1; j < elements.length; j++) {
      if (elements[j].ipa == ' ') break;
      if (_isVowel(elements[j].ipa)) {
        nextSound = elements[j].ipa;
        break;
      }
      if (_isConsonant(elements[j].ipa)) break;
    }

    return prevSound != null &&
        _isVowel(prevSound) &&
        nextSound != null &&
        _isVowel(nextSound);
  }

  /// Check if the element at [idx] is immediately after a nasal consonant.
  bool _isPostNasal(List<PhonemeElement> elements, int idx) {
    if (idx <= 0 || idx >= elements.length) return false;
    final prev = elements[idx - 1].ipa;
    if (prev == ' ') return false;
    return _isNasal(prev);
  }

  bool _isVowel(String ipa) {
    return ipa.contains(RegExp(r'[aeiouɪʊɛɔ]'));
  }

  bool _isConsonant(String ipa) {
    return ipa.isNotEmpty && !_isVowel(ipa) && ipa != ' ' && ipa != '̃';
  }

  bool _isNasal(String ipa) {
    return ['m', 'n', 'n̪', 'ɳ', 'ɲ', 'ŋ'].contains(ipa);
  }
}
