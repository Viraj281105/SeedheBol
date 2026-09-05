// lib/bridge/boli_bridge.dart
// Unified Dart API interface wrapping all Platform Channels for the SeedheBol Engine.

import 'dart:async';
import 'package:flutter/services.dart';
import 'models/boli_events.dart';
export 'models/boli_events.dart';

abstract class IBoliBridge {
  // Streams
  Stream<StreamingTranscriptEvent> get onStreamingTranscript;
  Stream<AmbientMinedLemmaEvent> get onAmbientLemmaDiscovered;
  Stream<ThermalLevel> get onThermalLevelChanged;
  Stream<bool> get onVADStateChanged; // true = speaking, false = silence

  // Lifecycle & Session
  Future<bool> initializeEngine({
    required LanguageCorridor corridor,
    required WorkDomain domain,
  });

  // ASR & Audio Recording
  Future<void> startListening();
  Future<void> stopListening();
  Future<void> cancelListening();

  // Pronunciation Assessment (GOP)
  Future<PronunciationAssessmentReport> scorePronunciation({
    required String targetWord,
    required String canonicalG2P,
  });

  // Situational Roleplay Loop
  Future<DialogueTurnResponse> submitUserUtterance({
    required String situationId,
    required String currentNodeId,
    required String userSpokenText,
  });

  // TTS Synthesis & Playback
  Future<void> speakPrompt({
    required String text,
    String? preRenderedAssetPath,
    double speechRate = 1.0,
  });
  Future<void> stopSpeaking(); // Barge-in trigger

  // Ambient Vocabulary Mining
  Future<void> startAmbientMining();
  Future<void> stopAmbientMining();
  Future<bool> isAmbientMiningActive();

  // Camera OCR Lesson Generation
  Future<List<String>> extractTextFromImage(
    Uint8List imageBytes, {
    Map<String, double>? cropRect,
  });

  // Gemma-powered AI methods (with deterministic fallback)
  Future<Map<String, dynamic>> generateLessonFromOcr(String ocrText, {String? topicHint});
  Future<Map<String, dynamic>> translateText(String text);
  Future<Map<String, dynamic>> evaluateSpokenIntent({
    required String targetPhrase,
    required String prompt,
    required String spokenText,
  });
  Future<bool> isGemmaAvailable();

  // Telemetry & Hardware Info
  Future<Map<String, dynamic>> getHardwareTelemetry();
  Future<Map<String, dynamic>> exportOfficeKitData();

  // Learner Memory & Personalization API
  Future<bool> recordWordAttempt({required String word, required bool isCorrect});
  Future<bool> recordPronunciationWeakness({required String word, required double score, String? phoneme});
  Future<bool> recordCompletedScenario(String scenarioId);
  Future<bool> addLearnedVocab(String word);
  Future<Map<String, dynamic>> getLearnerProfile();
  Future<bool> updateLearnerProfile({String? l1, String? l2, String? occupation, String? level});

  // Daily Mission API
  Future<Map<String, dynamic>> generateDailyMission();

  // Listen Around Me API
  Future<Map<String, dynamic>> analyzeHeardPhrase(String phrase);
}

class BoliBridge implements IBoliBridge {
  static const String _methodChannelName = 'boli/engine_methods';
  static const String _transcriptEventChannelName = 'boli/transcript_stream';
  static const String _ambientEventChannelName = 'boli/ambient_stream';
  static const String _thermalEventChannelName = 'boli/thermal_stream';
  static const String _vadEventChannelName = 'boli/vad_stream';

  final MethodChannel _methodChannel;
  final EventChannel _transcriptEventChannel;
  final EventChannel _ambientEventChannel;
  final EventChannel _thermalEventChannel;
  final EventChannel _vadEventChannel;

  BoliBridge({
    MethodChannel? methodChannel,
    EventChannel? transcriptEventChannel,
    EventChannel? ambientEventChannel,
    EventChannel? thermalEventChannel,
    EventChannel? vadEventChannel,
  }) : _methodChannel =
           methodChannel ?? const MethodChannel(_methodChannelName),
       _transcriptEventChannel =
           transcriptEventChannel ??
           const EventChannel(_transcriptEventChannelName),
       _ambientEventChannel =
           ambientEventChannel ?? const EventChannel(_ambientEventChannelName),
       _thermalEventChannel =
           thermalEventChannel ?? const EventChannel(_thermalEventChannelName),
       _vadEventChannel =
           vadEventChannel ?? const EventChannel(_vadEventChannelName);

  // Singleton Instance
  static final BoliBridge instance = BoliBridge();

  @override
  Stream<StreamingTranscriptEvent> get onStreamingTranscript {
    return _transcriptEventChannel.receiveBroadcastStream().map(
      (event) => StreamingTranscriptEvent.fromMap(
        Map<String, dynamic>.from(event as Map),
      ),
    );
  }

  @override
  Stream<AmbientMinedLemmaEvent> get onAmbientLemmaDiscovered {
    return _ambientEventChannel.receiveBroadcastStream().map(
      (event) => AmbientMinedLemmaEvent.fromMap(
        Map<String, dynamic>.from(event as Map),
      ),
    );
  }

  @override
  Stream<ThermalLevel> get onThermalLevelChanged {
    return _thermalEventChannel.receiveBroadcastStream().map((event) {
      final level = event as String? ?? 'nominal';
      switch (level) {
        case 'warm':
          return ThermalLevel.warm;
        case 'elevated':
          return ThermalLevel.elevated;
        case 'critical':
          return ThermalLevel.critical;
        case 'nominal':
        default:
          return ThermalLevel.nominal;
      }
    });
  }

  @override
  Stream<bool> get onVADStateChanged {
    return _vadEventChannel.receiveBroadcastStream().map(
      (event) => event as bool? ?? false,
    );
  }

  @override
  Future<bool> initializeEngine({
    required LanguageCorridor corridor,
    required WorkDomain domain,
  }) async {
    final result = await _methodChannel.invokeMethod<bool>('initializeEngine', {
      'corridor': corridor.name,
      'domain': domain.name,
    });
    return result ?? false;
  }

  @override
  Future<void> startListening() async {
    await _methodChannel.invokeMethod<void>('startListening');
  }

  @override
  Future<void> stopListening() async {
    await _methodChannel.invokeMethod<void>('stopListening');
  }

  @override
  Future<void> cancelListening() async {
    await _methodChannel.invokeMethod<void>('cancelListening');
  }

  @override
  Future<PronunciationAssessmentReport> scorePronunciation({
    required String targetWord,
    required String canonicalG2P,
  }) async {
    final result = await _methodChannel.invokeMapMethod<String, dynamic>(
      'scorePronunciation',
      {'target_word': targetWord, 'canonical_g2p': canonicalG2P},
    );
    if (result == null) {
      return PronunciationAssessmentReport(
        targetWord: targetWord,
        targetTransliteration: '',
        overallScore: 0.0,
        phonemes: const [],
      );
    }
    return PronunciationAssessmentReport.fromMap(result);
  }

  @override
  Future<DialogueTurnResponse> submitUserUtterance({
    required String situationId,
    required String currentNodeId,
    required String userSpokenText,
  }) async {
    final result = await _methodChannel
        .invokeMapMethod<String, dynamic>('submitUserUtterance', {
          'situation_id': situationId,
          'current_node_id': currentNodeId,
          'user_spoken_text': userSpokenText,
        });
    if (result == null) {
      throw StateError('Failed to evaluate utterance for node $currentNodeId');
    }
    return DialogueTurnResponse.fromMap(result);
  }

  @override
  Future<void> speakPrompt({
    required String text,
    String? preRenderedAssetPath,
    double speechRate = 1.0,
  }) async {
    await _methodChannel.invokeMethod<void>('speakPrompt', {
      'text': text,
      'pre_rendered_asset_path': ?preRenderedAssetPath,
      'speech_rate': speechRate,
    });
  }

  @override
  Future<void> stopSpeaking() async {
    await _methodChannel.invokeMethod<void>('stopSpeaking');
  }

  @override
  Future<void> startAmbientMining() async {
    await _methodChannel.invokeMethod<void>('startAmbientMining');
  }

  @override
  Future<void> stopAmbientMining() async {
    await _methodChannel.invokeMethod<void>('stopAmbientMining');
  }

  @override
  Future<bool> isAmbientMiningActive() async {
    final result = await _methodChannel.invokeMethod<bool>(
      'isAmbientMiningActive',
    );
    return result ?? false;
  }

  @override
  Future<List<String>> extractTextFromImage(
    Uint8List imageBytes, {
    Map<String, double>? cropRect,
  }) async {
    final result = await _methodChannel.invokeListMethod<String>(
      'extractTextFromImage',
      {
        'image_bytes': imageBytes,
        'crop_rect': ?cropRect,
      },
    );
    return result ?? const [];
  }

  @override
  Future<Map<String, dynamic>> generateLessonFromOcr(
    String ocrText, {
    String? topicHint,
  }) async {
    final result = await _methodChannel.invokeMapMethod<String, dynamic>(
      'generateLessonFromOcr',
      {'ocr_text': ocrText, 'topic_hint': topicHint},
    );
    return result ?? const {};
  }

  @override
  Future<Map<String, dynamic>> translateText(String text) async {
    final result = await _methodChannel.invokeMapMethod<String, dynamic>(
      'translateText',
      {'text': text},
    );
    return result ?? const {};
  }

  @override
  Future<bool> isGemmaAvailable() async {
    final result = await _methodChannel.invokeMethod<bool>('isGemmaAvailable');
    return result ?? false;
  }

  @override
  Future<Map<String, dynamic>> getHardwareTelemetry() async {
    final result = await _methodChannel.invokeMapMethod<String, dynamic>(
      'getHardwareTelemetry',
    );
    return result ?? const {};
  }

  @override
  Future<bool> recordWordAttempt({
    required String word,
    required bool isCorrect,
  }) async {
    final result = await _methodChannel.invokeMethod<bool>('recordWordAttempt', {
      'word': word,
      'is_correct': isCorrect,
    });
    return result ?? false;
  }

  @override
  Future<bool> recordPronunciationWeakness({
    required String word,
    required double score,
    String? phoneme,
  }) async {
    final result = await _methodChannel.invokeMethod<bool>(
      'recordPronunciationWeakness',
      {
        'word': word,
        'score': score,
        'phoneme': phoneme,
      },
    );
    return result ?? false;
  }

  @override
  Future<bool> recordCompletedScenario(String scenarioId) async {
    final result = await _methodChannel.invokeMethod<bool>(
      'recordCompletedScenario',
      {'scenario_id': scenarioId},
    );
    return result ?? false;
  }

  @override
  Future<bool> addLearnedVocab(String word) async {
    final result = await _methodChannel.invokeMethod<bool>(
      'addLearnedVocab',
      {'word': word},
    );
    return result ?? false;
  }

  @override
  Future<Map<String, dynamic>> getLearnerProfile() async {
    final result = await _methodChannel.invokeMapMethod<String, dynamic>(
      'getLearnerProfile',
    );
    return result ?? const {};
  }

  @override
  Future<bool> updateLearnerProfile({
    String? l1,
    String? l2,
    String? occupation,
    String? level,
  }) async {
    final result = await _methodChannel.invokeMethod<bool>(
      'updateLearnerProfile',
      {
        'l1': l1,
        'l2': l2,
        'occupation': occupation,
        'level': level,
      },
    );
    return result ?? false;
  }

  @override
  Future<Map<String, dynamic>> generateDailyMission() async {
    final result = await _methodChannel.invokeMapMethod<String, dynamic>(
      'generateDailyMission',
    );
    return result ?? const {};
  }

  @override
  Future<Map<String, dynamic>> analyzeHeardPhrase(String phrase) async {
    final result = await _methodChannel.invokeMapMethod<String, dynamic>(
      'analyzeHeardPhrase',
      {'phrase': phrase},
    );
    return result ?? const {};
  }

  @override
  Future<Map<String, dynamic>> evaluateSpokenIntent({
    required String targetPhrase,
    required String prompt,
    required String spokenText,
  }) async {
    try {
      final result = await _methodChannel.invokeMapMethod<String, dynamic>(
        'evaluateSpokenIntent',
        {
          'target_phrase': targetPhrase,
          'prompt': prompt,
          'spoken_text': spokenText,
        },
      );
      return result ?? const {};
    } catch (_) {
      return const {};
    }
  }

  @override
  Future<Map<String, dynamic>> exportOfficeKitData() async {
    final result = await _methodChannel.invokeMapMethod<String, dynamic>(
      'exportOfficeKitData',
    );
    return result ?? const {};
  }
}

/// Official SeedheBol Bridge aliases for backwards and forwards compatibility.
typedef SeedheBolBridge = BoliBridge;
typedef ISeedheBolBridge = IBoliBridge;

