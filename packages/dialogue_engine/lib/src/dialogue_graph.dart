// packages/dialogue_engine/lib/src/dialogue_graph.dart
//
// Dialogue Graph State Traversal Engine.
//
// Manages node lookup, branch transitions, and fallback reprompt triggers.

import 'package:meta/meta.dart';
import 'package:boli_shared_models/shared_models.dart';
import 'intent_matcher.dart';

enum TransitionStatus {
  success,
  fallbackReprompt,
  dialogueCompleted,
}

@immutable
class TransitionResult {
  final TransitionStatus status;
  final DialogueNode currentNode;
  final MatchResult? match;
  final String? message;

  const TransitionResult({
    required this.status,
    required this.currentNode,
    this.match,
    this.message,
  });
}

class DialogueGraphNavigator {
  final Situation situation;
  final IntentMatcher matcher;

  DialogueGraphNavigator({
    required this.situation,
    this.matcher = const IntentMatcher(),
  });

  /// Initiates navigation at the entry node.
  DialogueNode getInitialNode() => situation.entryNode;

  /// Progresses the dialogue state based on user's spoken transcript.
  TransitionResult advance({
    required DialogueNode currentNode,
    required String userTranscript,
  }) {
    if (currentNode.branches.isEmpty) {
      return TransitionResult(
        status: TransitionStatus.dialogueCompleted,
        currentNode: currentNode,
        message: 'Situation finished.',
      );
    }

    final match = matcher.findBestMatch(userTranscript, currentNode.branches);

    if (match != null) {
      final targetNode = situation.resolveNode(match.branch.targetNodeId);
      return TransitionResult(
        status: TransitionStatus.success,
        currentNode: targetNode,
        match: match,
      );
    }

    // Unrecognized utterance -> Trigger fallback reprompt if defined
    if (currentNode.fallbackNodeId != null) {
      final fallbackNode = situation.resolveNode(currentNode.fallbackNodeId!);
      return TransitionResult(
        status: TransitionStatus.fallbackReprompt,
        currentNode: fallbackNode,
        message: 'Could not match intent. Reprompting.',
      );
    }

    return TransitionResult(
      status: TransitionStatus.fallbackReprompt,
      currentNode: currentNode,
      message: 'Utterance not understood. Please retry.',
    );
  }
}
