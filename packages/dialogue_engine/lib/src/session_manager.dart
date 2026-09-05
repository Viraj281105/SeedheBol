// packages/dialogue_engine/lib/src/session_manager.dart
//
// Stateful Dialogue Session Manager.
//
// Tracks user turn progression, history, completed situations, and hesitation latency.

import 'package:boli_shared_models/shared_models.dart';
import 'dialogue_graph.dart';

class DialogueSession {
  final Situation situation;
  final DialogueGraphNavigator _navigator;
  DialogueNode _currentNode;
  final List<DialogueNode> _history;
  int _turnCount;
  bool _isComplete;

  DialogueSession({required this.situation})
      : _navigator = DialogueGraphNavigator(situation: situation),
        _currentNode = situation.entryNode,
        _history = [situation.entryNode],
        _turnCount = 0,
        _isComplete = false;

  DialogueNode get currentNode => _currentNode;
  List<DialogueNode> get history => List.unmodifiable(_history);
  int get turnCount => _turnCount;
  bool get isComplete => _isComplete;

  /// Submits user response and advances state machine.
  TransitionResult processUserTurn(String transcript) {
    if (_isComplete) {
      return TransitionResult(
        status: TransitionStatus.dialogueCompleted,
        currentNode: _currentNode,
      );
    }

    final result = _navigator.advance(
      currentNode: _currentNode,
      userTranscript: transcript,
    );

    if (result.status == TransitionStatus.success ||
        result.status == TransitionStatus.fallbackReprompt) {
      _currentNode = result.currentNode;
      _history.add(_currentNode);
      _turnCount++;
    }

    if (result.status == TransitionStatus.dialogueCompleted ||
        _currentNode.branches.isEmpty) {
      _isComplete = true;
    }

    return result;
  }
}
