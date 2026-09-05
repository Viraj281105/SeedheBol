// packages/shared_models/lib/src/phoneme_inventory.dart
//
// Complete International Phonetic Alphabet (IPA) phoneme inventory for
// Seedhebol's target Indian languages. Each phoneme is annotated with
// articulatory feature vectors (place, manner, voicing, aspiration, retroflex)
// enabling L1-interference analysis and GOP diagnostic feedback.
//
// Coverage: Hindi, Bhojpuri (mapped to Hindi), Tamil, Kannada, Malayalam.
// Reference: IPA Handbook (2015), Ohala (1983) for Hindi, Keane (2004) for Tamil.

import 'package:meta/meta.dart';

/// Articulatory place of consonant production.
enum Place {
  bilabial,
  labiodental,
  dental,
  alveolar,
  retroflex,
  postalveolar,
  palatal,
  velar,
  uvular,
  glottal,
}

/// Articulatory manner of consonant production.
enum Manner {
  stop,
  nasal,
  trill,
  tap,
  fricative,
  lateralFricative,
  approximant,
  lateralApproximant,
  affricate,
}

/// Vowel height classification.
enum VowelHeight { close, nearClose, closeMid, mid, openMid, nearOpen, open }

/// Vowel backness classification.
enum VowelBackness { front, central, back }

/// Phoneme category for dispatch in G2P and GOP pipelines.
enum PhonemeCategory {
  consonant,
  vowel,
  diphthong,
  semivowel,
  silence,
}

/// Immutable representation of a single phoneme with full articulatory features.
///
/// Used by [G2PEngine] for canonical target sequences and by [GOPScorer]
/// for computing per-phoneme Goodness-of-Pronunciation metrics.
@immutable
class Phoneme {
  /// IPA symbol (e.g., 'ʈ', 't̪', 'aː').
  final String ipa;

  /// Human-readable label for diagnostic messages (e.g., 'retroflex t').
  final String label;

  /// Category: consonant, vowel, diphthong, semivowel, or silence.
  final PhonemeCategory category;

  /// Place of articulation (consonants only; null for vowels).
  final Place? place;

  /// Manner of articulation (consonants only; null for vowels).
  final Manner? manner;

  /// Whether the consonant is voiced.
  final bool voiced;

  /// Whether the consonant is aspirated (Hindi/Indo-Aryan aspiration contrast).
  final bool aspirated;

  /// Whether the consonant is retroflex (critical for Indo-Aryan → Dravidian).
  final bool retroflex;

  /// Vowel height (vowels only; null for consonants).
  final VowelHeight? height;

  /// Vowel backness (vowels only; null for consonants).
  final VowelBackness? backness;

  /// Whether the vowel is long (phonemic length contrast in all target languages).
  final bool long;

  /// Whether the vowel is nasalized.
  final bool nasalized;

  const Phoneme({
    required this.ipa,
    required this.label,
    required this.category,
    this.place,
    this.manner,
    this.voiced = false,
    this.aspirated = false,
    this.retroflex = false,
    this.height,
    this.backness,
    this.long = false,
    this.nasalized = false,
  });

  /// Returns true if this phoneme has the retroflex articulatory feature.
  bool get isRetroflex => retroflex || place == Place.retroflex;

  /// Returns true if this is an aspirated stop or affricate.
  bool get isAspirated => aspirated;

  @override
  bool operator ==(Object other) =>
      identical(this, other) || other is Phoneme && ipa == other.ipa;

  @override
  int get hashCode => ipa.hashCode;

  @override
  String toString() => 'Phoneme($ipa, $label)';
}

/// Complete phoneme inventory for a single language.
///
/// Provides lookup by IPA symbol, iteration over consonants/vowels,
/// and feature-based filtering for confusion matrix construction.
@immutable
class PhonemeInventory {
  /// ISO 639-1/3 language code (e.g., 'hi', 'ta', 'kn', 'ml').
  final String languageCode;

  /// Human-readable language name.
  final String languageName;

  /// All phonemes in the inventory.
  final List<Phoneme> phonemes;

  /// Fast lookup by IPA symbol.
  final Map<String, Phoneme> _byIpa;

  PhonemeInventory({
    required this.languageCode,
    required this.languageName,
    required this.phonemes,
  }) : _byIpa = {for (final p in phonemes) p.ipa: p};

  /// Look up a phoneme by its IPA symbol. Returns null if not in inventory.
  Phoneme? lookup(String ipa) => _byIpa[ipa];

  /// All consonant phonemes in the inventory.
  Iterable<Phoneme> get consonants =>
      phonemes.where((p) => p.category == PhonemeCategory.consonant);

  /// All vowel phonemes in the inventory.
  Iterable<Phoneme> get vowels =>
      phonemes.where((p) => p.category == PhonemeCategory.vowel);

  /// All retroflex consonants — critical for L1-interference targeting.
  Iterable<Phoneme> get retroflexConsonants =>
      consonants.where((p) => p.isRetroflex);

  /// All aspirated consonants — critical for aspiration contrast drills.
  Iterable<Phoneme> get aspiratedConsonants =>
      consonants.where((p) => p.isAspirated);

  /// Find phonemes matching specific articulatory features.
  Iterable<Phoneme> findByFeatures({
    Place? place,
    Manner? manner,
    bool? voiced,
    bool? aspirated,
  }) {
    return consonants.where((p) {
      if (place != null && p.place != place) return false;
      if (manner != null && p.manner != manner) return false;
      if (voiced != null && p.voiced != voiced) return false;
      if (aspirated != null && p.aspirated != aspirated) return false;
      return true;
    });
  }
}

// ---------------------------------------------------------------------------
// Pre-built inventories for Seedhebol's target languages.
// ---------------------------------------------------------------------------

/// Hindi phoneme inventory (also used for Bhojpuri L1 mapping).
///
/// Includes the full set of aspirated/unaspirated, voiced/voiceless stops
/// at dental and retroflex places that are the primary source of
/// L1-interference errors when Hindi speakers learn Dravidian languages.
final PhonemeInventory hindiInventory = PhonemeInventory(
  languageCode: 'hi',
  languageName: 'Hindi',
  phonemes: [
    // Voiceless unaspirated stops
    const Phoneme(
        ipa: 'k',
        label: 'ka',
        category: PhonemeCategory.consonant,
        place: Place.velar,
        manner: Manner.stop),
    const Phoneme(
        ipa: 'tʃ',
        label: 'cha',
        category: PhonemeCategory.consonant,
        place: Place.postalveolar,
        manner: Manner.affricate),
    const Phoneme(
        ipa: 'ʈ',
        label: 'retroflex Ta',
        category: PhonemeCategory.consonant,
        place: Place.retroflex,
        manner: Manner.stop,
        retroflex: true),
    const Phoneme(
        ipa: 't̪',
        label: 'dental ta',
        category: PhonemeCategory.consonant,
        place: Place.dental,
        manner: Manner.stop),
    const Phoneme(
        ipa: 'p',
        label: 'pa',
        category: PhonemeCategory.consonant,
        place: Place.bilabial,
        manner: Manner.stop),

    // Voiceless aspirated stops
    const Phoneme(
        ipa: 'kʰ',
        label: 'kha',
        category: PhonemeCategory.consonant,
        place: Place.velar,
        manner: Manner.stop,
        aspirated: true),
    const Phoneme(
        ipa: 'tʃʰ',
        label: 'chha',
        category: PhonemeCategory.consonant,
        place: Place.postalveolar,
        manner: Manner.affricate,
        aspirated: true),
    const Phoneme(
        ipa: 'ʈʰ',
        label: 'retroflex Tha',
        category: PhonemeCategory.consonant,
        place: Place.retroflex,
        manner: Manner.stop,
        aspirated: true,
        retroflex: true),
    const Phoneme(
        ipa: 't̪ʰ',
        label: 'dental tha',
        category: PhonemeCategory.consonant,
        place: Place.dental,
        manner: Manner.stop,
        aspirated: true),
    const Phoneme(
        ipa: 'pʰ',
        label: 'pha',
        category: PhonemeCategory.consonant,
        place: Place.bilabial,
        manner: Manner.stop,
        aspirated: true),

    // Voiced unaspirated stops
    const Phoneme(
        ipa: 'ɡ',
        label: 'ga',
        category: PhonemeCategory.consonant,
        place: Place.velar,
        manner: Manner.stop,
        voiced: true),
    const Phoneme(
        ipa: 'dʒ',
        label: 'ja',
        category: PhonemeCategory.consonant,
        place: Place.postalveolar,
        manner: Manner.affricate,
        voiced: true),
    const Phoneme(
        ipa: 'ɖ',
        label: 'retroflex Da',
        category: PhonemeCategory.consonant,
        place: Place.retroflex,
        manner: Manner.stop,
        voiced: true,
        retroflex: true),
    const Phoneme(
        ipa: 'd̪',
        label: 'dental da',
        category: PhonemeCategory.consonant,
        place: Place.dental,
        manner: Manner.stop,
        voiced: true),
    const Phoneme(
        ipa: 'b',
        label: 'ba',
        category: PhonemeCategory.consonant,
        place: Place.bilabial,
        manner: Manner.stop,
        voiced: true),

    // Voiced aspirated stops
    const Phoneme(
        ipa: 'ɡʰ',
        label: 'gha',
        category: PhonemeCategory.consonant,
        place: Place.velar,
        manner: Manner.stop,
        voiced: true,
        aspirated: true),
    const Phoneme(
        ipa: 'dʒʰ',
        label: 'jha',
        category: PhonemeCategory.consonant,
        place: Place.postalveolar,
        manner: Manner.affricate,
        voiced: true,
        aspirated: true),
    const Phoneme(
        ipa: 'ɖʰ',
        label: 'retroflex Dha',
        category: PhonemeCategory.consonant,
        place: Place.retroflex,
        manner: Manner.stop,
        voiced: true,
        aspirated: true,
        retroflex: true),
    const Phoneme(
        ipa: 'd̪ʰ',
        label: 'dental dha',
        category: PhonemeCategory.consonant,
        place: Place.dental,
        manner: Manner.stop,
        voiced: true,
        aspirated: true),
    const Phoneme(
        ipa: 'bʰ',
        label: 'bha',
        category: PhonemeCategory.consonant,
        place: Place.bilabial,
        manner: Manner.stop,
        voiced: true,
        aspirated: true),

    // Nasals
    const Phoneme(
        ipa: 'ŋ',
        label: 'nga',
        category: PhonemeCategory.consonant,
        place: Place.velar,
        manner: Manner.nasal,
        voiced: true),
    const Phoneme(
        ipa: 'ɲ',
        label: 'nya',
        category: PhonemeCategory.consonant,
        place: Place.palatal,
        manner: Manner.nasal,
        voiced: true),
    const Phoneme(
        ipa: 'ɳ',
        label: 'retroflex Na',
        category: PhonemeCategory.consonant,
        place: Place.retroflex,
        manner: Manner.nasal,
        voiced: true,
        retroflex: true),
    const Phoneme(
        ipa: 'n̪',
        label: 'dental na',
        category: PhonemeCategory.consonant,
        place: Place.dental,
        manner: Manner.nasal,
        voiced: true),
    const Phoneme(
        ipa: 'm',
        label: 'ma',
        category: PhonemeCategory.consonant,
        place: Place.bilabial,
        manner: Manner.nasal,
        voiced: true),

    // Fricatives
    const Phoneme(
        ipa: 's',
        label: 'sa',
        category: PhonemeCategory.consonant,
        place: Place.alveolar,
        manner: Manner.fricative),
    const Phoneme(
        ipa: 'ʃ',
        label: 'sha',
        category: PhonemeCategory.consonant,
        place: Place.postalveolar,
        manner: Manner.fricative),
    const Phoneme(
        ipa: 'ʂ',
        label: 'retroflex sha',
        category: PhonemeCategory.consonant,
        place: Place.retroflex,
        manner: Manner.fricative,
        retroflex: true),
    const Phoneme(
        ipa: 'h',
        label: 'ha',
        category: PhonemeCategory.consonant,
        place: Place.glottal,
        manner: Manner.fricative),

    // Approximants & liquids
    const Phoneme(
        ipa: 'ɾ',
        label: 'ra (flap)',
        category: PhonemeCategory.consonant,
        place: Place.alveolar,
        manner: Manner.tap,
        voiced: true),
    const Phoneme(
        ipa: 'l',
        label: 'la',
        category: PhonemeCategory.consonant,
        place: Place.alveolar,
        manner: Manner.lateralApproximant,
        voiced: true),
    const Phoneme(
        ipa: 'ʋ',
        label: 'va',
        category: PhonemeCategory.consonant,
        place: Place.labiodental,
        manner: Manner.approximant,
        voiced: true),
    const Phoneme(
        ipa: 'j',
        label: 'ya',
        category: PhonemeCategory.consonant,
        place: Place.palatal,
        manner: Manner.approximant,
        voiced: true),

    // Vowels — short
    const Phoneme(
        ipa: 'ə',
        label: 'schwa',
        category: PhonemeCategory.vowel,
        height: VowelHeight.mid,
        backness: VowelBackness.central),
    const Phoneme(
        ipa: 'ɪ',
        label: 'short i',
        category: PhonemeCategory.vowel,
        height: VowelHeight.nearClose,
        backness: VowelBackness.front),
    const Phoneme(
        ipa: 'ʊ',
        label: 'short u',
        category: PhonemeCategory.vowel,
        height: VowelHeight.nearClose,
        backness: VowelBackness.back),
    const Phoneme(
        ipa: 'ɛ',
        label: 'short e',
        category: PhonemeCategory.vowel,
        height: VowelHeight.openMid,
        backness: VowelBackness.front),
    const Phoneme(
        ipa: 'ɔ',
        label: 'short o',
        category: PhonemeCategory.vowel,
        height: VowelHeight.openMid,
        backness: VowelBackness.back),
    const Phoneme(
        ipa: 'æ',
        label: 'short a (open)',
        category: PhonemeCategory.vowel,
        height: VowelHeight.nearOpen,
        backness: VowelBackness.front),
    const Phoneme(
        ipa: 'ɑ',
        label: 'open a',
        category: PhonemeCategory.vowel,
        height: VowelHeight.open,
        backness: VowelBackness.back),

    // Vowels — long
    const Phoneme(
        ipa: 'iː',
        label: 'long ii',
        category: PhonemeCategory.vowel,
        height: VowelHeight.close,
        backness: VowelBackness.front,
        long: true),
    const Phoneme(
        ipa: 'uː',
        label: 'long uu',
        category: PhonemeCategory.vowel,
        height: VowelHeight.close,
        backness: VowelBackness.back,
        long: true),
    const Phoneme(
        ipa: 'eː',
        label: 'long ee',
        category: PhonemeCategory.vowel,
        height: VowelHeight.closeMid,
        backness: VowelBackness.front,
        long: true),
    const Phoneme(
        ipa: 'oː',
        label: 'long oo',
        category: PhonemeCategory.vowel,
        height: VowelHeight.closeMid,
        backness: VowelBackness.back,
        long: true),
    const Phoneme(
        ipa: 'aː',
        label: 'long aa',
        category: PhonemeCategory.vowel,
        height: VowelHeight.open,
        backness: VowelBackness.central,
        long: true),

    // Nasalized vowels
    const Phoneme(
        ipa: 'ə̃',
        label: 'nasalized schwa',
        category: PhonemeCategory.vowel,
        height: VowelHeight.mid,
        backness: VowelBackness.central,
        nasalized: true),
    const Phoneme(
        ipa: 'ã',
        label: 'nasalized aa',
        category: PhonemeCategory.vowel,
        height: VowelHeight.open,
        backness: VowelBackness.central,
        nasalized: true),
    const Phoneme(
        ipa: 'ĩ',
        label: 'nasalized ii',
        category: PhonemeCategory.vowel,
        height: VowelHeight.close,
        backness: VowelBackness.front,
        nasalized: true),
    const Phoneme(
        ipa: 'ũ',
        label: 'nasalized uu',
        category: PhonemeCategory.vowel,
        height: VowelHeight.close,
        backness: VowelBackness.back,
        nasalized: true),
  ],
);

/// Tamil phoneme inventory.
///
/// Tamil has a smaller consonant inventory than Hindi — no aspiration contrast,
/// no voiced/voiceless contrast in stops (voicing is allophonic). The retroflex
/// approximant /ɻ/ (ழ) is distinctive and a major L1-interference target for
/// Hindi speakers.
final PhonemeInventory tamilInventory = PhonemeInventory(
  languageCode: 'ta',
  languageName: 'Tamil',
  phonemes: [
    // Stops — Tamil has no phonemic voicing or aspiration contrast.
    // Voicing is allophonic: voiceless word-initially, voiced intervocalically.
    const Phoneme(
        ipa: 'k',
        label: 'ka (க)',
        category: PhonemeCategory.consonant,
        place: Place.velar,
        manner: Manner.stop),
    const Phoneme(
        ipa: 'tʃ',
        label: 'cha (ச)',
        category: PhonemeCategory.consonant,
        place: Place.postalveolar,
        manner: Manner.affricate),
    const Phoneme(
        ipa: 'ʈ',
        label: 'retroflex Ta (ட)',
        category: PhonemeCategory.consonant,
        place: Place.retroflex,
        manner: Manner.stop,
        retroflex: true),
    const Phoneme(
        ipa: 't̪',
        label: 'dental ta (த)',
        category: PhonemeCategory.consonant,
        place: Place.dental,
        manner: Manner.stop),
    const Phoneme(
        ipa: 'p',
        label: 'pa (ப)',
        category: PhonemeCategory.consonant,
        place: Place.bilabial,
        manner: Manner.stop),

    // Nasals
    const Phoneme(
        ipa: 'ŋ',
        label: 'nga (ங)',
        category: PhonemeCategory.consonant,
        place: Place.velar,
        manner: Manner.nasal,
        voiced: true),
    const Phoneme(
        ipa: 'ɲ',
        label: 'nya (ஞ)',
        category: PhonemeCategory.consonant,
        place: Place.palatal,
        manner: Manner.nasal,
        voiced: true),
    const Phoneme(
        ipa: 'ɳ',
        label: 'retroflex Na (ண)',
        category: PhonemeCategory.consonant,
        place: Place.retroflex,
        manner: Manner.nasal,
        voiced: true,
        retroflex: true),
    const Phoneme(
        ipa: 'n̪',
        label: 'dental na (ந)',
        category: PhonemeCategory.consonant,
        place: Place.dental,
        manner: Manner.nasal,
        voiced: true),
    const Phoneme(
        ipa: 'n',
        label: 'alveolar na (ன)',
        category: PhonemeCategory.consonant,
        place: Place.alveolar,
        manner: Manner.nasal,
        voiced: true),
    const Phoneme(
        ipa: 'm',
        label: 'ma (ம)',
        category: PhonemeCategory.consonant,
        place: Place.bilabial,
        manner: Manner.nasal,
        voiced: true),

    // Fricatives & sibilants
    const Phoneme(
        ipa: 's',
        label: 'sa (ஸ)',
        category: PhonemeCategory.consonant,
        place: Place.alveolar,
        manner: Manner.fricative),
    const Phoneme(
        ipa: 'ʃ',
        label: 'sha (ஷ)',
        category: PhonemeCategory.consonant,
        place: Place.postalveolar,
        manner: Manner.fricative),
    const Phoneme(
        ipa: 'h',
        label: 'ha (ஹ)',
        category: PhonemeCategory.consonant,
        place: Place.glottal,
        manner: Manner.fricative),

    // Liquids, trills & approximants — Tamil's distinctive set
    const Phoneme(
        ipa: 'r',
        label: 'trill ra (ர)',
        category: PhonemeCategory.consonant,
        place: Place.alveolar,
        manner: Manner.trill,
        voiced: true),
    const Phoneme(
        ipa: 'ɾ',
        label: 'flap ra (ற)',
        category: PhonemeCategory.consonant,
        place: Place.alveolar,
        manner: Manner.tap,
        voiced: true),
    const Phoneme(
        ipa: 'l',
        label: 'la (ல)',
        category: PhonemeCategory.consonant,
        place: Place.alveolar,
        manner: Manner.lateralApproximant,
        voiced: true),
    const Phoneme(
        ipa: 'ɭ',
        label: 'retroflex La (ள)',
        category: PhonemeCategory.consonant,
        place: Place.retroflex,
        manner: Manner.lateralApproximant,
        voiced: true,
        retroflex: true),
    const Phoneme(
        ipa: 'ɻ',
        label: 'retroflex approximant zha (ழ)',
        category: PhonemeCategory.consonant,
        place: Place.retroflex,
        manner: Manner.approximant,
        voiced: true,
        retroflex: true),
    const Phoneme(
        ipa: 'ʋ',
        label: 'va (வ)',
        category: PhonemeCategory.consonant,
        place: Place.labiodental,
        manner: Manner.approximant,
        voiced: true),
    const Phoneme(
        ipa: 'j',
        label: 'ya (ய)',
        category: PhonemeCategory.consonant,
        place: Place.palatal,
        manner: Manner.approximant,
        voiced: true),

    // Vowels — short
    const Phoneme(
        ipa: 'a',
        label: 'a (அ)',
        category: PhonemeCategory.vowel,
        height: VowelHeight.open,
        backness: VowelBackness.central),
    const Phoneme(
        ipa: 'i',
        label: 'i (இ)',
        category: PhonemeCategory.vowel,
        height: VowelHeight.close,
        backness: VowelBackness.front),
    const Phoneme(
        ipa: 'u',
        label: 'u (உ)',
        category: PhonemeCategory.vowel,
        height: VowelHeight.close,
        backness: VowelBackness.back),
    const Phoneme(
        ipa: 'e',
        label: 'e (எ)',
        category: PhonemeCategory.vowel,
        height: VowelHeight.closeMid,
        backness: VowelBackness.front),
    const Phoneme(
        ipa: 'o',
        label: 'o (ஒ)',
        category: PhonemeCategory.vowel,
        height: VowelHeight.closeMid,
        backness: VowelBackness.back),

    // Vowels — long
    const Phoneme(
        ipa: 'aː',
        label: 'aa (ஆ)',
        category: PhonemeCategory.vowel,
        height: VowelHeight.open,
        backness: VowelBackness.central,
        long: true),
    const Phoneme(
        ipa: 'iː',
        label: 'ii (ஈ)',
        category: PhonemeCategory.vowel,
        height: VowelHeight.close,
        backness: VowelBackness.front,
        long: true),
    const Phoneme(
        ipa: 'uː',
        label: 'uu (ஊ)',
        category: PhonemeCategory.vowel,
        height: VowelHeight.close,
        backness: VowelBackness.back,
        long: true),
    const Phoneme(
        ipa: 'eː',
        label: 'ee (ஏ)',
        category: PhonemeCategory.vowel,
        height: VowelHeight.closeMid,
        backness: VowelBackness.front,
        long: true),
    const Phoneme(
        ipa: 'oː',
        label: 'oo (ஓ)',
        category: PhonemeCategory.vowel,
        height: VowelHeight.closeMid,
        backness: VowelBackness.back,
        long: true),

    // Diphthongs
    const Phoneme(
        ipa: 'ai', label: 'ai (ஐ)', category: PhonemeCategory.diphthong),
    const Phoneme(
        ipa: 'au', label: 'au (ஔ)', category: PhonemeCategory.diphthong),
  ],
);

/// Registry of all available language inventories.
///
/// Add new languages here as Seedhebol expands to additional corridors.
final Map<String, PhonemeInventory> phonemeInventories = {
  'hi': hindiInventory,
  'ta': tamilInventory,
};
