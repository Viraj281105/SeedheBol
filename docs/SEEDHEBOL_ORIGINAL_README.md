# Seedhebol (सीधेबोल)

> **Functional Indian Spoken Languages, 100% Offline, for People Who Move for Work.**  
> Built for the **iQOO Hackathon 2026 · City Battles (Pune Leg)**.

[![Platform](https://img.shields.io/badge/Platform-Android%2016%20%7C%20OriginOS%206-brightgreen.svg)](https://developer.android.com)
[![Hardware](https://img.shields.io/badge/NPU-Qualcomm%20Snapdragon%208%20Elite-blue.svg)](https://www.qualcomm.com)
[![Runtime](https://img.shields.io/badge/Inference-100%25%20On--Device-orange.svg)](#)
[![License](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)

---

## 🚀 Overview

**Seedhebol** is an on-device, voice-first regional language learning application engineered specifically for India's **140+ million internal migrant workforce**. 

When a Bhojpuri-speaking construction worker moves from Bihar to Chennai, or an Odia-speaking healthcare worker arrives in Kerala, they face an immediate economic and social crisis: **they cannot speak the local language, cannot read the local script, and have less than three weeks to become functional before losing employment opportunities.**

Seedhebol solves this with three non-negotiable architectural principles:
1. **Direct Indian Language to Indian Language (L1 → L2)**: Bhojpuri → Tamil directly. Zero English pivot.
2. **100% On-Device Edge AI**: Runs ASR, forced alignment, Goodness-of-Pronunciation (GOP) scoring, situational dialogue, and neural TTS locally on the **Qualcomm Hexagon NPU** with **zero internet, zero API keys, and zero data cost**.
3. **Voice-First & Zero-Literacy Primacy**: Operable entirely by voice and high-contrast tactile visual affordances. No reading or writing required.

```
                                      SEEDHEBOL SYSTEM ARCHITECTURE
                                      
  ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
  │                                    FLUTTER VOICE-FIRST UI SHELL                                 │
  │  ┌──────────────────────┐  ┌──────────────────────┐  ┌───────────────────┐  ┌────────────────┐  │
  │  │ Zero-Literacy Audio  │  │ Situational Roleplay │  │ Camera OCR Scanner│  │ Ambient Miner  │  │
  │  │ Navigation & Haptics │  │ Dialogue Interface   │  │ & Micro-Lessons   │  │ Transparency UI│  │
  │  └──────────┬───────────┘  └──────────┬───────────┘  └─────────┬─────────┘  └────────┬───────┘  │
  └─────────────┼─────────────────────────┼────────────────────────┼─────────────────────┼──────────┘
                │                         │ Platform Channels (JNI)│                     │
  ┌─────────────▼─────────────────────────▼────────────────────────▼─────────────────────▼──────────┐
  │                                    NATIVE ANDROID RUNTIME LAYER                                 │
  │  ┌───────────────────────────┐ ┌───────────────────────────┐ ┌────────────────────────────────┐  │
  │  │ AudioRecord (16kHz Mono)  │ │ CameraX Frame Analyzer    │ │ PowerManager Thermal Poller    │  │
  │  │ & AudioTrack (Barge-In)   │ │ & Indic OCR Bridge        │ │ & Telemetry Diagnostics        │  │
  │  └─────────────┬─────────────┘ └─────────────┬─────────────┘ └────────────────┬───────────────┘  │
  └────────────────┼─────────────────────────────┼────────────────────────────────┼──────────────────┘
                   │                             │                                │
  ┌────────────────▼─────────────────────────────▼────────────────────────────────▼──────────────────┐
  │                           ON-DEVICE NPU ACCELERATION (ONNX RUNTIME + QNN)                       │
  │  ┌─────────────────────────────────────────┐     ┌───────────────────────────────────────────┐  │
  │  │       IndicConformer (~60MB INT8)       │     │     FastPitch + HiFi-GAN V1 (~40MB)       │  │
  │  │  - CTC Head: GOP Frame Posteriors       │     │  - Single-Pass Non-Autoregressive TTS     │  │
  │  │  - RNNT Head: Real-time Transcription   │     │  - Multi-speaker (Male/Female) Indic      │  │
  │  └────────────────────┬────────────────────┘     └───────────────────────────────────────────┘  │
  │                       │                                                                         │
  │  ┌────────────────────▼──────────────────────────────────────────────────────────────────────┐  │
  │  │                     G2P & L1-INTERFERENCE PHONETIC SCORING ENGINE                         │  │
  │  │  - Indic Grapheme-to-Phoneme decomposition with Hindi Schwa Deletion                      │  │
  │  │  - L1-Confusion Matrix (Bhojpuri/Hindi -> Tamil Retroflex & Aspiration Error Targeting)   │  │
  │  └───────────────────────────────────────────────────────────────────────────────────────────┘  │
  └─────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 📦 Monorepo Structure

```
Seedhebol/
├── AGENTS.md                          # Strict rules & guidelines for AI agents and engineers
├── PROJECT_CONTEXT.md                 # Master Technical Blueprint & Single Source of Truth
├── README.md                          # Flagship project documentation (THIS FILE)
│
├── apps/
│   ├── mobile/                        # Flutter (Dart) mobile app + native Android Kotlin layer
│   │   ├── android/                   # Native AudioRecord, AudioTrack, QNN NPU, CameraX, HCE NFC
│   │   └── lib/                       # Riverpod state management & Zero-Literacy voice-first UI
│   └── companion_desktop/             # Teacher & Institutional companion app (Vivo Office Kit receiver)
│
├── packages/
│   ├── npu_engine/                    # Native ONNX Runtime + Qualcomm QNN Execution Provider
│   ├── g2p_indic/                     # Indic G2P & Hindi Schwa-Deletion rule engine
│   ├── l1_interference/               # L1-to-L2 phonetic confusion matrices & GOP scoring
│   ├── dialogue_engine/               # Low-latency branching situational dialogue state machine
│   ├── ambient_miner/                 # DPDP-compliant volatile ring-buffer token extractor
│   └── shared_models/                 # Unified domain schemas (Situations, AST, Attestations)
│
├── tools/
│   ├── model_pipeline/                # ONNX conversion, INT8 quantization & Parler-TTS renderer
│   ├── content_compiler/              # Situational curricula compiler (Construction, Nursing, Logistics)
│   └── officekit_bridge/              # Vivo Office Kit Free Transfer & Remote PC bridge scripts
│
└── docs/                              # Deep-dive architectural & hardware specifications
    ├── architecture/                  # System pipelines, GOP scoring math, DPDP privacy
    ├── hardware/                      # Snapdragon 8 Elite & OriginOS 6 telemetry specifications
    └── demo/                          # 3-minute airplane-mode stage pitch runbook
```

---

## 🌟 Key Features

### 1. Offline Situational Roleplay Loop
- Engage in realistic, domain-specific spoken dialogues (e.g., negotiating wages with a Chennai site supervisor).
- **Streaming ASR + deterministic dialogue state machine** delivers instant responses in `< 800ms`.
- **Full Barge-in Support**: Interrupt the persona mid-sentence; the audio stops instantly and listens.

### 2. L1-Interference Phonetic Scoring (GOP)
- Unlike generic apps that simply grade "correct/incorrect", Seedhebol diagnoses root linguistic causes.
- Powered by CTC forced alignment: *"Your Tamil retroflex consonant **ட** came out as dental **த** because of your Bhojpuri native phonological interference."*

### 3. Camera OCR → Instant Micro-Lesson
- Point the camera at printed signboards, medicine labels, or wage slips in regional scripts (Tamil, Kannada, Malayalam).
- Extracted unfamiliar vocabulary is automatically transformed into an immediate 5-word spoken pronunciation drill.

### 4. Ambient Vocabulary Mining (DPDP Compliant)
- Passively monitors ambient regional speech in busy public settings (buses, canteens, markets).
- Runs in an ephemeral 30-second RAM ring buffer. Discovers unknown regional words and queues them for tomorrow's commute lesson.
- **100% of raw audio is discarded immediately**. Zero biometric or acoustic data ever persists to disk.

### 5. Commute Mode (Eyes-Free)
- Motion sensors detect vehicle or walking movement and automatically switch to an audio-only, hands-free drill.

### 6. Institutional Assessment & Vivo Office Kit Export
- Teachers and supervisors can conduct batch oral reading fluency assessments (WCPM).
- Exports formatted reports to a connected PC seamlessly using **Vivo Office Kit Free Transfer**.

---

## 🛠️ Tech Stack & Hardware Specs

| Subsystem | Technology | Specifications / Details |
|---|---|---|
| **Target Hardware** | **iQOO 15** | Qualcomm Snapdragon 8 Elite Gen 5 (Hexagon NPU), 7000mAh Battery |
| **Operating System** | **OriginOS 6** | Android 16, API Level 36 |
| **Mobile UI Shell** | **Flutter (Dart)** | Flutter 3.x with Riverpod 2.x, High-Contrast Tactile Dark Mode |
| **Inference Bridge** | **Kotlin / JNI** | ONNX Runtime Mobile with Qualcomm QNN Execution Provider |
| **Acoustic ASR Model** | **IndicConformer** | AI4Bharat (~60 MB INT8 ONNX), Hybrid CTC-RNNT heads |
| **Speech Synthesizer** | **FastPitch + HiFi-GAN** | AI4Bharat (~40 MB ONNX), 16 Indian languages, male/female |
| **Lesson Audio Generator** | **Indic Parler-TTS** | Build-time pre-rendered 22kHz mono audio (0 MB runtime overhead) |
| **Audio I/O** | **Android AudioRecord / AudioTrack** | 16kHz mono PCM low-latency streaming pipeline |

---

## ⚡ Quickstart & Setup

### Prerequisites
- Flutter SDK (v3.24+)
- Android Studio / Android SDK (API 36 / Android 16)
- Python 3.10+ (for `tools/` compilation scripts)
- ONNX Runtime Mobile (`onnxruntime-android:1.19.0+`)

### 1. Clone & Initialize Monorepo
```bash
git clone https://github.com/your-org/Seedhebol.git
cd Seedhebol
```

### 2. Pre-Render Curriculum Audio & Compile Situations
```bash
# Generate situational dialogue trees and lesson audio assets
python tools/content_compiler/compile_curriculum.py --corridor bhojpuri_tamil --domain construction
```

### 3. Build & Run Mobile App (Android / iQOO 15)
```bash
cd apps/mobile
flutter pub get
flutter run --release
```

---

## 📖 Complete Documentation Index

- [**PROJECT_CONTEXT.md**](file:///d:/Hackathons/IQOOHackathon/PROJECT_CONTEXT.md) — Master Technical Blueprint & System Architecture
- [**AGENTS.md**](file:///d:/Hackathons/IQOOHackathon/AGENTS.md) — Strict Agent Operating Charter & Coding Rules
- [**Architecture Deep Dive**](file:///d:/Hackathons/IQOOHackathon/docs/architecture/01_system_architecture.md) — Detailed inference pipelines & mathematics
- [**Hardware Profiling**](file:///d:/Hackathons/IQOOHackathon/docs/hardware/snapdragon_8_elite_origin_os.md) — Snapdragon 8 Elite NPU & thermal policy
- [**3-Minute Pitch Runbook**](file:///d:/Hackathons/IQOOHackathon/docs/demo/3_minute_pitch_runbook.md) — Airplane mode stage demonstration guide

---

## ⚖️ License & Ethical Commitment

Seedhebol is distributed under the **MIT License**. All acoustic models (AI4Bharat Vistaar / Indic-TTS / Indic Parler-TTS) are open-source and MIT-licensed. Seedhebol adheres strictly to India's **Digital Personal Data Protection (DPDP) Act 2023** regarding ephemeral voice processing.
