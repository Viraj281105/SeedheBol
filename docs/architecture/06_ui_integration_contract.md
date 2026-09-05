# 06 — UI Developer Integration Contract & Platform Bridge

> **Guide for UI Engineers**: How to consume the on-device Seedhebol NPU engine from your Flutter UI views, handle reactive streams, and trigger native capabilities.

---

## 🚀 Quick Integration

### 1. Import the Bridge
```dart
import 'package:seedhebol_mobile/bridge/seedhebol_bridge.dart';
import 'package:seedhebol_mobile/bridge/models/seedhebol_events.dart';
```

### 2. Initialize the Engine on Launch
```dart
final bridge = SeedhebolBridge.instance;

await bridge.initializeEngine(
  corridor: LanguageCorridor.bhojpuriTamil,
  domain: WorkDomain.construction,
);
```

---

## 🎙️ Real-Time ASR & Mic Streams

```dart
// 1. Listen for real-time partial text & transcripts
bridge.onStreamingTranscript.listen((event) {
  print("Partial: ${event.partialText} (Final: ${event.isFinal})");
});

// 2. Start / Stop Recording
await bridge.startListening();
// ... user speaks ...
await bridge.stopListening();
```

---

## 🎯 Pronunciation Assessment (GOP Scoring)

```dart
final report = await bridge.scorePronunciation(
  targetWord: "படம்",
  canonicalG2P: "pa-dam",
);

print("Overall Score: ${report.overallScore}");
for (var p in report.phonemes) {
  if (!p.isCorrect) {
    print("Error on ${p.phoneme}: ${p.articulationGuidance}");
  }
}
```

---

## 🗣️ Situational Roleplay & Barge-In

```dart
// Submit user reply in a conversation turn
final response = await bridge.submitUserUtterance(
  situationId: "construction_wage_01",
  currentNodeId: "supervisor_greeting",
  userSpokenText: "வணக்கம் அண்ணா",
);

// Play persona reply
await bridge.speakPrompt(
  text: response.spokenText,
  preRenderedAssetPath: response.audioAssetPath,
);

// If user starts talking while persona is speaking:
await bridge.stopSpeaking(); // Instant Barge-in
```

---

## 📡 Ambient Vocabulary Mining Streams

```dart
// Listen for newly discovered regional words in the background
bridge.onAmbientLemmaDiscovered.listen((event) {
  print("Discovered: ${event.lemma} (${event.l1Translation})");
});

// Toggle Ambient Mining
await bridge.startAmbientMining();
```

---

## 🌡️ Hardware & Thermal Monitoring

```dart
bridge.onThermalLevelChanged.listen((level) {
  if (level == ThermalLevel.warm) {
    // UI can display battery/efficiency indicator
  }
});
```
