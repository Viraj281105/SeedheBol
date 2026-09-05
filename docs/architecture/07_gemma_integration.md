# 07 — Gemma 3n E2B Integration Guide

## Architecture Overview

SeedheBol now uses a **hybrid on-device AI** architecture:

```
+─────────────────────────────── FLUTTER SHELL ───────────────────────────────+
│  Practice Screen  │  Camera Lesson Screen  │  Roleplay Screen  │  Tools     │
+─────────────────────────────────────────────────────────────────────────────+
                            │ Platform Channels
                            │ 'boli/asr'          'boli/engine_methods'
+─────────────────────────── KOTLIN ANDROID CORE ────────────────────────────+
│                                                                              │
│  BoliAiLayer  ◄──── abstraction layer — all business logic goes here        │
│      │                                                                       │
│      ├── GemmaEngine (MediaPipe LLM) ──── Gemma 3n E2B INT4 (~1.5GB)       │
│      │     • Translation (OCR text → L1)                                   │
│      │     • Micro-lesson generation                                        │
│      │     • Vocabulary extraction                                          │
│      │     • Roleplay next-turn generation                                  │
│      │     • Explanations                                                   │
│      │                                                                       │
│      └── DeterministicFallback ───────── always available, instant          │
│                                                                              │
│  SPECIALISED SYSTEMS (unchanged — not replaced by Gemma)                    │
│      ├── OnnxAsr ──── IndicConformer CTC ──── model.arm64.onnx (~187MB)    │
│      ├── FastPitchTts ─ FastPitch + HiFi-GAN ─ fastpitch.onnx + hifigan.onnx│
│      └── MlKitOcr ──── ML Kit Text Recognition ─── on-device, no network   │
+─────────────────────────────────────────────────────────────────────────────+
```

## Gemma Model Setup

Gemma 3n E2B **cannot be bundled in the APK** — the INT4 quantized model is ~1.5GB.
It must be pushed to the device before use:

```bash
# Export MSYS_NO_PATHCONV=1 in Git Bash to prevent path mangling
adb push gemma-3n-e2b-it-int4.task \
  /sdcard/Android/data/com.boli.boli_proto/files/gemma/gemma-3n-e2b-it-int4.task

# Verify:
adb shell ls -la /sdcard/Android/data/com.boli.boli_proto/files/gemma/
```

> **Same caution as ASR/TTS models** (HANDOFF.md §CRITICAL): reinstalling the APK
> (`adb install -r`) may wipe `/sdcard/Android/data/.../files/`. Always verify
> the model is present before attributing behaviour to a code regression.

### Accepted file locations (GemmaEngine resolveModelFile):
1. `/sdcard/Android/data/com.boli.boli_proto/files/gemma/gemma-3n-e2b-it-int4.task`
2. `/sdcard/Android/data/com.boli.boli_proto/files/gemma-3n-e2b-it-int4.task` (no subfolder)

If neither exists, `GemmaEngine.isAvailable` = false and all calls transparently
use `DeterministicFallback`.

## GemmaContext Schema

Every Gemma call is grounded in structured context. Fields are all optional; only
supply what is known at the call site:

```kotlin
data class GemmaContext(
    val l1: String = "Hindi",              // Mother tongue
    val l2: String = "Marathi",            // Target language
    val occupation: String = "construction worker",
    val userLevel: String = "beginner",    // beginner | intermediate | advanced
    val scenario: String? = null,          // Current situation (roleplay)
    val ocrText: String? = null,           // Raw ML Kit OCR output
    val asrTranscript: String? = null,     // IndicConformer ASR output
    val learningContext: String? = null,   // Current lesson topic
)
```

## Fallback Contract

`BoliAiLayer` guarantees:

| Condition | Behaviour |
|---|---|
| Gemma model not pushed | `isAvailable = false`, all calls → `DeterministicFallback` |
| Gemma init throws | Same — `isAvailable = false` |
| Gemma returns null/empty | `DeterministicFallback` silently used |
| Gemma responds | Structured response with `source = "gemma"` |

The `source` field in every response (`"gemma"` or `"deterministic_fallback"`)
is propagated to Flutter and shown as a badge in the camera lesson screen.

## Prompt Templates

All prompts are in `GemmaPromptBuilder.kt`. The output format is deliberately
simple (`KEY: value` lines) so `BoliAiLayer`'s parsers are robust even if the
model produces slight variations.

| Prompt method | Used in | Output format |
|---|---|---|
| `buildOcrLessonPrompt` | Camera lesson (primary flow) | `TOPIC:` `TRANSLATION:` `EXPLANATION:` `WORD:` `PRACTICE:` |
| `buildTranslationPrompt` | `translateText` bridge method | Plain text |
| `buildVocabularyPrompt` | `generateVocabulary` | `WORD: L2 = L1 (roman)` |
| `buildExplanationPrompt` | `getExplanation` bridge method | Plain prose |
| `buildRoleplayNextTurnPrompt` | `submitUserUtterance` roleplay | `L2:` `L1:` `HINT:` |

## Performance Notes

> **No numbers are claimed here.** Measure on the iQOO 15 device and document
> in `docs/device-notes.md` after on-device testing.

Expected variables that affect latency:
- Whether MediaPipe delegates to NNAPI/Hexagon (check logcat for `NNAPI`)
- Token count (512 max configured — reduce for faster roleplay turns)
- Thermal throttling (check `getHardwareTelemetry` → `thermal_headroom`)

To measure: use `latency_ms` field in every `AiResponse`, log via `adb logcat -s BoliGemma`.

## Adding a New Gemma Capability

1. Add a prompt builder method to `GemmaPromptBuilder.kt`
2. Add a public suspend method to `BoliAiLayer.kt` (with fallback)
3. Add a deterministic stub to `DeterministicFallback.kt`
4. Wire a new method name in `BoliBridgePlugin.kt` `onMethodCall`
5. Add the Flutter-side call to `boli_bridge.dart` (optional — only if Flutter needs it)

## Files in This Integration

| File | Role |
|---|---|
| `GemmaContext.kt` | Context data class + response types |
| `GemmaEngine.kt` | MediaPipe LLM wrapper, optional init |
| `GemmaPromptBuilder.kt` | All prompt templates |
| `BoliAiLayer.kt` | Abstraction: routes to Gemma or fallback |
| `DeterministicFallback.kt` | Rule-based responses, always available |
| `MlKitOcr.kt` | On-device Devanagari + Latin OCR |
| `BoliBridgePlugin.kt` | Platform channel wiring |
| `camera_lesson_screen.dart` | Camera → OCR → Gemma → TTS Flutter UI |
| `roleplay_screen.dart` | Gemma-powered conversation Flutter UI |
