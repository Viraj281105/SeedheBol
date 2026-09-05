// packages/g2p_indic/lib/src/g2p_engine.dart
//
// Unified Grapheme-to-Phoneme engine for Seedhebol.
//
// Dispatches to language-specific backends based on the provided language code.
// Input: Unicode text + language code → Output: IPA phoneme sequence with
// alignment indices for CTC forced alignment.
//
// This is the single entry point consumed by:
// - The forced alignment pipeline (for GOP scoring)
// - The TTS phoneme conditioning (for FastPitch)
// - The pronunciation drill generator (for target phoneme identification)

import 'unicode_decomposer.dart';
import 'schwa_deletion.dart';
import 'tamil_g2p.dart';

/// Result of a G2P conversion, containing the phoneme sequence and
/// alignment mapping back to source text positions.
class G2PResult {
  /// Ordered list of IPA phoneme symbols.
  final List<String> phonemes;

  /// Source text character offsets corresponding to each phoneme.
  /// Used by forced alignment to map CTC frame boundaries back to graphemes.
  final List<int> alignmentOffsets;

  /// The original input text.
  final String sourceText;

  /// The language code used for conversion.
  final String languageCode;

  const G2PResult({
    required this.phonemes,
    required this.alignmentOffsets,
    required this.sourceText,
    required this.languageCode,
  });

  /// Concatenated IPA string with spaces between phonemes.
  String get ipaString => phonemes.where((p) => p != ' ').join(' ');

  /// Total number of non-silence phonemes.
  int get phonemeCount => phonemes.where((p) => p.trim().isNotEmpty).length;

  @override
  String toString() =>
      'G2PResult(lang=$languageCode, phonemes=${phonemes.length}, '
      'ipa="$ipaString")';
}

/// Unified G2P engine supporting multiple Indic languages.
///
/// Usage:
/// ```dart
/// final engine = G2PEngine();
/// final result = engine.convert('कमल', languageCode: 'hi');
/// print(result.ipaString); // 'k ə m ə l'
/// ```
class G2PEngine {
  final DevanagariDecomposer _devanagariDecomposer;
  final TamilDecomposer _tamilDecomposer;
  final HindiSchwaDeleter _schwaDeleter;
  final TamilAllophoneProcessor _tamilProcessor;

  G2PEngine()
      : _devanagariDecomposer = DevanagariDecomposer(),
        _tamilDecomposer = TamilDecomposer(),
        _schwaDeleter = HindiSchwaDeleter(),
        _tamilProcessor = TamilAllophoneProcessor();

  /// Supported language codes.
  static const Set<String> supportedLanguages = {'hi', 'mr', 'ta'};

  /// Convert Unicode text to IPA phoneme sequence.
  ///
  /// Throws [ArgumentError] if [languageCode] is not supported.
  /// Throws [FormatException] if [text] contains no processable characters.
  G2PResult convert(String text, {required String languageCode}) {
    if (!supportedLanguages.contains(languageCode)) {
      throw ArgumentError(
        'Unsupported language code "$languageCode". '
        'Supported: ${supportedLanguages.join(", ")}',
      );
    }

    if (text.trim().isEmpty) {
      throw FormatException('Input text is empty');
    }

    switch (languageCode) {
      case 'hi':
      case 'mr':
        return _convertHindi(text);
      case 'ta':
        return _convertTamil(text);
      default:
        throw StateError('Unreachable: $languageCode');
    }
  }

  /// Hindi G2P: Devanagari decomposition → schwa deletion → IPA output.
  G2PResult _convertHindi(String text) {
    // Step 1: Decompose Devanagari Unicode into raw phoneme elements
    final rawElements = _devanagariDecomposer.decompose(text);

    if (rawElements.isEmpty) {
      throw FormatException(
        'No Devanagari characters found in input: "$text"',
      );
    }

    // Step 2: Apply Hindi schwa deletion rules
    final processedElements = _schwaDeleter.applyDeletion(rawElements);

    // Step 3: Extract phoneme symbols and alignment offsets
    return G2PResult(
      phonemes: processedElements.map((e) => e.ipa).toList(),
      alignmentOffsets: processedElements.map((e) => e.offset).toList(),
      sourceText: text,
      languageCode: 'hi',
    );
  }

  /// Tamil G2P: Tamil decomposition → allophonic voicing → IPA output.
  G2PResult _convertTamil(String text) {
    // Step 1: Decompose Tamil Unicode into raw phoneme elements
    final rawElements = _tamilDecomposer.decompose(text);

    if (rawElements.isEmpty) {
      throw FormatException(
        'No Tamil characters found in input: "$text"',
      );
    }

    // Step 2: Apply Tamil allophonic voicing rules
    final processedElements = _tamilProcessor.applySurfaceRules(rawElements);

    // Step 3: Extract phoneme symbols and alignment offsets
    return G2PResult(
      phonemes: processedElements.map((e) => e.ipa).toList(),
      alignmentOffsets: processedElements.map((e) => e.offset).toList(),
      sourceText: text,
      languageCode: 'ta',
    );
  }
}
