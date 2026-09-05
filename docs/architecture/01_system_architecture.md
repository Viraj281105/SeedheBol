# 01 — System Architecture

## Architectural Invariants

1. **Zero-Cloud Dependency**: Every ML inference step executes entirely on-device.
2. **Offline-first privacy**: No audio, text, or image leaves the phone at any point.
3. **Graceful degradation**: Gemma is optional; the app functions fully without it.

## Hybrid On-Device AI Stack

```
+──────────────────────────── FLUTTER PRESENTATION SHELL ────────────────────────────+
│  Practice Screen   │  Camera Lesson Screen  │  Roleplay Screen  │  Tools Screen     │
│  (ASR + TTS)       │  (OCR → Gemma → TTS)  │  (ASR + Gemma + TTS)  │             │
+───────────────────────────────────────────────────────────────────────────────────+
                                        │
                    Platform Channels: 'boli/asr'  'boli/engine_methods'
                                        │
+──────────────────────────── NATIVE ANDROID CORE (KOTLIN) ─────────────────────────+
│                                                                                     │
│  ┌─────────────────────── BoliAiLayer (AI abstraction) ─────────────────────────┐ │
│  │                                                                                │ │
│  │  ┌──────────────────────────────────────────────┐                            │ │
│  │  │  GemmaEngine (MediaPipe LLM Inference API)   │  Gemma 3n E2B INT4 (~1.5GB)│ │
│  │  │  • contextual translation (OCR → L1)          │  adb push to external dir  │ │
│  │  │  • dynamic micro-lesson generation            │  isAvailable=false if absent│ │
│  │  │  • vocabulary extraction from OCR text        │                            │ │
│  │  │  • roleplay next-turn generation              │                            │ │
│  │  │  • phrase explanations                        │                            │ │
│  │  └──────────────────────────────────────────────┘                            │ │
│  │                                                                                │ │
│  │  ┌──────────────────────────────────────────────┐                            │ │
│  │  │  DeterministicFallback                        │  Always available, instant │ │
│  │  │  (used when Gemma unavailable or fails)       │  Zero external dependencies│ │
│  │  └──────────────────────────────────────────────┘                            │ │
│  └────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                     │
│  SPECIALISED SYSTEMS (not replaced by Gemma — each owns its domain)                │
│  ┌───────────────────────────────────────────────────────────────────────────────┐ │
│  │  OnnxAsr ─────────── IndicConformer CTC ──── model.arm64.onnx (~187MB)       │ │
│  │                       nemo80.onnx (log-mel)  MatMul-only int8  RTF ~0.065    │ │
│  │                                                                                │ │
│  │  FastPitchTts ──────── FastPitch + HiFi-GAN ─ fastpitch.onnx + hifigan.onnx  │ │
│  │                         22.05kHz, character-based, SHA-256 PCM cache          │ │
│  │                                                                                │ │
│  │  MlKitOcr ─────────── ML Kit Text Recognition ─ on-device, no network        │ │
│  │                         Devanagari (Hindi+Marathi) + Latin fallback            │ │
│  └───────────────────────────────────────────────────────────────────────────────┘ │
+─────────────────────────────────────────────────────────────────────────────────────+
```

## Primary Flow: Camera → OCR → Gemma → TTS

```
User taps camera card
       │
       ▼
CameraLessonScreen (Flutter)
  CameraX capture → JPEG bytes
       │
       ▼ 'extractTextFromImage'
MlKitOcr.recognizeBytes()
  Devanagari → Latin fallback
       │
       ▼ 'generateLessonFromOcr'
BoliAiLayer.generateLessonFromOcr()
  ├─ GemmaEngine.generate(buildOcrLessonPrompt(...))
  │    → TOPIC / EXPLANATION / WORD / PRACTICE
  └─ (DeterministicFallback if Gemma unavailable)
       │
       ▼
MicroLessonCard shown in Flutter
  + 'speak' → FastPitchTts.speak(practicePrompt)
```

## Component Decoupling

- **BoliAiLayer**: The ONLY class that knows whether Gemma is active.
  All Kotlin business logic calls BoliAiLayer; it never imports GemmaEngine directly.
- **GemmaContext**: Typed struct passed to every Gemma call.
  Contains L1/L2 pair, occupation, level, OCR text, ASR transcript.
- **AiSource enum**: `GEMMA | DETERMINISTIC_FALLBACK` — surfaces to Flutter UI as a badge.
- **ASR + TTS + OCR**: Completely isolated from Gemma; receive/emit text strings only.
