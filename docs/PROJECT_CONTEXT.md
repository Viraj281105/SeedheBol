# PROJECT_CONTEXT.md — Seedhebol Master Technical Blueprint

> **Single Source of Truth (SSOT)** for the Seedhebol on-device, voice-first language acquisition platform. Every agent, developer, and automated pipeline must refer to this document before making architectural, algorithmic, or structural modifications.

---

## 1. Executive Summary & Core Mission

### 1.1 The Core Mission
**Seedhebol** (सीधेबोल / "Speak Directly") is an on-device, voice-first regional language learning engine engineered for India's **140M+ internal migrant workforce**. Seedhebol enables an illiterate or semi-literate migrant worker to achieve **functional spoken competence** in a new regional Indian language within **three weeks**, operating **100% offline on a low-to-mid-range smartphone with zero data cost, zero cloud latency, and zero reading required**.

### 1.2 The Three Structural Differentiators
1. **Direct Indian Language to Indian Language (L1 → L2)**: No English pivot. A Bhojpuri speaker learns Tamil with explanations in Bhojpuri/Hindi, preserving socio-cultural registers, honorifics, and workplace idioms.
2. **100% On-Device & Edge AI Native**: Complete ASR, forced alignment, Goodness-of-Pronunciation (GOP) scoring, dialogue branching, and TTS synthesis execute on the phone's Neural Processing Unit (Qualcomm Hexagon NPU / CPU fallback). Works seamlessly in airplane mode or on remote construction sites with zero network signal.
3. **Voice-First, Zero-Literacy Primacy**: The application is fully operable without reading or writing. Navigated entirely by voice, audio prompts, and high-contrast tactile visual affordances.

### 1.3 The Unshippable Moat: Ambient Vocabulary Mining
Cloud-based continuous audio streaming violates India's **Digital Personal Data Protection (DPDP) Act** and is a privacy catastrophe. Seedhebol runs a strictly local, ephemeral audio ring buffer on the NPU that extracts unknown regional vocabulary lemmas and discards all raw audio immediately. **On-device edge AI is the only legal and ethical architecture that makes this feature possible.**

---

## 2. Demographic Realities & Migration Corridors

### 2.1 The Migration Scale
| Metric | Figure | Source / Context |
|---|---|---|
| Total Internal Migrants (Place of Last Residence) | 450 Million (37% of population) | Census 2011 |
| Current Estimated Migrant Workers | ~140–200 Million | Economic Survey / Academic Projections |
| Circular / Short-Term Seasonal Migrants | >200 Million | World Bank / IHDS |
| Annual Inter-State Migration Flow | ~9 Million / year | Ministry of Finance |
| Inter-District Migration (Often Crossing Dialect Boundaries) | 26% of all movement | Census Data |

### 2.2 Critical Language Corridors
| Priority | Source State (L1) | Destination State (L2) | Primary Corridor | Key Linguistic Challenge |
|---|---|---|---|---|
| **1 (Flagship)** | **Bihar / UP (Bhojpuri / Hindi)** | **Tamil Nadu (Tamil)** | Muzaffarpur/Patna → Chennai/Coimbatore | Indo-Aryan to Dravidian; retroflex/aspiration divergence; distinct script |
| **2** | **Odisha (Odia)** | **Kerala (Malayalam)** | Ganjam/Cuttack → Kochi/Ernakulam | Complex agglutination; nasalization; completely distinct phonetics |
| **3** | **UP / Bihar (Hindi)** | **Karnataka (Kannada)** | Gorakhpur/Varanasi → Bengaluru | Dravidian verb morphology; formal vs. colloquial register shifts |

### 2.3 Economic & Literacy Bottlenecks
- **Wage Suppression & Exploitation**: Inability to negotiate piece rates, verify overtime, or challenge underpayment due to language barriers.
- **Safety Hazards**: Inability to comprehend site hazard warnings or describe medical symptoms.
- **Illiteracy Statistics**: 10–15% of male and ~30% of female interstate migrants cannot read in any language. Nationally, ASER 2024 shows 76.6% of Grade 3 children cannot read Grade 2 level text.
- **Smartphone Reality**: 89% of 14–16 year olds have smartphone access, but prepaid data packs (1.5GB/day) are strictly rationed for family calls. Zero-data offline capability is an absolute economic requirement.

---

## 3. Target User Personas

### Persona A (Primary): Ramesh (Construction Worker)
- **Age / Background**: 34, from Muzaffarpur, Bihar.
- **L1 / Literacy**: Speaks Bhojpuri and conversational Hindi. Reads halting Hindi (Devanagari only). Cannot read Tamil script.
- **Context**: Construction site in Chennai on a 6-month contract. Has 3 weeks to achieve functional spoken Tamil or risks demotion to unskilled daily wage.
- **Hardware**: ₹8,000 Android device (3GB RAM, 64GB Storage), 1.5GB/day prepaid pack.
- **Usage Pattern**: 90 minutes daily on noisy bus commutes; hands occupied, eyes exhausted.
- **Core Need**: Site safety, tool names, supervisor commands, wage bargaining, clinic basics.

### Persona B (Secondary): Lakshmi (Frontline Healthcare Nurse)
- **Age / Background**: 27, nurse from Kozhikode, Kerala.
- **L1 / Literacy**: Malayalam L1, fluent English, zero Kannada. Literate and smartphone-fluent.
- **Context**: Starting at a private hospital in Bengaluru. Needs clinical Kannada within 14 days.
- **Core Need**: Patient intake, symptom triage, dosage instructions, patient consent, family bedside reassurance.

### Persona C (Institutional): Sunita (Anganwadi / Foundational Educator)
- **Context**: Primary educator in rural Maharashtra managing 40 multilingual students.
- **Core Need**: Batch oral reading fluency assessment (ASER protocol), WCPM tracking, offline roster scoring, and export via Vivo Office Kit to laptop.

---

## 4. On-Device Model Architecture & Hardware Tiering

```
                     ┌─────────────────────────────────────────────────────────┐
                     │                 HARDWARE ACCELERATION                   │
                     │  Qualcomm Snapdragon 8 Elite Gen 5 (Hexagon NPU / QNN)  │
                     └────────────────────────────┬────────────────────────────┘
                                                  │
             ┌────────────────────────────────────┼────────────────────────────────────┐
             ▼                                    ▼                                    ▼
┌─────────────────────────┐          ┌─────────────────────────┐          ┌─────────────────────────┐
│     ASR & ALIGNMENT     │          │    TTS SYNTHESIS        │          │   SITUATIONAL DIALOGUE  │
│ IndicConformer (~60MB)  │          │ FastPitch+HiFi-GAN      │          │ Branching State Machine │
│ Hybrid CTC + RNNT       │          │ (~40MB, 16 Languages)   │          │ (Deterministic JSON AST)│
│  - CTC: GOP Posteriors  │          │  - Male / Female Voices │          │  - Zero Latency         │
│  - RNNT: Transcripts    │          │  - Non-autoregressive   │          │  - Zero Hallucination   │
└─────────────────────────┘          └─────────────────────────┘          └─────────────────────────┘
```

### 4.1 Model Selection Rationale
| Component | Selected Model | Size / Format | License | Why Selected Over Alternatives |
|---|---|---|---|---|
| **ASR + GOP Scorer** | **IndicConformer** (AI4Bharat) | ~60 MB (INT8 ONNX) | MIT | **Dual CTC-RNNT heads**. CTC head outputs frame-level posteriors required for Goodness-of-Pronunciation (GOP); RNNT outputs clean transcripts. Rejected IndicWhisper (769M autoregressive, 6x larger, no frame posteriors). |
| **Runtime TTS** | **FastPitch + HiFi-GAN V1** | ~40 MB (ONNX) | MIT | **Intelligibility over cosmetic smoothness**. Lowest Character Error Rate (CER) on Indic languages in IIT Madras benchmarks. Feed-forward, single-pass NPU execution. |
| **Curriculum Audio** | **Indic Parler-TTS** | Build-Time Only | MIT | 880M parameter autoregressive model pre-renders 22kHz mono authored lesson audio on laptop/cloud before build time. **0 MB runtime overhead**. |
| **G2P Engine** | `g2p_indic` | Native / Rule | In-House | Custom Indic phoneme decomposition with explicit **Hindi Schwa-Deletion** rule engine (`kamal` vs `kamala`). |

### 4.2 Hardware & Thermal Tiering Policy
Target Device: **iQOO 15 (Snapdragon 8 Elite Gen 5, OriginOS 6 on Android 16)**.
Monitored via `PowerManager.getThermalHeadroom(forecastSeconds)` (polled at 1Hz):

| Thermal Headroom | Device Status | System Action |
|---|---|---|
| `< 0.70` | Nominal | Full NPU pipeline: Streaming ASR + FastPitch TTS + Ambient Miner active |
| `0.70 – 0.85` | Warm | Switch runtime TTS to pre-rendered audio cache; reduce ASR beam width |
| `0.85 – 0.95` | Elevated | Pause ambient mining; CTC-only decode (drop RNNT pass) |
| `> 0.95` | Throttling Risk | Fallback to cached phrasebook mode; zero active NPU compute |

---

## 5. End-to-End System Pipelines

### 5.1 Real-Time Situational Roleplay Loop
```
[User Speaks] ──► [Android AudioRecord 16kHz Mono]
                        │
                        ▼
                 [VAD Gate (Silero / WebRTC)]
                        │
                        ▼
           [IndicConformer Streaming CTC Decode (NPU)]
                        │ (Partial Transcripts < 150ms)
                        ▼
           [Branching Dialogue Engine Intent Matcher]
                        │
                        ▼
           [Trigger Persona Turn: Pre-rendered / FastPitch Audio]
                        │
                        ▼
          [Android AudioTrack Output (Supports Barge-In)]
                        │
           [Parallel Background RNNT Decode + GOP Forced Alignment]
                        │
                        ▼
           [Haptic & Visual L1 Phonetic Diagnostic Overlay]
```
*Target End-to-End Latency: `< 800ms`.*

### 5.2 Pronunciation Scoring (GOP) & L1-Interference Targeting
1. **Target String** $\to$ `g2p_indic` $\to$ Canonical Phoneme Sequence $P = \{p_1, p_2, \dots, p_N\}$.
2. **Audio Frames** $\to$ IndicConformer Acoustic CTC Posteriorgram $P(y_t = k | x_{1:T})$.
3. **Forced Alignment** $\to$ Time boundaries $(t_{start}, t_{end})$ for each phoneme $p_n$.
4. **Goodness-of-Pronunciation Metric**:
   $$\text{GOP}(p_n) = \frac{1}{t_{end} - t_{start} + 1} \sum_{t=t_{start}}^{t_{end}} \log \frac{P(p_n | x_t)}{\max_{q \in \text{Phonemes}} P(q | x_t)}$$
5. **L1 Interference Matrix Comparison**: Evaluates specific substitution patterns (e.g., Hindi speaker replacing Tamil retroflex $/\text{ʈ}/$ with dental $/\text{t̪}/$) and provides targeted vocal articulation guidance.

### 5.3 Camera OCR Micro-Lesson Pipeline
- **Capture**: Android CameraX frame analysis.
- **Detection & Recognition**: Local Indic OCR model detects regional script (Tamil/Kannada/Malayalam) on signboards, medicine bottles, or wage slips.
- **Diff & Extraction**: Filters extracted text against the user's `known_lemmas` local database.
- **Lesson Assembly**: Assembles an instant 5-word spoken drill teaching vocabulary discovered in the physical world.

### 5.4 DPDP-Compliant Ambient Vocabulary Mining
- **Operation**: Runs as an Android Foreground Service with a prominent ongoing notification and active mic indicator.
- **Volatile Ring Buffer**: Maintains a 30-second rolling audio buffer in RAM.
- **Local VAD & Language ID**: If ambient language matches target L2, runs ASR $\to$ tokenizes lemmas.
- **Immediate Purge**: **100% of raw audio is discarded immediately**. Extracted unfamiliar words are queued for the user's next morning commute lesson.

---

## 6. Monorepo Structure & Package Manifest

```
Seedhebol/
├── apps/
│   ├── mobile/                                 # Flutter Application Shell
│   │   ├── android/                            # Native Kotlin Android Host
│   │   │   └── app/src/main/kotlin/com/seedhebol/app/
│   │   │       ├── audio/                      # AudioRecord & AudioTrack barge-in engine
│   │   │       ├── npu/                        # ONNX Runtime & Qualcomm QNN JNI bindings
│   │   │       ├── telemetry/                  # PowerManager thermal headroom polling
│   │   │       ├── ocr/                        # CameraX Indic OCR pipeline
│   │   │       ├── nfc/                        # Custom HCE AID peer practice driver
│   │   │       └── officekit/                  # Vivo Office Kit Remote PC / Free Transfer integration
│   │   └── lib/                                # Flutter Riverpod Presentation & State
│   │       ├── core/                           # Theme, design system, zero-literacy tokens
│   │       ├── features/
│   │       │   ├── roleplay/                   # Conversational dialogue interface
│   │       │   ├── pronunciation/              # GOP visual/tactile phoneme diagnostics
│   │       │   ├── camera_lesson/              # Real-time OCR scanner & micro-lesson
│   │       │   ├── ambient/                    # Ambient mining toggle & privacy transparency
│   │       │   └── commute/                    # Eyes-free accelerometer-triggered commute player
│   │       └── main.dart
│   └── companion_desktop/                      # Electron / Flutter Desktop Teacher Portal
├── packages/
│   ├── npu_engine/                             # ONNX Runtime C++/Kotlin interface
│   ├── g2p_indic/                              # Indic G2P & Schwa Deletion engine
│   ├── l1_interference/                        # Confusion matrices & GOP scoring algorithms
│   ├── dialogue_engine/                        # Situational branching dialogue state machine
│   ├── ambient_miner/                          # Ephemeral ring-buffer token miner
│   └── shared_models/                          # Canonical schemas (Situations, AST, Attestations)
├── tools/
│   ├── model_pipeline/                         # ONNX export, INT8 quantizers, Parler-TTS batch script
│   ├── content_compiler/                       # Situational curricula JSON generator
│   └── officekit_bridge/                       # Vivo Office Kit telemetry and transfer CLI
└── docs/                                       # Granular architecture & demo specifications
```

---

## 7. 30-Hour Build Priorities & Strict Cut List

### 7.1 The Six Mandatory Hackathon Deliverables
1. **Offline Roleplay Dialogue Loop**: Live ASR $\to$ intent branch $\to$ pre-rendered/FastPitch speech playback with barge-in support.
2. **Phoneme-Level Pronunciation Feedback**: G2P forced alignment + GOP score identifying exact L1 retroflex/aspiration substitutions.
3. **Camera OCR $\to$ Micro-Lesson**: Point camera at printed Tamil/Kannada text, extract unseen words, and run instant pronunciation drill.
4. **Ambient Vocabulary Mining**: Opt-in background service mining unknown spoken words into the queue with zero audio persistence.
5. **Zero-Literacy Voice Navigation Shell**: High-contrast, tactile, voice-driven UI usable without reading a single character.
6. **Vivo Office Kit Teacher Export**: Seamless transfer of student oral reading fluency evaluations to PC.

### 7.2 Strict Cut Hierarchy (If Behind Schedule)
1. *Cut 1st*: Front-camera facial articulation feedback.
2. *Cut 2nd*: NFC peer-to-peer practice.
3. *Cut 3rd*: Indic handwriting stroke recognition.
4. *Cut 4th*: Secondary dialect options (lock to single flagship dialect per corridor).
5. *Cut 5th*: Gamified leagues and social leaderboards.
6. *Cut 6th*: Real-time dynamic thermal tiering (convert to documented static policy).

---

## 8. Hackathon 3-Minute Demo Runbook

- **[0:00 – 0:15] The Hook**: Present Ramesh's situation (Bihari migrant in Chennai with 3 weeks to learn Tamil).
- **[0:15 – 0:25] The Airplane Mode Proof**: Turn on Airplane Mode in front of the jury. Prove zero network dependencies.
- **[0:25 – 0:45] Zero-Literacy Voice Launch**: Wake the app and select construction domain using pure voice navigation.
- **[0:45 – 1:20] Situational Roleplay & Barge-In**: Engage with the site supervisor persona in Tamil. Interrupt mid-sentence to prove real-time barge-in.
- **[1:20 – 1:50] Targeted GOP Pronunciation Feedback**: Deliberately mispronounce a Tamil retroflex consonant. The app diagnoses: *"Your ட came out as a त because of your Bhojpuri background."*
- **[1:50 – 2:15] Live Camera OCR**: Scan a physical Tamil safety signboard $\to$ instant translation and 5-word micro-lesson.
- **[2:15 – 2:40] Ambient Vocabulary Mining**: Reveal the ambient words captured during the room discussion. Highlight DPDP compliance.
- **[2:40 – 3:00] Closing & Verification**: Show device telemetry proving Hexagon NPU execution while remaining 100% in Airplane Mode.
