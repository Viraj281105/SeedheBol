// packages/shared_models/lib/src/situation_model.dart
//
// Situation-based curriculum AST for Seedhebol's branching dialogue engine.
//
// The atomic content unit is a *Situation* — not a word, not a grammar point.
// "Asking your supervisor to correct an underpaid wage" is a Situation.
// "Money vocabulary" is not.
//
// Situations are organized into Domains (occupations) → Tracks → Units → Situations.
// Each Situation contains a directed acyclic graph of DialogueNodes
// representing branching conversational turns with register variants.

import 'package:meta/meta.dart';

/// Occupational domain for curriculum content.
enum Domain {
  construction,
  healthcare,
  logistics,
  domestic,
  driving,
  hospitality,
  retail,
  security,
  factory,
}

/// Language corridor — source L1 to target L2.
enum Corridor {
  bhojpuriTamil,
  odiaMalayalam,
  hindiKannada,
}

/// Social register variant for politeness/formality calibration.
enum Register {
  peer, // Same-status coworker
  employer, // Superior / supervisor / employer
  elder, // Respectful elder address
}

/// A single node in the branching dialogue graph.
///
/// Each node represents one conversational turn — either a persona utterance
/// (NPC turn) or a user prompt (learner's expected response).
@immutable
class DialogueNode {
  /// Unique identifier within the situation graph.
  final String nodeId;

  /// The spoken text in the target language (L2).
  final String l2Text;

  /// Romanized transliteration of the L2 text.
  final String transliteration;

  /// Translation in the learner's native language (L1).
  final String l1Translation;

  /// Path to pre-rendered audio asset (relative to assets/audio/).
  final String? audioAssetPath;

  /// Whether this node is spoken by the AI persona (true) or expected
  /// from the learner (false).
  final bool isPersonaTurn;

  /// Register variant for this utterance.
  final Register register;

  /// Outgoing branches — possible continuations from this node.
  final List<DialogueBranch> branches;

  /// Phoneme focus set — which phoneme contrasts this node stresses
  /// for pronunciation drilling.
  final List<String> phonemeFocusIpa;

  /// Fallback reprompt node ID if the user's response is unrecognized.
  final String? fallbackNodeId;

  const DialogueNode({
    required this.nodeId,
    required this.l2Text,
    required this.transliteration,
    required this.l1Translation,
    this.audioAssetPath,
    required this.isPersonaTurn,
    this.register = Register.peer,
    this.branches = const [],
    this.phonemeFocusIpa = const [],
    this.fallbackNodeId,
  });

  /// Deserialize from bundled JSON curriculum asset.
  factory DialogueNode.fromJson(Map<String, dynamic> json) {
    return DialogueNode(
      nodeId: json['node_id'] as String,
      l2Text: json['l2_text'] as String,
      transliteration: json['transliteration'] as String? ?? '',
      l1Translation: json['l1_translation'] as String? ?? '',
      audioAssetPath: json['audio_asset_path'] as String?,
      isPersonaTurn: json['is_persona_turn'] as bool? ?? true,
      register: Register.values.firstWhere(
        (r) => r.name == (json['register'] as String? ?? 'peer'),
        orElse: () => Register.peer,
      ),
      branches: (json['branches'] as List<dynamic>?)
              ?.map((b) => DialogueBranch.fromJson(b as Map<String, dynamic>))
              .toList() ??
          const [],
      phonemeFocusIpa: (json['phoneme_focus_ipa'] as List<dynamic>?)
              ?.map((p) => p as String)
              .toList() ??
          const [],
      fallbackNodeId: json['fallback_node_id'] as String?,
    );
  }

  /// Serialize to JSON for curriculum compilation output.
  Map<String, dynamic> toJson() => {
        'node_id': nodeId,
        'l2_text': l2Text,
        'transliteration': transliteration,
        'l1_translation': l1Translation,
        if (audioAssetPath != null) 'audio_asset_path': audioAssetPath,
        'is_persona_turn': isPersonaTurn,
        'register': register.name,
        'branches': branches.map((b) => b.toJson()).toList(),
        'phoneme_focus_ipa': phonemeFocusIpa,
        if (fallbackNodeId != null) 'fallback_node_id': fallbackNodeId,
      };
}

/// A branch connecting one dialogue node to the next, gated by an intent.
@immutable
class DialogueBranch {
  /// Human-readable intent label (e.g., 'correct_mix_ratio', 'ask_clarification').
  final String intentLabel;

  /// Keywords that trigger this branch when matched against ASR transcript.
  final List<String> triggerKeywords;

  /// Target node ID to transition to if this branch is selected.
  final String targetNodeId;

  /// Minimum fuzzy match confidence (0.0–1.0) for triggering this branch.
  final double confidenceThreshold;

  const DialogueBranch({
    required this.intentLabel,
    required this.triggerKeywords,
    required this.targetNodeId,
    this.confidenceThreshold = 0.6,
  });

  factory DialogueBranch.fromJson(Map<String, dynamic> json) {
    return DialogueBranch(
      intentLabel: json['intent_label'] as String,
      triggerKeywords: (json['trigger_keywords'] as List<dynamic>?)
              ?.map((k) => k as String)
              .toList() ??
          const [],
      targetNodeId: json['target_node_id'] as String,
      confidenceThreshold:
          (json['confidence_threshold'] as num?)?.toDouble() ?? 0.6,
    );
  }

  Map<String, dynamic> toJson() => {
        'intent_label': intentLabel,
        'trigger_keywords': triggerKeywords,
        'target_node_id': targetNodeId,
        'confidence_threshold': confidenceThreshold,
      };
}

/// A complete Situation — the atomic content unit for Seedhebol's curriculum.
///
/// Contains a branching dialogue graph, register variants, phoneme focus sets,
/// and metadata for curriculum organization.
@immutable
class Situation {
  /// Unique situation identifier (e.g., 'tamil_construction_wage_dispute_01').
  final String situationId;

  /// Human-readable title in L1 for the learner.
  final String titleL1;

  /// Human-readable title in L2 for reference.
  final String titleL2;

  /// Brief description of the conversational scenario.
  final String description;

  /// Occupational domain this situation belongs to.
  final Domain domain;

  /// Language corridor (L1 → L2).
  final Corridor corridor;

  /// AI persona name and role (e.g., 'Site Supervisor Murugan').
  final String personaName;

  /// All dialogue nodes keyed by their node IDs for O(1) lookup.
  final Map<String, DialogueNode> nodes;

  /// The entry-point node ID to start the dialogue.
  final String entryNodeId;

  /// Difficulty tier (1 = beginner survival phrases, 5 = complex negotiation).
  final int difficultyTier;

  /// Tags for search and organization.
  final List<String> tags;

  const Situation({
    required this.situationId,
    required this.titleL1,
    required this.titleL2,
    required this.description,
    required this.domain,
    required this.corridor,
    required this.personaName,
    required this.nodes,
    required this.entryNodeId,
    this.difficultyTier = 1,
    this.tags = const [],
  });

  /// Get the entry node to begin a dialogue session.
  DialogueNode get entryNode {
    final node = nodes[entryNodeId];
    if (node == null) {
      throw StateError(
        'Entry node "$entryNodeId" not found in situation "$situationId"',
      );
    }
    return node;
  }

  /// Resolve a node by its ID. Throws if missing.
  DialogueNode resolveNode(String nodeId) {
    final node = nodes[nodeId];
    if (node == null) {
      throw StateError(
        'Node "$nodeId" not found in situation "$situationId"',
      );
    }
    return node;
  }

  factory Situation.fromJson(Map<String, dynamic> json) {
    final nodesJson = json['nodes'] as Map<String, dynamic>? ?? {};
    final parsedNodes = nodesJson.map(
      (key, value) => MapEntry(
        key,
        DialogueNode.fromJson(value as Map<String, dynamic>),
      ),
    );

    return Situation(
      situationId: json['situation_id'] as String,
      titleL1: json['title_l1'] as String? ?? '',
      titleL2: json['title_l2'] as String? ?? '',
      description: json['description'] as String? ?? '',
      domain: Domain.values.firstWhere(
        (d) => d.name == (json['domain'] as String? ?? 'construction'),
        orElse: () => Domain.construction,
      ),
      corridor: Corridor.values.firstWhere(
        (c) => c.name == (json['corridor'] as String? ?? 'bhojpuriTamil'),
        orElse: () => Corridor.bhojpuriTamil,
      ),
      personaName: json['persona_name'] as String? ?? 'Supervisor',
      nodes: parsedNodes,
      entryNodeId: json['entry_node_id'] as String,
      difficultyTier: json['difficulty_tier'] as int? ?? 1,
      tags:
          (json['tags'] as List<dynamic>?)?.map((t) => t as String).toList() ??
              const [],
    );
  }

  Map<String, dynamic> toJson() => {
        'situation_id': situationId,
        'title_l1': titleL1,
        'title_l2': titleL2,
        'description': description,
        'domain': domain.name,
        'corridor': corridor.name,
        'persona_name': personaName,
        'nodes': nodes.map((key, value) => MapEntry(key, value.toJson())),
        'entry_node_id': entryNodeId,
        'difficulty_tier': difficultyTier,
        'tags': tags,
      };
}
