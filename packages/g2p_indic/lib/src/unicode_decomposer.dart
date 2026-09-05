// packages/g2p_indic/lib/src/unicode_decomposer.dart
//
// Decomposes Unicode Indic script text into constituent phonemic components.
//
// Indian abugida scripts (Devanagari, Tamil, etc.) encode phonetic information
// structurally: each consonant carries an inherent vowel (schwa in Devanagari,
// /a/ in Tamil), modified by dependent vowel signs (matras), suppressed by
// virama/halant, or combined via conjuncts. This decomposer extracts the
// underlying phonemic representation from the Unicode codepoint sequence.
//
// Reference: Unicode Standard Chapter 12 (South and Central Asian Scripts).

/// A single decomposed phonemic element from Unicode text.
class PhonemeElement {
  /// The original Unicode character(s) this element was derived from.
  final String grapheme;

  /// The IPA phoneme symbol this maps to.
  final String ipa;

  /// Character offset in the original string.
  final int offset;

  /// Whether this is an inherent vowel (schwa) that may be subject to deletion.
  final bool isInherentVowel;

  const PhonemeElement({
    required this.grapheme,
    required this.ipa,
    required this.offset,
    this.isInherentVowel = false,
  });

  @override
  String toString() => 'PhonemeElement($grapheme → $ipa, offset=$offset)';
}

/// Decomposes Devanagari Unicode text into phonemic elements.
///
/// Handles consonants, dependent/independent vowels, virama (halant),
/// nukta, anusvara, chandrabindu, and visarga.
class DevanagariDecomposer {
  /// Devanagari consonant codepoints → base IPA (without inherent schwa).
  static const Map<int, String> _consonants = {
    0x0915: 'k', // क
    0x0916: 'kʰ', // ख
    0x0917: 'ɡ', // ग
    0x0918: 'ɡʰ', // घ
    0x0919: 'ŋ', // ङ
    0x091A: 'tʃ', // च
    0x091B: 'tʃʰ', // छ
    0x091C: 'dʒ', // ज
    0x091D: 'dʒʰ', // झ
    0x091E: 'ɲ', // ञ
    0x091F: 'ʈ', // ट
    0x0920: 'ʈʰ', // ठ
    0x0921: 'ɖ', // ड
    0x0922: 'ɖʰ', // ढ
    0x0923: 'ɳ', // ण
    0x0924: 't̪', // त
    0x0925: 't̪ʰ', // थ
    0x0926: 'd̪', // द
    0x0927: 'd̪ʰ', // ध
    0x0928: 'n̪', // न
    0x092A: 'p', // प
    0x092B: 'pʰ', // फ
    0x092C: 'b', // ब
    0x092D: 'bʰ', // भ
    0x092E: 'm', // म
    0x092F: 'j', // य
    0x0930: 'ɾ', // र
    0x0932: 'l', // ल
    0x0935: 'ʋ', // व
    0x0936: 'ʃ', // श
    0x0937: 'ʂ', // ष
    0x0938: 's', // स
    0x0939: 'h', // ह
  };

  /// Devanagari independent vowel codepoints → IPA.
  static const Map<int, String> _independentVowels = {
    0x0905: 'ə', // अ
    0x0906: 'aː', // आ
    0x0907: 'ɪ', // इ
    0x0908: 'iː', // ई
    0x0909: 'ʊ', // उ
    0x090A: 'uː', // ऊ
    0x090F: 'eː', // ए
    0x0910: 'ɛː', // ऐ
    0x0913: 'oː', // ओ
    0x0914: 'ɔː', // औ
  };

  /// Devanagari dependent vowel signs (matras) → IPA.
  static const Map<int, String> _matras = {
    0x093E: 'aː', // ा
    0x093F: 'ɪ', // ि
    0x0940: 'iː', // ी
    0x0941: 'ʊ', // ु
    0x0942: 'uː', // ू
    0x0947: 'eː', // े
    0x0948: 'ɛː', // ै
    0x094B: 'oː', // ो
    0x094C: 'ɔː', // ौ
  };

  /// Virama / Halant codepoint — suppresses the inherent schwa.
  static const int _virama = 0x094D;

  /// Anusvara — nasalization marker.
  static const int _anusvara = 0x0902;

  /// Chandrabindu — vowel nasalization.
  static const int _chandrabindu = 0x0901;

  /// Visarga — voiceless glottal fricative.
  static const int _visarga = 0x0903;

  /// Nukta — modifies consonant (e.g., क़ → /q/).
  static const int _nukta = 0x093C;

  /// Decompose a Devanagari string into a sequence of [PhonemeElement]s.
  ///
  /// Each consonant carries an inherent schwa /ə/ unless:
  /// - Followed by a virama (halant)
  /// - Followed by a dependent vowel sign (matra)
  ///
  /// Inherent schwas are marked with [PhonemeElement.isInherentVowel] = true
  /// so the schwa deletion engine can remove them contextually.
  List<PhonemeElement> decompose(String text) {
    final result = <PhonemeElement>[];
    final codeUnits = text.runes.toList();
    final length = codeUnits.length;

    for (int i = 0; i < length; i++) {
      final cp = codeUnits[i];
      final nextCp = (i + 1 < length) ? codeUnits[i + 1] : null;
      final char = String.fromCharCode(cp);

      // Check for nukta — modifies preceding consonant
      if (cp == _nukta) continue;

      // Check for virama — skip, it was handled by the consonant
      if (cp == _virama) continue;

      // Check for matra — handled by consonant lookahead
      if (_matras.containsKey(cp)) continue;

      // Anusvara → nasalization
      if (cp == _anusvara) {
        result.add(PhonemeElement(grapheme: char, ipa: 'ŋ', offset: i));
        continue;
      }

      // Chandrabindu → vowel nasalization (modify previous vowel)
      if (cp == _chandrabindu) {
        // Mark nasalization — handled at a higher level
        result.add(PhonemeElement(grapheme: char, ipa: '̃', offset: i));
        continue;
      }

      // Visarga
      if (cp == _visarga) {
        result.add(PhonemeElement(grapheme: char, ipa: 'h', offset: i));
        continue;
      }

      // Independent vowel
      if (_independentVowels.containsKey(cp)) {
        result.add(PhonemeElement(
          grapheme: char,
          ipa: _independentVowels[cp]!,
          offset: i,
        ));
        continue;
      }

      // Consonant
      if (_consonants.containsKey(cp)) {
        // Check for nukta modification
        final hasNukta = nextCp == _nukta;
        final baseIpa = _consonants[cp]!;

        result.add(PhonemeElement(grapheme: char, ipa: baseIpa, offset: i));

        if (hasNukta) {
          i++; // Skip nukta
        }

        // Determine what follows the consonant
        final afterConsonant = (i + 1 < length) ? codeUnits[i + 1] : null;

        if (afterConsonant == _virama) {
          // Virama suppresses inherent vowel — no schwa added
          i++; // Skip virama
        } else if (afterConsonant != null &&
            _matras.containsKey(afterConsonant)) {
          // Matra replaces inherent vowel
          result.add(PhonemeElement(
            grapheme: String.fromCharCode(afterConsonant),
            ipa: _matras[afterConsonant]!,
            offset: i + 1,
          ));
          i++; // Skip matra
        } else {
          // No virama, no matra → inherent schwa /ə/ (subject to deletion)
          result.add(PhonemeElement(
            grapheme: '',
            ipa: 'ə',
            offset: i,
            isInherentVowel: true,
          ));
        }
        continue;
      }

      // Whitespace and punctuation — emit silence boundary
      if (cp == 0x20 || cp == 0x0A) {
        result.add(PhonemeElement(grapheme: char, ipa: ' ', offset: i));
      }
    }

    return result;
  }
}

/// Decomposes Tamil Unicode text into phonemic elements.
///
/// Tamil script is structurally simpler than Devanagari: no aspiration
/// or voicing contrast in stops (these are allophonic), and the
/// inherent vowel is /a/ rather than schwa.
class TamilDecomposer {
  /// Tamil consonant codepoints → base IPA.
  static const Map<int, String> _consonants = {
    0x0B95: 'k', // க
    0x0B99: 'ŋ', // ங
    0x0B9A: 'tʃ', // ச
    0x0B9E: 'ɲ', // ஞ
    0x0B9F: 'ʈ', // ட
    0x0BA3: 'ɳ', // ண
    0x0BA4: 't̪', // த
    0x0BA8: 'n̪', // ந
    0x0BAA: 'p', // ப
    0x0BAE: 'm', // ம
    0x0BAF: 'j', // ய
    0x0BB0: 'r', // ர
    0x0BB2: 'l', // ல
    0x0BB5: 'ʋ', // வ
    0x0BB4: 'ɻ', // ழ (retroflex approximant — unique to Tamil)
    0x0BB3: 'ɭ', // ள (retroflex lateral)
    0x0BB1: 'ɾ', // ற (alveolar flap)
    0x0BA9: 'n', // ன (alveolar nasal)
    // Grantha consonants (borrowed)
    0x0BB8: 's', // ஸ
    0x0BB7: 'ʃ', // ஷ
    0x0BB9: 'h', // ஹ
    0x0B9C: 'dʒ', // ஜ
  };

  /// Tamil independent vowels.
  static const Map<int, String> _independentVowels = {
    0x0B85: 'a', // அ
    0x0B86: 'aː', // ஆ
    0x0B87: 'i', // இ
    0x0B88: 'iː', // ஈ
    0x0B89: 'u', // உ
    0x0B8A: 'uː', // ஊ
    0x0B8E: 'e', // எ
    0x0B8F: 'eː', // ஏ
    0x0B90: 'ai', // ஐ
    0x0B92: 'o', // ஒ
    0x0B93: 'oː', // ஓ
    0x0B94: 'au', // ஔ
  };

  /// Tamil dependent vowel signs.
  static const Map<int, String> _matras = {
    0x0BBE: 'aː', // ா
    0x0BBF: 'i', // ி
    0x0BC0: 'iː', // ீ
    0x0BC1: 'u', // ு
    0x0BC2: 'uː', // ூ
    0x0BC6: 'e', // ெ
    0x0BC7: 'eː', // ே
    0x0BC8: 'ai', // ை
    0x0BCA: 'o', // ொ
    0x0BCB: 'oː', // ோ
    0x0BCC: 'au', // ௌ
  };

  /// Tamil pulli (virama equivalent).
  static const int _pulli = 0x0BCD;

  /// Decompose Tamil text into phonemic elements.
  List<PhonemeElement> decompose(String text) {
    final result = <PhonemeElement>[];
    final codeUnits = text.runes.toList();
    final length = codeUnits.length;

    for (int i = 0; i < length; i++) {
      final cp = codeUnits[i];
      final char = String.fromCharCode(cp);

      if (cp == _pulli) continue;
      if (_matras.containsKey(cp)) continue;

      if (_independentVowels.containsKey(cp)) {
        result.add(PhonemeElement(
          grapheme: char,
          ipa: _independentVowels[cp]!,
          offset: i,
        ));
        continue;
      }

      if (_consonants.containsKey(cp)) {
        result.add(PhonemeElement(
          grapheme: char,
          ipa: _consonants[cp]!,
          offset: i,
        ));

        final afterConsonant = (i + 1 < length) ? codeUnits[i + 1] : null;

        if (afterConsonant == _pulli) {
          i++; // Skip pulli — no inherent vowel
        } else if (afterConsonant != null &&
            _matras.containsKey(afterConsonant)) {
          result.add(PhonemeElement(
            grapheme: String.fromCharCode(afterConsonant),
            ipa: _matras[afterConsonant]!,
            offset: i + 1,
          ));
          i++;
        } else {
          // Inherent vowel /a/ in Tamil (not schwa like Hindi)
          result.add(PhonemeElement(
            grapheme: '',
            ipa: 'a',
            offset: i,
            isInherentVowel: true,
          ));
        }
        continue;
      }

      if (cp == 0x20 || cp == 0x0A) {
        result.add(PhonemeElement(grapheme: char, ipa: ' ', offset: i));
      }
    }

    return result;
  }
}
