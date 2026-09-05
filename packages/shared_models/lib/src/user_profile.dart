// packages/shared_models/lib/src/user_profile.dart
//
// On-device user proficiency profile for Seedhebol.
//
// Stored entirely locally (Isar/Hive). Tracks the learner's L1/L2 pairing,
// occupational domain, literacy level, known vocabulary, phoneme error history,
// and hesitation-based spaced repetition schedule.
//
// No field in this model is ever transmitted off-device.

import 'package:meta/meta.dart';
import 'situation_model.dart';

/// Literacy level determines whether text is rendered in the UI at all.
enum LiteracyLevel {
  /// Cannot read in any language. UI is 100% voice + icons + haptics.
  zeroLiteracy,

  /// Can read haltingly in L1 script (e.g., Devanagari). L2 script unknown.
  semiLiterate,

  /// Can read fluently in L1 and potentially L2. Text is an enhancement.
  literate,
}

/// A single phoneme substitution error recorded during a practice session.
@immutable
class PhonemeError {
  /// The target phoneme the learner was expected to produce (IPA symbol).
  final String targetPhonemeIpa;

  /// The phoneme the learner actually produced (IPA symbol).
  final String producedPhonemeIpa;

  /// The GOP score for this specific error instance.
  final double gopScore;

  /// Timestamp of the error occurrence (milliseconds since epoch).
  final int timestampMs;

  /// The word context in which this error occurred.
  final String wordContext;

  const PhonemeError({
    required this.targetPhonemeIpa,
    required this.producedPhonemeIpa,
    required this.gopScore,
    required this.timestampMs,
    this.wordContext = '',
  });

  factory PhonemeError.fromJson(Map<String, dynamic> json) {
    return PhonemeError(
      targetPhonemeIpa: json['target'] as String,
      producedPhonemeIpa: json['produced'] as String,
      gopScore: (json['gop_score'] as num).toDouble(),
      timestampMs: json['timestamp_ms'] as int,
      wordContext: json['word_context'] as String? ?? '',
    );
  }

  Map<String, dynamic> toJson() => {
        'target': targetPhonemeIpa,
        'produced': producedPhonemeIpa,
        'gop_score': gopScore,
        'timestamp_ms': timestampMs,
        'word_context': wordContext,
      };
}

/// Aggregated phoneme error history for a specific L1→L2 substitution pair.
///
/// Drives confusion-targeted drill generation: if a learner repeatedly
/// substitutes dental /t̪/ for retroflex /ʈ/, the system generates
/// minimal-pair discrimination exercises for that specific contrast.
@immutable
class PhonemeErrorAggregate {
  /// Target phoneme IPA symbol.
  final String targetIpa;

  /// Most commonly substituted phoneme IPA symbol.
  final String mostCommonSubstitutionIpa;

  /// Total number of times this substitution error has occurred.
  final int occurrenceCount;

  /// Average GOP score across all occurrences (lower = worse).
  final double averageGopScore;

  /// Trend: is the learner improving? (positive = improving)
  final double trendSlope;

  /// Most recent occurrence timestamp.
  final int lastOccurrenceMs;

  const PhonemeErrorAggregate({
    required this.targetIpa,
    required this.mostCommonSubstitutionIpa,
    required this.occurrenceCount,
    required this.averageGopScore,
    this.trendSlope = 0.0,
    required this.lastOccurrenceMs,
  });

  factory PhonemeErrorAggregate.fromJson(Map<String, dynamic> json) {
    return PhonemeErrorAggregate(
      targetIpa: json['target_ipa'] as String,
      mostCommonSubstitutionIpa: json['substitution_ipa'] as String,
      occurrenceCount: json['count'] as int,
      averageGopScore: (json['avg_gop'] as num).toDouble(),
      trendSlope: (json['trend'] as num?)?.toDouble() ?? 0.0,
      lastOccurrenceMs: json['last_ms'] as int,
    );
  }

  Map<String, dynamic> toJson() => {
        'target_ipa': targetIpa,
        'substitution_ipa': mostCommonSubstitutionIpa,
        'count': occurrenceCount,
        'avg_gop': averageGopScore,
        'trend': trendSlope,
        'last_ms': lastOccurrenceMs,
      };
}

/// A single vocabulary item in the learner's known-word set.
@immutable
class KnownLemma {
  /// The word in L2 script.
  final String l2Text;

  /// Romanized transliteration.
  final String transliteration;

  /// Translation in L1.
  final String l1Translation;

  /// How the word was acquired.
  final LemmaSource source;

  /// Mastery confidence (0.0 = just encountered, 1.0 = fully mastered).
  final double masteryScore;

  /// Timestamp of first encounter.
  final int firstEncounteredMs;

  /// Number of successful recall attempts.
  final int successfulRecalls;

  const KnownLemma({
    required this.l2Text,
    required this.transliteration,
    required this.l1Translation,
    required this.source,
    this.masteryScore = 0.0,
    required this.firstEncounteredMs,
    this.successfulRecalls = 0,
  });

  factory KnownLemma.fromJson(Map<String, dynamic> json) {
    return KnownLemma(
      l2Text: json['l2_text'] as String,
      transliteration: json['translit'] as String? ?? '',
      l1Translation: json['l1_text'] as String? ?? '',
      source: LemmaSource.values.firstWhere(
        (s) => s.name == (json['source'] as String? ?? 'curriculum'),
        orElse: () => LemmaSource.curriculum,
      ),
      masteryScore: (json['mastery'] as num?)?.toDouble() ?? 0.0,
      firstEncounteredMs: json['first_ms'] as int? ?? 0,
      successfulRecalls: json['recalls'] as int? ?? 0,
    );
  }

  Map<String, dynamic> toJson() => {
        'l2_text': l2Text,
        'translit': transliteration,
        'l1_text': l1Translation,
        'source': source.name,
        'mastery': masteryScore,
        'first_ms': firstEncounteredMs,
        'recalls': successfulRecalls,
      };
}

/// How a vocabulary item was acquired by the learner.
enum LemmaSource {
  /// From structured curriculum lessons.
  curriculum,

  /// Mined from ambient microphone listening.
  ambient,

  /// Extracted from camera OCR.
  cameraOcr,

  /// Manually added or imported.
  manual,
}

/// Hesitation-based spaced repetition entry.
///
/// Unlike traditional SRS (where the user self-reports "easy" / "hard"),
/// Seedhebol measures actual speech hesitation latency and pronunciation
/// confidence to schedule reviews.
@immutable
class SpacedRepetitionEntry {
  /// The vocabulary item or situation being scheduled.
  final String itemId;

  /// Average response hesitation in milliseconds across recent attempts.
  final double averageHesitationMs;

  /// Latest GOP score for this item (if pronunciation-relevant).
  final double? latestGopScore;

  /// Next scheduled review timestamp (milliseconds since epoch).
  final int nextReviewMs;

  /// Current interval multiplier (Leitner box equivalent).
  final double intervalMultiplier;

  /// Number of consecutive successful recalls.
  final int streak;

  const SpacedRepetitionEntry({
    required this.itemId,
    required this.averageHesitationMs,
    this.latestGopScore,
    required this.nextReviewMs,
    this.intervalMultiplier = 1.0,
    this.streak = 0,
  });

  factory SpacedRepetitionEntry.fromJson(Map<String, dynamic> json) {
    return SpacedRepetitionEntry(
      itemId: json['item_id'] as String,
      averageHesitationMs: (json['hesitation_ms'] as num).toDouble(),
      latestGopScore: (json['gop'] as num?)?.toDouble(),
      nextReviewMs: json['next_review_ms'] as int,
      intervalMultiplier: (json['interval'] as num?)?.toDouble() ?? 1.0,
      streak: json['streak'] as int? ?? 0,
    );
  }

  Map<String, dynamic> toJson() => {
        'item_id': itemId,
        'hesitation_ms': averageHesitationMs,
        if (latestGopScore != null) 'gop': latestGopScore,
        'next_review_ms': nextReviewMs,
        'interval': intervalMultiplier,
        'streak': streak,
      };
}

/// Complete on-device user profile.
///
/// All fields persist locally. No field is ever transmitted off-device.
@immutable
class UserProfile {
  /// Unique local profile ID.
  final String profileId;

  /// Native language (L1) ISO code.
  final String l1LanguageCode;

  /// Target language (L2) ISO code.
  final String l2LanguageCode;

  /// Dialect variant if applicable (e.g., 'chennai_tamil', 'madurai_tamil').
  final String? dialectVariant;

  /// Primary occupational domain.
  final Domain primaryDomain;

  /// Literacy level — determines UI mode.
  final LiteracyLevel literacyLevel;

  /// Language corridor.
  final Corridor corridor;

  /// Set of mastered vocabulary items.
  final List<KnownLemma> knownLemmas;

  /// Phoneme error history aggregated by substitution pair.
  final List<PhonemeErrorAggregate> phonemeErrors;

  /// Spaced repetition schedule entries.
  final List<SpacedRepetitionEntry> srsQueue;

  /// Total practice sessions completed.
  final int totalSessions;

  /// Total practice time in seconds.
  final int totalPracticeSeconds;

  /// Current daily streak count.
  final int currentStreak;

  /// Profile creation timestamp.
  final int createdAtMs;

  const UserProfile({
    required this.profileId,
    required this.l1LanguageCode,
    required this.l2LanguageCode,
    this.dialectVariant,
    required this.primaryDomain,
    required this.literacyLevel,
    required this.corridor,
    this.knownLemmas = const [],
    this.phonemeErrors = const [],
    this.srsQueue = const [],
    this.totalSessions = 0,
    this.totalPracticeSeconds = 0,
    this.currentStreak = 0,
    required this.createdAtMs,
  });

  /// Number of unique words the learner has encountered.
  int get vocabularySize => knownLemmas.length;

  /// Number of words with mastery score above 0.8 (considered "ready").
  int get masteredWordCount =>
      knownLemmas.where((l) => l.masteryScore >= 0.8).length;

  /// The learner's weakest phoneme contrasts, sorted by error frequency.
  List<PhonemeErrorAggregate> get weakestPhonemes {
    final sorted = List<PhonemeErrorAggregate>.from(phonemeErrors)
      ..sort((a, b) => b.occurrenceCount.compareTo(a.occurrenceCount));
    return sorted;
  }

  factory UserProfile.fromJson(Map<String, dynamic> json) {
    return UserProfile(
      profileId: json['profile_id'] as String,
      l1LanguageCode: json['l1'] as String,
      l2LanguageCode: json['l2'] as String,
      dialectVariant: json['dialect'] as String?,
      primaryDomain: Domain.values.firstWhere(
        (d) => d.name == (json['domain'] as String? ?? 'construction'),
        orElse: () => Domain.construction,
      ),
      literacyLevel: LiteracyLevel.values.firstWhere(
        (l) => l.name == (json['literacy'] as String? ?? 'zeroLiteracy'),
        orElse: () => LiteracyLevel.zeroLiteracy,
      ),
      corridor: Corridor.values.firstWhere(
        (c) => c.name == (json['corridor'] as String? ?? 'bhojpuriTamil'),
        orElse: () => Corridor.bhojpuriTamil,
      ),
      knownLemmas: (json['known_lemmas'] as List<dynamic>?)
              ?.map((l) => KnownLemma.fromJson(l as Map<String, dynamic>))
              .toList() ??
          const [],
      phonemeErrors: (json['phoneme_errors'] as List<dynamic>?)
              ?.map((e) =>
                  PhonemeErrorAggregate.fromJson(e as Map<String, dynamic>))
              .toList() ??
          const [],
      srsQueue: (json['srs_queue'] as List<dynamic>?)
              ?.map((s) =>
                  SpacedRepetitionEntry.fromJson(s as Map<String, dynamic>))
              .toList() ??
          const [],
      totalSessions: json['total_sessions'] as int? ?? 0,
      totalPracticeSeconds: json['total_practice_s'] as int? ?? 0,
      currentStreak: json['streak'] as int? ?? 0,
      createdAtMs: json['created_ms'] as int? ?? 0,
    );
  }

  Map<String, dynamic> toJson() => {
        'profile_id': profileId,
        'l1': l1LanguageCode,
        'l2': l2LanguageCode,
        if (dialectVariant != null) 'dialect': dialectVariant,
        'domain': primaryDomain.name,
        'literacy': literacyLevel.name,
        'corridor': corridor.name,
        'known_lemmas': knownLemmas.map((l) => l.toJson()).toList(),
        'phoneme_errors': phonemeErrors.map((e) => e.toJson()).toList(),
        'srs_queue': srsQueue.map((s) => s.toJson()).toList(),
        'total_sessions': totalSessions,
        'total_practice_s': totalPracticeSeconds,
        'streak': currentStreak,
        'created_ms': createdAtMs,
      };
}
