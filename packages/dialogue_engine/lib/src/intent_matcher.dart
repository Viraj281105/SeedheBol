// packages/dialogue_engine/lib/src/intent_matcher.dart
//
// Fuzzy Intent Matcher for Spoken Utterances.
//
// Matches incoming ASR transcripts against dialogue branch trigger keywords
// using token containment and Levenshtein edit distance tolerance.

import 'dart:math' as math;
import 'package:boli_shared_models/shared_models.dart';

class MatchResult {
  final DialogueBranch branch;
  final double confidence;
  final String matchedKeyword;

  const MatchResult({
    required this.branch,
    required this.confidence,
    required this.matchedKeyword,
  });
}

class IntentMatcher {
  const IntentMatcher();

  /// Matches user transcript against available outgoing branches from the current node.
  MatchResult? findBestMatch(
    String userTranscript,
    List<DialogueBranch> availableBranches,
  ) {
    if (availableBranches.isEmpty || userTranscript.trim().isEmpty) {
      return null;
    }

    final String normalizedInput = userTranscript.toLowerCase().trim();
    MatchResult? bestMatch;
    double highestScore = -1.0;

    for (final branch in availableBranches) {
      for (final keyword in branch.triggerKeywords) {
        final String normKeyword = keyword.toLowerCase().trim();

        // 1. Direct containment
        if (normalizedInput.contains(normKeyword)) {
          final double score = 1.0;
          if (score > highestScore) {
            highestScore = score;
            bestMatch = MatchResult(
              branch: branch,
              confidence: score,
              matchedKeyword: keyword,
            );
          }
          continue;
        }

        // 2. Fuzzy Levenshtein token matching
        final List<String> inputTokens = normalizedInput.split(RegExp(r'\s+'));
        for (final token in inputTokens) {
          final double similarity = _tokenSimilarity(token, normKeyword);
          if (similarity >= branch.confidenceThreshold &&
              similarity > highestScore) {
            highestScore = similarity;
            bestMatch = MatchResult(
              branch: branch,
              confidence: similarity,
              matchedKeyword: keyword,
            );
          }
        }
      }
    }

    return bestMatch;
  }

  double _tokenSimilarity(String s1, String s2) {
    if (s1 == s2) return 1.0;
    if (s1.isEmpty || s2.isEmpty) return 0.0;

    final int distance = _levenshtein(s1, s2);
    final int maxLen = math.max(s1.length, s2.length);
    return 1.0 - (distance / maxLen);
  }

  int _levenshtein(String s1, String s2) {
    final int m = s1.length;
    final int n = s2.length;
    final List<List<int>> d =
        List.generate(m + 1, (_) => List.filled(n + 1, 0));

    for (int i = 0; i <= m; i++) d[i][0] = i;
    for (int j = 0; j <= n; j++) d[0][j] = j;

    for (int i = 1; i <= m; i++) {
      for (int j = 1; j <= n; j++) {
        final int cost = (s1[i - 1] == s2[j - 1]) ? 0 : 1;
        d[i][j] = math.min(
          math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
          d[i - 1][j - 1] + cost,
        );
      }
    }
    return d[m][n];
  }
}
