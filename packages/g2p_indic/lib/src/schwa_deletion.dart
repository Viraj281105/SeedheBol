// packages/g2p_indic/lib/src/schwa_deletion.dart
//
// Hindi Schwa Deletion Rule Engine
//
// In Devanagari, every consonant carries an inherent schwa /ə/. In spoken
// Hindi, many of these schwas are deleted according to well-studied
// phonotactic rules (Ohala 1983, Narasimhan et al. 2004).
//
// Example: कमल (ka-ma-la orthographically) is pronounced /kəməl/ — the
// word-final schwa is deleted. Without this engine, CTC forced alignment
// would attempt to align against /kəmələ/ and drift catastrophically.
//
// This is one of the most critical components for Hindi G2P accuracy.

import 'unicode_decomposer.dart';

/// Applies Hindi schwa deletion rules to a decomposed phoneme sequence.
///
/// Rules implemented (based on Narasimhan et al. 2004 and Choudhury et al. 2004):
///
/// 1. **Word-final schwa deletion**: The inherent schwa of the last consonant
///    in a word is always deleted (unless the word is monosyllabic CəC).
///
/// 2. **Medial schwa deletion**: An inherent schwa between two consonants is
///    deleted if:
///    - The following consonant is NOT part of a conjunct (no virama before it)
///    - The preceding syllable already has a vowel
///    - Deleting the schwa would not create an illegal consonant cluster
///
/// 3. **Schwa retention before conjuncts**: If a consonant is followed by
///    a conjunct cluster (C₁ virama C₂), the schwa before C₁ is typically retained.
///
/// 4. **Monosyllable protection**: In monosyllabic words (single vowel nucleus),
///    the schwa is never deleted.
class HindiSchwaDeleter {
  /// Apply schwa deletion to a list of decomposed phoneme elements.
  ///
  /// Returns a new list with inherent schwas removed where appropriate.
  /// The original list is not modified.
  List<PhonemeElement> applyDeletion(List<PhonemeElement> elements) {
    if (elements.isEmpty) return elements;

    // Split into words at space boundaries
    final words = _splitIntoWords(elements);
    final result = <PhonemeElement>[];

    for (final word in words) {
      if (word.isEmpty) continue;

      // Check if it's a space separator
      if (word.length == 1 && word[0].ipa == ' ') {
        result.addAll(word);
        continue;
      }

      result.addAll(_processWord(word));
    }

    return result;
  }

  /// Split element sequence into word-level groups.
  List<List<PhonemeElement>> _splitIntoWords(List<PhonemeElement> elements) {
    final words = <List<PhonemeElement>>[];
    var current = <PhonemeElement>[];

    for (final elem in elements) {
      if (elem.ipa == ' ') {
        if (current.isNotEmpty) words.add(current);
        words.add([elem]);
        current = <PhonemeElement>[];
      } else {
        current.add(elem);
      }
    }
    if (current.isNotEmpty) words.add(current);
    return words;
  }

  /// Process a single word for schwa deletion.
  List<PhonemeElement> _processWord(List<PhonemeElement> word) {
    // Count inherent schwas — if 0 or 1 vowel nucleus total, protect it
    final inherentSchwas = word.where((e) => e.isInherentVowel).toList();
    final nonSchwaVowels = word
        .where(
          (e) => !e.isInherentVowel && _isVowel(e.ipa) && e.ipa != ' ',
        )
        .toList();

    // Monosyllable protection: if removing all schwas would leave no vowel
    // nucleus, keep at least one.
    final totalVowelNuclei = inherentSchwas.length + nonSchwaVowels.length;
    if (totalVowelNuclei <= 1) {
      return word; // Cannot delete the only vowel
    }

    final result = <PhonemeElement>[];
    final marked = List<bool>.filled(word.length, false);

    // Pass 1: Mark word-final schwa for deletion
    for (int i = word.length - 1; i >= 0; i--) {
      if (word[i].isInherentVowel) {
        marked[i] = true; // Delete final schwa
        break;
      }
      if (_isConsonant(word[i].ipa)) continue;
      break; // Hit a non-inherent vowel or other element
    }

    // Pass 2: Mark medial schwas for deletion (simplified Narasimhan rules)
    for (int i = 0; i < word.length; i++) {
      if (!word[i].isInherentVowel || marked[i]) continue;

      // Find the preceding consonant and the following consonant
      final prevConsonant = _findPreviousConsonant(word, i);
      final nextConsonant = _findNextConsonant(word, i);

      if (prevConsonant == null || nextConsonant == null) continue;

      // Check if preceding syllable has a vowel (enables deletion)
      final hasPrecedingVowel = _hasPrecedingVowel(word, prevConsonant);
      if (!hasPrecedingVowel) continue;

      // Check if deletion would create an illegal cluster
      final prevIpa = word[prevConsonant].ipa;
      final nextIpa = word[nextConsonant].ipa;

      if (_isLegalCluster(prevIpa, nextIpa)) {
        // Ensure we're not deleting the only remaining vowel
        final remainingVowels = _countRemainingVowels(word, marked, i);
        if (remainingVowels > 1) {
          marked[i] = true;
        }
      }
    }

    // Build result, excluding marked schwas
    for (int i = 0; i < word.length; i++) {
      if (!marked[i]) {
        result.add(word[i]);
      }
    }

    return result;
  }

  /// Returns the index of the consonant immediately before position [i].
  int? _findPreviousConsonant(List<PhonemeElement> word, int i) {
    for (int j = i - 1; j >= 0; j--) {
      if (_isConsonant(word[j].ipa)) return j;
      if (_isVowel(word[j].ipa)) return null; // Hit a vowel, no preceding C
    }
    return null;
  }

  /// Returns the index of the consonant immediately after position [i].
  int? _findNextConsonant(List<PhonemeElement> word, int i) {
    for (int j = i + 1; j < word.length; j++) {
      if (_isConsonant(word[j].ipa)) return j;
      if (_isVowel(word[j].ipa)) return null;
    }
    return null;
  }

  /// Check if there's a vowel before the consonant at index [consonantIdx].
  bool _hasPrecedingVowel(List<PhonemeElement> word, int consonantIdx) {
    for (int j = consonantIdx - 1; j >= 0; j--) {
      if (_isVowel(word[j].ipa)) return true;
    }
    return false;
  }

  /// Count vowels remaining if we mark position [excludeIdx].
  int _countRemainingVowels(
    List<PhonemeElement> word,
    List<bool> marked,
    int excludeIdx,
  ) {
    int count = 0;
    for (int i = 0; i < word.length; i++) {
      if (i == excludeIdx) continue;
      if (marked[i]) continue;
      if (_isVowel(word[i].ipa)) count++;
    }
    return count;
  }

  /// Check if two consonants can legally cluster in Hindi.
  ///
  /// Hindi permits clusters like /st/, /nt/, /nd/, /mp/, /nk/, /sk/, etc.
  /// but disallows clusters like /pk/, /tk/, /km/ in onset position.
  bool _isLegalCluster(String c1, String c2) {
    // Nasal + homorganic stop is always legal
    if (_isNasal(c1) && _isStop(c2)) return true;

    // Sibilant + stop is legal
    if (_isSibilant(c1) && _isStop(c2)) return true;

    // Liquid + stop or stop + liquid
    if (_isLiquid(c1) || _isLiquid(c2)) return true;

    // Same-place clusters are generally legal in coda position
    return false;
  }

  bool _isVowel(String ipa) {
    return ipa.contains(RegExp(r'[əaeiouɪʊɛɔæɑ]'));
  }

  bool _isConsonant(String ipa) {
    return ipa.isNotEmpty && !_isVowel(ipa) && ipa != ' ' && ipa != '̃';
  }

  bool _isNasal(String ipa) {
    return ['m', 'n', 'n̪', 'ɳ', 'ɲ', 'ŋ'].contains(ipa);
  }

  bool _isStop(String ipa) {
    const stops = {
      'k',
      'kʰ',
      'ɡ',
      'ɡʰ',
      'tʃ',
      'tʃʰ',
      'dʒ',
      'dʒʰ',
      'ʈ',
      'ʈʰ',
      'ɖ',
      'ɖʰ',
      't̪',
      't̪ʰ',
      'd̪',
      'd̪ʰ',
      'p',
      'pʰ',
      'b',
      'bʰ',
    };
    return stops.contains(ipa);
  }

  bool _isSibilant(String ipa) {
    return ['s', 'ʃ', 'ʂ'].contains(ipa);
  }

  bool _isLiquid(String ipa) {
    return ['ɾ', 'r', 'l', 'ɭ'].contains(ipa);
  }
}
