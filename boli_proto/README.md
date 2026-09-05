# SeedheBol (boli_proto)

**Direct, on-device regional language tutor for Indian blue-collar and migrant workers.**

SeedheBol enables workers to achieve functional spoken competence in regional languages (e.g. Hindi/Bhojpuri to Marathi/Tamil) entirely offline on-device without cloud calls, data costs, or literacy barriers.

---

## Core Capabilities

1. **Point the Camera (Understand & Learn)**
   - High-contrast viewfinder for job-site signboards, hazard warnings, and wage slips.
   - On-device ML Kit OCR (Devanagari + Latin).
   - On-device Gemma 3n E2B / 2B INT4 SLM via MediaPipe: contextual workplace explanation, vocabulary breakdown with romanized pronunciation, and practice prompts.
   - Immediate audio playback via FastPitch TTS and vocal practice via IndicConformer ASR.

2. **Practise a Conversation (Situational Roleplay)**
   - On-device Gemma-driven supervisor, shopkeeper, and coworker dialogues.
   - Real-time spoken pronunciation assessment and articulatory feedback.

3. **Say It Out Loud (Speaking Drills)**
   - AI4Bharat IndicConformer ASR running on-device via ONNX Runtime.
   - Real-time acoustic and phonetic scoring against canonical target phonemes.

---

## Architecture & Technology Stack

* **Mobile Frontend**: Flutter 3.x (Dart) with high-contrast, large-touch-target handloom design system (`theme.dart`).
* **Platform Bridge**: Custom low-overhead asynchronous MethodChannels (`boli/engine_methods`, `boli/asr`).
* **On-Device SLM**: Gemma 2B / Gemma 3n E2B INT4 via Google MediaPipe LLM Inference Engine.
* **On-Device Speech Recognition**: AI4Bharat IndicConformer STT 120M (ONNX Runtime).
* **On-Device Speech Synthesis**: FastPitch + HiFi-GAN V1 (ONNX Runtime).
* **On-Device OCR**: Google ML Kit Devanagari & Latin Text Recognition.

---

## Building and Running

```bash
# Analyze Flutter code
flutter analyze

# Compile Android Kotlin backend
cd android
./gradlew :app:compileDebugKotlin

# Run on connected Android device
flutter run
```
