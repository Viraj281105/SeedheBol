# SeedheBol — Project Document

**Functional Indian languages, offline, for people who move for work.**

An on-device, voice-first language learning application for Indian regional languages.
Built for the iQOO Hackathon 2026 · City Battles, Pune leg (5–6 September 2026).


---

## Table of contents

1. [Executive summary](#1-executive-summary)
2. [The problem](#2-the-problem)
3. [Target users](#3-target-users)
4. [Positioning and product thesis](#4-positioning-and-product-thesis)
5. [Complete feature specification](#5-complete-feature-specification)
6. [Technical architecture](#6-technical-architecture)
7. [Tech stack](#7-tech-stack)
8. [Content and data architecture](#8-content-and-data-architecture)
9. [Implementation approach](#9-implementation-approach)
10. [Hackathon alignment](#10-hackathon-alignment)
11. [30-hour build plan and cut list](#11-30-hour-build-plan-and-cut-list)
12. [Risks and mitigations](#12-risks-and-mitigations)
13. [Demo script](#13-demo-script)
14. [Beyond the hackathon](#14-beyond-the-hackathon)
15. [Appendices](#15-appendices)

---

## 1. Executive summary

**What it is.** Boli teaches functional spoken competence in Indian regional languages, running entirely on the device with no network calls. Automatic speech recognition, machine translation, text-to-speech, and lesson generation all execute on the phone's NPU.

**Who it is for.** India's internal migrants — the ~140 million people who move within the country for work and arrive somewhere they cannot speak the local language. Secondarily: students, healthcare and frontline workers, and anyone in a multilingual workplace.

**Why it is different.** Three structural decisions, none of which any existing product makes together:

1. **Indian language to Indian language, directly.** No English pivot. Bhojpuri speaker learns Tamil, not "Bhojpuri → English → Tamil."
2. **Fully offline.** Zero data cost, works in airplane mode, works on a construction site with no signal.
3. **Voice-first, literacy-optional.** The entire application is operable without reading anything, because a large share of the target user base cannot read fluently in any language.

**Why on-device is not an optimisation.** The flagship feature — ambient vocabulary mining, where the phone passively listens to the language around the user and builds tomorrow's lesson from it — is *unshippable* by anyone streaming audio to a server. Continuous ambient audio to a cloud endpoint is a privacy catastrophe and a regulatory non-starter under India's DPDP framework. Running locally is what makes the feature legal to exist. That is a defensible position, not a rubric hack.

**The stack.** Two MIT-licensed models totalling roughly 100MB on the device: AI4Bharat's IndicConformer for speech recognition and forced alignment, and FastPitch + HiFi-GAN V1 for synthesis. Lesson audio is pre-rendered at build time with Indic Parler-TTS. No vendor access, no API keys, nothing to negotiate.

**Hackathon fit.** The product is phone-native by construction: it needs the microphone, the camera, the NPU, motion sensors, and NFC. A laptop version of this product is strictly worse or impossible. That is the exact filter the iQOO scoring rubric applies.

---

## 2. The problem

### 2.1 The migration reality

India's internal migration is one of the largest sustained human movements in the world, and it is almost entirely invisible to consumer software.

| Metric | Figure | Source |
|---|---|---|
| Internal migrants (place of last residence) | 450 million — 37% of population | Census 2011 |
| Growth over previous decade | +45% (from 309 million in 2001) | Census 2001/2011 |
| Inter-state migrant workers | 41.4 million | Census 2011 |
| Estimated migrant workers today | ~140 million (~200M including intra-district) | Post-Census estimates |
| Circular migrants | >200 million | World Bank / IHDS, 2011–12 |
| Annual inter-state migration | ~9 million per year | Economic Survey 2017 |
| Post-COVID estimate of total internal migrants | ~600 million | Economic Survey 2023–24 and independent researchers |

Movement breaks down as roughly 62% within the same district, 26% between districts inside a state, and 12% inter-state.

**The important nuance:** even the 26% inter-district figure often crosses a language boundary. Marathi in Nagpur is not Marathi in Kolhapur. Telangana Telugu is not coastal Andhra Telugu. The language problem is far larger than the inter-state number alone suggests.

**Major source states:** Uttar Pradesh, Bihar, Madhya Pradesh, Rajasthan, West Bengal, Odisha, Jharkhand.
**Major destination states:** Delhi, Maharashtra, Tamil Nadu, Gujarat, Karnataka, Kerala, Andhra Pradesh.

Note the pairs this creates. Bihar → Tamil Nadu. Odisha → Kerala. UP → Karnataka. Every one of those is a language pair with essentially zero consumer learning material, and no shared script.

### 2.2 The language barrier is an economic barrier

For a migrant worker, not speaking the local language is not an inconvenience. It is:

- **Wage suppression.** Inability to negotiate, verify piece rates, or challenge underpayment.
- **Safety risk.** Cannot read or hear site safety instructions; cannot describe an injury at a hospital.
- **Exploitation exposure.** Dependence on a contractor or middleman who becomes the sole interface with the outside world.
- **Service exclusion.** Cannot navigate a ration shop, a bank, a police station, a school admission.
- **Social isolation.** Which is a mental health cost nobody measures.

Existing research on interstate migrants notes that low literacy and dire economic need leave workers unable to bargain or assert rights, and that contractors exploit exactly this weakness.

### 2.3 The literacy reality

This is the constraint that kills most edtech products aimed at this population, and the one that shapes Boli's entire interface design.

- Among inter-state migrants aged 15–59, roughly **10–15% of men and around 30% of women are illiterate** (Census 2011 analysis).
- Nationally, ASER 2024 found **76.6% of Class 3 students still cannot read a Class 2 level text**, and **66.3% of Class 3 and 70% of Class 5 students cannot perform simple arithmetic**.
- Reading levels are improving — Class 3 government school students reading a Class 2 text rose from 16.3% in 2022 to 23.4% in 2024, the highest since ASER began in 2005 — but the base is very low.

**Implication:** any product that requires reading to operate has excluded a third of its intended users before it starts. Voice cannot be a feature. It has to be the interface.

### 2.4 Smartphone access is not the bottleneck

ASER 2024's first-ever digital literacy module found **89% of 14–16 year olds have smartphone access**, with 57% using it for education and 76% for social media. Personal ownership skews male: 36.2% of boys versus 26.9% of girls.

So the device exists. What doesn't exist is software designed for the person holding it.

### 2.5 Why every existing solution fails this user

| Product | Why it fails |
|---|---|
| **Duolingo** | English-centric. Indian language courses are English→Hindi only. Needs connectivity. Assumes literacy. Teaches tourist vocabulary, not work vocabulary. Gamification tuned for hobbyists, not for someone with a three-week deadline. |
| **Google Translate** | A lookup tool, not a teacher. No retention, no assessment, no pronunciation feedback. Offline packs exist but the conversational experience does not. |
| **Multibhashi / Hello English** | Aimed at English acquisition, i.e. the opposite direction. Still connectivity-dependent. |
| **YouTube tutorials** | Passive, unstructured, no feedback loop, data-hungry, and requires knowing what to search for in a language you don't have. |
| **Bhashini-powered apps** | Excellent models, but cloud-first architecture. No offline mode. |
| **NGO / in-person classes** | Actually work, but do not scale, and require a worker to be somewhere at a fixed time — which shift work makes impossible. |

**The unoccupied position:** Indian-language-to-Indian-language, offline, voice-first, occupation-specific, on the phone the person already owns.

---

## 3. Target users

### Primary persona — Ramesh

34, from Muzaffarpur, Bihar. Speaks Bhojpuri and functional Hindi. Reads Hindi haltingly, Devanagari only. Works construction in Chennai on a six-month contract. Has three weeks to become functional in Tamil or his supervisor stops assigning him skilled work.

- Phone: ₹8,000 Android, 3GB RAM, 64GB storage
- Data: 1.5GB/day prepaid pack, mostly spent on video calls home
- Available learning time: 90 minutes of bus commute, hands occupied, eyes tired
- Cannot read Tamil script at all
- Needs: site safety vocabulary, wage negotiation, buying food, asking directions, hospital basics

**What Ramesh needs from a product:** speed to functional competence, not completeness. Voice, not text. Work vocabulary, not colours and animals.

### Secondary persona — Lakshmi

27, nurse from Kozhikode, Kerala. Malayalam L1, good English, no Kannada. Starting at a Bengaluru hospital. Needs clinical Kannada — patient intake, symptom description, consent explanation, family communication — within two weeks. Literate, smartphone-fluent, motivated, time-poor.

**What Lakshmi needs:** domain-specific register, correct politeness forms, pronunciation good enough that a patient in distress understands her the first time.

### Tertiary persona — Sunita

Anganwadi worker / primary school teacher in rural Maharashtra. Has 40 children, mixed home languages, and an obligation to assess foundational reading. Currently does this one-on-one with a clipboard.

**What Sunita needs:** batch oral assessment, offline, with an exportable report.

### Non-target users (explicitly out of scope)

- Hobbyist language learners with time and connectivity — Duolingo serves them adequately
- Academic/literary study of Indian languages
- Sanskrit and classical language pedagogy
- Anyone learning an Indian language from outside India

Naming who you are *not* for is part of the pitch. It signals focus.

---

## 4. Positioning and product thesis

### One sentence

> Boli gets a person from zero to functionally employable in a second Indian language in three weeks, using only their phone, with no internet and no reading required.

### The reframe that makes this defensible

"Duolingo for Indian languages" is a weak pitch. It invites the response *"so, a worse Duolingo."*

The strong framing:

> **Duolingo teaches you to talk about owls. India's language problem is a Bihari construction worker in Chennai who has three weeks to become functional in Tamil.**

Everything else follows from taking that user seriously.

### Three non-negotiable design principles

**1. Voice is the interface, not a feature.**
Every core loop must be completable without reading. Text is an enhancement layer for literate users, never a requirement.

**2. Direct L1 → L2, never through English.**
English pivoting destroys register, politeness levels, and idiom. A Bhojpuri speaker learning Tamil should get explanations in Bhojpuri and Tamil, with English nowhere in the chain.

**3. Offline is the default state, not a degraded mode.**
The product assumes no network. Connectivity, when present, is used only for optional content sync and anonymous telemetry — never for inference.

### The moat argument

On-device inference here is not a cost optimisation or a latency trick. It is an **enabling constraint**:

- **Ambient vocabulary mining** cannot legally exist as a cloud product. Continuous passive microphone capture streamed to a server is a non-starter under DPDP and any reasonable privacy review. On-device makes it shippable.
- **Zero marginal cost** means the product can be free forever for a user who cannot pay per-query API costs.
- **Zero data cost** means it works for someone rationing 1.5GB a day.
- **Zero latency floor** means conversational roleplay feels like a conversation rather than a walkie-talkie.

---

## 5. Complete feature specification

Organised in layers. Layers 2, 4, and 5 are where the product differentiates; Layer 0 is table stakes that must merely look finished.

### Layer 0 — Application shell (table stakes)

| # | Feature | Notes |
|---|---|---|
| 0.1 | Units → sections → lessons progression tree | Standard gamified structure |
| 0.2 | XP, streaks, hearts/lives, leagues | Standard retention mechanics |
| 0.3 | **Spoken placement test** | Not written. Establishes L1, L2, current level, and target domain by voice |
| 0.4 | **Offline-first progress with deferred sync** | Streaks and XP accrue with no network; reconcile later |
| 0.5 | Roman-script transliteration input | Most Indians type Indic languages in Roman script. Non-negotiable for literate users |
| 0.6 | Daily goal setting tuned to commute length | "20 minutes" is meaningless; "one bus ride" is not |
| 0.7 | Low-storage mode | Download only the language pair and domain in use |

### Layer 1 — Core language engine (all on-device)

| # | Feature | Edge AI role |
|---|---|---|
| 1.1 | Streaming ASR with partial hypotheses | On-device acoustic model, NPU |
| 1.2 | TTS with consistent speaker identity across languages | On-device vocoder, NPU |
| 1.3 | Direct Indic↔Indic neural machine translation | On-device seq2seq, no English pivot |
| 1.4 | Language identification and code-switch detection | Handles Hinglish/Tanglish natively rather than penalising it |
| 1.5 | Grapheme-to-phoneme (G2P) conversion | Prerequisite for all pronunciation scoring |
| 1.6 | Transliteration engine (Roman ↔ native script) | Rule-based + learned hybrid |
| 1.7 | Voice activity detection and barge-in | User can interrupt TTS mid-sentence |

### Layer 2 — Assessment and feedback (highest edge-AI density)

This layer is where technical depth is demonstrated.

| # | Feature | Description |
|---|---|---|
| 2.1 | **Phoneme-level pronunciation scoring** | Forced alignment (CTC) against the G2P target, plus Goodness-of-Pronunciation scoring. Output is not "correct/incorrect" but *"your ट came out as a त."* |
| 2.2 | **L1-interference-aware error targeting** ⭐ | A Marathi speaker learning Tamil makes systematically different errors from a Bengali speaker learning Tamil. Model the user's L1 and pre-load the known confusion set for that specific pair. **No existing product does this for Indian languages.** |
| 2.3 | **Aspiration and retroflex discrimination** | क/ख, प/फ, त/ट, द/ड — the phoneme contrasts that actually break cross-Indian language learning. Detecting them requires voice-onset-time analysis at millisecond resolution. Genuine NPU work. |
| 2.4 | **Prosody and intonation scoring** | f0 contour extraction and DTW comparison against reference. Critical for Tamil/Telugu question intonation and Punjabi tone. |
| 2.5 | **Automated oral reading fluency assessment** | Words-correct-per-minute, substitutions, omissions, self-corrections, hesitation points, phrasing quality. Modelled on the ASER protocol. Fully offline; audio never leaves the device. |
| 2.6 | Disfluency analytics | Filler frequency, pause distribution, speaking rate trend over time |
| 2.7 | **Speech-derived confidence tracking** | The system knows you hesitated 800ms before answering correctly. Longitudinal, on-device |
| 2.8 | Noisy real-world listening comprehension | Training and evaluation against bus-stand and market audio, not studio recordings |
| 2.9 | Minimal-pair discrimination drills | Auto-generated from the user's actual error history |

### Layer 3 — Content generation (on-device small language model)

| # | Feature | Description |
|---|---|---|
| 3.1 | **Occupation-specific lesson generation** | Construction, nursing, delivery/logistics, domestic work, auto/taxi driving, retail counter, hospitality, security, factory floor. Not "food vocabulary" but "the forty phrases you need to not get fired in week one" |
| 3.2 | **Offline roleplay dialogue** ⭐ | On-device persona plays shopkeeper, landlord, hospital receptionist, site supervisor, police constable, bank clerk. Full STT → SLM → TTS loop with zero network. **This is the demo.** |
| 3.3 | **Speech-driven spaced repetition** | Most SRS uses self-reported recall. Boli schedules based on measured hesitation and pronunciation confidence, not on the user saying "easy" |
| 3.4 | Confusion-targeted distractors | Multiple-choice wrong answers generated from *this user's* error history, not a static bank |
| 3.5 | Situational scenario generator | "You are at a ration shop and the clerk says the card isn't registered." Generated, not authored |
| 3.6 | Politeness/register calibration | Same sentence in three registers: to a peer, to an employer, to an elder |
| 3.7 | Cultural context notes | Why a literal translation is rude; when honorifics are mandatory |

### Layer 4 — Phone-native features (the "only possible on a phone" differentiators)

| # | Feature | Sensor | Description |
|---|---|---|---|
| 4.1 | **Camera OCR → instant micro-lesson** ⭐ | Camera | Point at a signboard, menu, bus destination board, government form, prescription, or wage slip. Get translation plus a generated lesson from those exact words. Indic OCR across scripts is genuinely hard — which is precisely why doing it well is impressive |
| 4.2 | **Ambient vocabulary mining** ⭐⭐ | Microphone | Passively hear the language around you in a shop, canteen, or bus. Transcribe on-device. Mine unknown vocabulary. Build tomorrow's lesson. **Impossible as a cloud product.** Strict opt-in, visible indicator, no audio retained — only extracted tokens |
| 4.3 | Location-aware lesson switching | GPS/geofence | Market → bargaining phrases. Hospital → medical vocabulary. Bus stand → travel. Station → ticketing |
| 4.4 | **Commute mode** | Accelerometer, activity recognition | Detects walking or vehicle motion, switches to eyes-free audio-only lessons. Target users commute 60–120 minutes daily with hands occupied |
| 4.5 | Indic handwriting practice | Touch trajectory | Finger/stylus stroke capture, scored on stroke order and shape. Hard mode: conjuncts (क्ष, ज्ञ, त्र) |
| 4.6 | **Peer practice over NFC / Wi-Fi Direct** | NFC, Wi-Fi Direct | Two learners tap phones, receive a paired dialogue exercise, both sides scored locally, zero internet. Uses custom (non-payment) HCE AIDs |
| 4.7 | Front-camera articulation feedback | Front camera | Lip position guidance for sounds the user cannot produce. Ambitious; stretch goal |
| 4.8 | Proximity-triggered classroom mode | Ultrasonic / BLE | Teacher device discovers nearby learner devices for group sessions |

### Layer 5 — Inclusion and accessibility

| # | Feature | Description |
|---|---|---|
| 5.1 | **Zero-literacy mode** ⭐ | The entire application operable by voice, with no reading required anywhere. Icons plus audio labels. This is the single biggest real-world differentiator and it forces the most rubric-aligned architecture |
| 5.2 | Dialect selection | Pune vs Nagpur Marathi; Chennai vs Madurai Tamil; Hyderabad vs Vijayawada Telugu |
| 5.3 | Graceful degradation on low-end devices | Model tiering: full NPU stack on flagship, quantised subset on 3GB devices, phrasebook-only on the weakest hardware |
| 5.4 | Airplane-mode operation | Everything works with the radio off. Demonstrable in three seconds |
| 5.5 | Large-touch-target, high-contrast UI | Users may have damaged fingertips from manual labour, or be reading in direct sunlight |
| 5.6 | Adjustable speech rate | Native-speed audio is unusable for a beginner; a slider from 0.6× to 1.2× |

### Layer 6 — Institutional and teacher layer

| # | Feature | Description |
|---|---|---|
| 6.1 | **Batch oral assessment** | Teacher runs fluency assessment across 40 students; results aggregate on the phone |
| 6.2 | **Office Kit report export** | Mirror the class dashboard to a laptop, export CSV/PDF. Earns the Office Kit criterion by construction |
| 6.3 | Cohort progress view | Which students are stuck on which phoneme contrasts |
| 6.4 | Offline roster management | No cloud account required |

### Layer 7 — Trust and integrity

| # | Feature | Description |
|---|---|---|
| 7.1 | Liveness / replay detection on spoken answers | Distinguishes live speech from replayed or TTS-synthesised audio using spectral characteristics and room impulse response. Makes assessment scores mean something |
| 7.2 | **Signed on-device skill attestation** | "Functional Tamil, construction register, level 3" — cryptographically signed, verifiable by an employer. Converts a learning app into an employability credential |
| 7.3 | Explicit data policy surface | Plain-language, audio-narrated explanation of what is captured and what never leaves the phone |

### Layer 8 — NPU engineering (showcase explicitly)

| # | Feature | Description |
|---|---|---|
| 8.1 | **Model residency management** | ASR + TTS + MT + SLM cannot all be resident in RAM simultaneously on a mid-range device. Intelligent swap scheduling based on lesson phase is a real systems problem and worth showing |
| 8.2 | **Thermal-aware model tiering** | Read thermal headroom and degrade to a smaller model tier *before* throttling hits, rather than after. Directly showcases the sustained-workload story the sponsor is selling |
| 8.3 | Streaming pipeline with barge-in | Overlapped ASR/SLM/TTS so the roleplay feels live rather than turn-based |
| 8.4 | Quantisation-aware quality floor | Per-language minimum acceptable quantisation; some languages degrade faster than others at INT4 |
| 8.5 | Cold-start warm-up | Pre-load the acoustic model at app launch so the first utterance isn't slow |

**Total: 60+ discrete features.** See §11 for the six that actually get built in 30 hours.

---

## 6. Technical architecture

### 6.1 Design principles

1. **Nothing leaves the device during inference.** Not a preference — an architectural invariant.
2. **Every model has a fallback tier.** Flagship NPU path, mid-range quantised path, phrasebook path.
3. **Native for inference, Flutter for shell.** Flutter cannot reach the NPU. All inference lives in Kotlin behind platform channels.
4. **Streaming everywhere.** Batch inference feels broken in a conversational product.
5. **Degrade, never fail.** If a model can't load, fall back — never show an error to a user who may not read.

### 6.2 The on-device model stack

**Everything below is MIT-licensed, publicly downloadable, and requires no vendor access.** The entire runtime stack is two models totalling roughly 100MB.

| Role | Model | License | Where it runs | Size |
|---|---|---|---|---|
| ASR + forced alignment | **IndicConformer** (per-language) | MIT | On-device, NPU | ~120M → ~60 MB INT8 |
| Runtime TTS | **AI4Bharat FastPitch + HiFi-GAN V1** | MIT | On-device, NPU | ~25–40M → ~40 MB |
| Lesson audio | **Indic Parler-TTS** | MIT | **Build time, laptop** | 0 MB on device |
| Translation *(optional)* | IndicTrans2 distilled | MIT | On-device, or cut | ~200 MB |
| Transliteration *(optional)* | IndicXlit | MIT | On-device | small |

#### ASR — IndicConformer, and why not the most accurate model

AI4Bharat's Vistaar suite spans 59 benchmarks across 12 languages. Hindi subset word error rates, lower is better:

| System | FLEURS | CommonVoice | IndicTTS | Kathbath | Kathbath-Hard | Gramvaani |
|---|---|---|---|---|---|---|
| **IndicWhisper** | **11.40** | **15.00** | **7.60** | **10.30** | **12.00** | **26.80** |
| Sarvam Saarika | 16.00 | 18.21 | 15.37 | — | — | — |
| IndicWav2Vec | — | — | — | — | 16.20 | 42.10 |
| Nvidia Conformer-M | — | — | — | 14.00 | 15.60 | 41.30 |
| Google STT | 19.40 | 20.80 | 18.30 | 14.30 | 16.70 | 59.90 |

IndicWhisper has the lowest WER in 39 of 59 benchmarks, beats IndicWav2Vec in 45 of 59 and Google STT in 57 of 59, with an average 4.1 WER reduction.

**We do not use it.** IndicWhisper is a fine-tuned Whisper-medium at 769M parameters with an autoregressive encoder-decoder — six times IndicConformer's size and the most NPU-hostile decode pattern available.

**IndicConformer wins on architecture, not accuracy.** The per-language checkpoints are Conformer-Large encoders of ~120M parameters (17 conformer blocks, model dimension 512) with a **hybrid CTC-RNNT decoder**. That hybrid gives two decoders from one model:

- **CTC path** — non-autoregressive, streaming, and it emits the per-frame posteriors that forced alignment and GOP scoring require
- **RNNT path** — higher accuracy, for final transcription

One 120M model therefore serves both transcription and the pronunciation-scoring differentiator, with the NPU-friendly path being exactly the one the scorer depends on. IndicWhisper would give a better transcript and no posteriorgram at all.

A multilingual variant, `ai4bharat/indic-conformer-600m-multilingual`, covers all 22 scheduled languages, is MIT licensed, and already ships an **ONNX export** with community quantised variants. Use it only if multi-language support is needed at runtime; per-language is smaller and faster.

#### TTS — FastPitch + HiFi-GAN V1, per AI4Bharat's own benchmark

AI4Bharat evaluated FastPitch, Glow-TTS and VITS against HiFi-GAN V1, MB MelGAN and WaveGrad on Tamil and Hindi. Their finding: **VITS achieves the lowest MCD, but produces less intelligible speech than FastPitch — higher CER — with only average prosody.** They therefore selected FastPitch + HiFi-GAN V1 for Indian languages.

**Intelligibility is the metric that matters here.** If the learner mishears the model they learn the wrong pronunciation, and the GOP scorer then penalises them for reproducing what they were taught. Naturalness is cosmetic; CER is not. This is why Piper/VITS — the convenient deployment path — is the *second* choice, not the first.

The AI4Bharat release covers **16 Indian languages with both male and female voices**, MIT licensed, on the AI Kosh government registry. The acoustic model is small: six feed-forward Transformer blocks with multi-head self-attention and 1D convolution across the phoneme encoder and mel decoder, hidden size 256, 2 attention heads, conv kernels of 9 and 1 with 256/1024 and 1024/256 channels. Fully non-autoregressive, single forward pass.

Two incidental wins: male and female voices give listening variety and gendered-speech exposure, which matters pedagogically; and the model uses **Hybrid Segmentation** for phoneme alignment, so the TTS front-end and the GOP scorer can share one phoneme inventory instead of maintaining two.

#### Indic Parler-TTS — build time only, never runtime

Indic Parler-TTS covers **21 languages (20 Indic plus English)**, MIT licensed, trained on 1,806 hours. Quality is excellent. It cannot run on the phone.

Parler has three stages: a frozen FLAN-T5 text encoder, a transformer LM that **autoregressively generates audio tokens**, and a Descript Audio Codec that reconstructs the waveform. Parler-TTS Mini v1 — the base for Indic Parler — is **880M parameters**. DAC runs at 86Hz, so five seconds of speech is roughly 430 sequential decoder passes plus a codec decode plus the T5 encode. Realistically ~3× slower than real-time on this silicon, against an 800ms conversational budget. Autoregressive TTS also drops and repeats words, which is unrecoverable live on stage in a language the judges may speak natively.

**Use it as a build tool.** Batch-generate all authored lesson audio on a laptop or Colab before the event, varying the description prompt for speaker and speech rate. Downsample from DAC's 44.1kHz to 22kHz mono — indistinguishable through a phone speaker, less than half the asset size. This yields near-human lesson audio at zero runtime cost, and the description-conditioning delivers the adjustable-speech-rate feature (§5.6) for free.

#### Language coverage gaps — know these now

| Gap | Detail | Workaround |
|---|---|---|
| **Punjabi, Kashmiri** | Absent from Indic Parler's 21 (that list is 20 Indic + English) | IndicF5 has Punjabi among its 11 languages; or AI4Bharat Indic-TTS |
| **Bhojpuri** | Not a scheduled language; absent from every model here | Implement Ramesh's L1 as Hindi — the persona already has functional Hindi. Note as roadmap |

#### Schwa deletion — the G2P trap

GOP scoring wants phoneme-level alignment; IndicConformer's CTC head is character/subword level. In English that gap would be fatal. In Indic scripts it largely isn't — **abugida scripts are near-phonemic**, so character-level CTC alignment approximates phoneme alignment closely. That is a genuine linguistic advantage and belongs on the technical-depth slide.

The exception: **Hindi schwa deletion.** क-म-ल carries three inherent schwas but is pronounced *kamal*, not *kamala*. The G2P layer must handle this or alignment drifts. Well-studied with rule-based solutions, but do not assume one-to-one script mapping. Tamil is far better behaved here — another argument for Hindi→Tamil as the demo pair.

#### Models explicitly rejected

| Model | Why not |
|---|---|
| Sarvam Edge (Saaras / Mayura / Bulbul) | Enterprise/partner distribution, not openly downloadable. Also: Saarika underperforms IndicWhisper across every published benchmark |
| Sarvam-30B / 105B | MoE still requires all experts resident; ~60GB on disk, ~15GB at INT4. Open weights ≠ deployable |
| Sarvam-1 (2B) | Documented **text-completion base model**, explicitly not usable as a chat or instruction-following model without finetuning |
| IndicWhisper | Most accurate, but 769M autoregressive encoder-decoder. No posteriorgram for GOP |
| IndicF5 | Near-human across 11 languages, but flow-matching with many denoising steps and reference audio required |
| Indri 124M/350M | Hindi + English only, GPT-2 based, autoregressive |
| On-device SLM (Gemma/Qwen/Llama) | See §6.7 — likely unnecessary; pre-generated branching dialogue is more reliable |

### 6.3 Inference runtime

| Layer | Choice | Notes |
|---|---|---|
| NPU target | Qualcomm Hexagon (Snapdragon 8 Elite Gen 5) | The iQOO 15's silicon |
| Runtime options | ONNX Runtime + QNN execution provider (primary); Qualcomm AI Hub precompiled models | Both target models are non-autoregressive and map cleanly to the NPU |
| Quantisation | INT8 baseline | Validate per language, not globally |
| Export burden | **FastPitch and HiFi-GAN are two separate ONNX graphs** | AI4Bharat ships via the Coqui TTS framework, which is not mobile-friendly. You export both stages yourself |
| **Verification requirement** | **Prove NPU execution, not CPU fallback** | Many "on-device" demos silently run on CPU. Instrument this and show the evidence in the demo |

> **Sequencing decision.** FastPitch + HiFi-GAN is the better model; Piper/VITS is the easier path with a proven Android integration. Attempt the FastPitch ONNX export in Phase A. **If it is still fighting you by Wednesday, fall back to Piper and accept the CER penalty.** Do not discover this at hour six.

### 6.4 Model residency and memory management

With the SLM and MT cut (§6.7), residency becomes almost trivial — this is the main practical dividend of the AI4Bharat stack.

| Lesson phase | Resident models | Peak |
|---|---|---|
| Listening / roleplay | ASR + TTS | ~100 MB |
| Pronunciation scoring | ASR only (CTC head) | ~60 MB |
| Camera OCR lesson | OCR + TTS (+ MT if retained) | ~100–300 MB |
| Reading fluency assessment | ASR only | ~60 MB |

Both models fit co-resident on a 3GB device with room to spare. Warm-load at app launch; never show a loading spinner longer than 400ms.

### 6.5 Thermal-aware tiering

Android exposes `PowerManager.getThermalHeadroom(forecastSeconds)` (API 30+), returning a non-negative float where 1.0 is the severe-throttling threshold, with an optional forward forecast. It tracks slow-moving sensors, so **there is no benefit to polling faster than about once per second, and calling it significantly faster returns NaN.** Also available: `getCurrentThermalStatus()`, `addThermalStatusListener()`, and `PerformanceHintManager`.

**Policy:**

| Thermal headroom | Action |
|---|---|
| < 0.7 | Full stack, all features |
| 0.7 – 0.85 | Switch runtime TTS to pre-rendered audio; reduce ASR beam width |
| 0.85 – 0.95 | Disable ambient mining; CTC-only decode (drop RNNT) |
| > 0.95 | Cached-content phrasebook mode |

Degrading *before* throttling rather than after is the point. This directly demonstrates the sustained-workload capability the sponsor's hardware pitch is built on.

### 6.6 Core pipelines

**Roleplay conversation loop:**
```
Mic → VAD → Streaming ASR, CTC decode (NPU) → partial transcript
   → intent match against dialogue-tree branches (local, no model)
   → persona turn = pre-rendered Parler audio, or FastPitch if novel
   → audio out
   → [parallel] RNNT decode → forced alignment → GOP scoring
   → feedback overlay
Target end-to-end latency: < 800ms
Barge-in: user speech cancels playback mid-stream
```

**Camera OCR lesson:**
```
Camera frame → script detection → text region proposal
   → Indic OCR (NPU) → raw L2 text
   → MT (NPU) → L1 gloss
   → vocabulary diff against user's known-word set
   → SLM generates 5-item micro-lesson from unknown words
   → TTS pronounces each
```

**Ambient vocabulary mining:**
```
[opt-in, visible indicator, foreground service]
Mic ring buffer (30s) → VAD gate → language ID
   → if L2 detected: streaming ASR (NPU)
   → token extraction → discard audio immediately
   → diff against known-word set → queue for tomorrow's lesson
NOTHING is stored except extracted lemmas. No audio, ever.
```

**Pronunciation scoring:**
```
Target text → G2P → expected phoneme sequence
User audio → acoustic model → posteriorgram
   → forced alignment (CTC) → per-phoneme time boundaries
   → Goodness-of-Pronunciation score per phoneme
   → compare against L1-interference confusion set
   → highlight the specific phoneme, name the substitution
```

---

### 6.7 Two models you probably do not need

**Translation.** IndicTrans2 is only required for camera-OCR glossing and ambient-mining lookups. For the demo, pre-bundle translations for all lesson content and load IndicTrans2 only if time allows. Saves ~200MB of RAM and one conversion pipeline.

**The small language model.** More consequential. The instinct is to put a 1–2B SLM in the roleplay loop for dynamic dialogue. Resist it:

- A 1.5B model producing Tamil is *mediocre at Tamil*. Judges who speak the language will notice.
- It is the largest, slowest, hottest component and the hardest to get onto the NPU.
- Autoregressive decode with a KV cache is the pattern most likely to fail during Green Light.

**Instead: generate branching dialogue trees offline with a large model before the event, and execute them locally as a state machine.** Content quality goes up, latency goes to near zero, and three failure modes disappear. In a three-minute demo nobody can distinguish a well-authored branching conversation from live generation.

The on-device AI story is already carried by streaming ASR, forced alignment, GOP scoring and neural TTS — all running on the NPU. That is a stronger and more defensible claim than a wobbly SLM.

**Cutting both takes the footprint from ~2GB to ~100MB.**

---

## 7. Tech stack

| Layer | Technology | Rationale |
|---|---|---|
| **UI shell** | Flutter (Dart) | Fastest route to a polished, finished-looking app. Directly serves the 30% end-product-quality criterion |
| **State management** | Riverpod | Familiar, testable |
| **Local storage** | Isar or Hive | Fast local persistence, no server |
| **Inference bridge** | Kotlin, via Flutter platform channels | **Flutter cannot reach the NPU.** All model work is native |
| **Model runtime** | ONNX Runtime + QNN EP | Both models are non-autoregressive and map cleanly to the NPU |
| **Audio capture** | Android AudioRecord, 16kHz mono | Direct control needed for VAD and streaming |
| **Audio playback** | Android AudioTrack | Needed for barge-in cancellation |
| **Camera** | CameraX | Frame analysis pipeline for OCR |
| **Sensors** | Android SensorManager, ActivityRecognition API | Commute mode, context switching |
| **NFC** | Android HCE with custom non-payment AIDs | Peer practice. Payment-category AIDs are restricted to the Wallet role holder on Android 15+; custom AIDs are unrestricted |
| **Thermal** | PowerManager thermal APIs | Tiering policy |
| **Local networking** | Wi-Fi Direct / Wi-Fi Aware | Teacher-to-student, peer practice |
| **Crypto** | Android Keystore / StrongBox, Ed25519 | Signed skill attestations |
| **Laptop bridge** | vivo Office Kit (Remote PC, Free Transfer, Screen Mirroring) | Model conversion offloaded to laptop; teacher report export |
| **Build/CI** | Gradle, local only | No cloud CI during Red Light |

### Deliberately not used

- No backend server. No database. No cloud API calls of any kind during inference.
- No Firebase, no analytics SDK that phones home.
- No web view. Native rendering only.

This "not used" list is itself a pitch asset. It is unusual and it is the point.

---

## 8. Content and data architecture

### 8.1 The content unit

The atom is not a word. It is a **situation**.

```
Situation
  ├─ trigger context (location type, occupation, urgency)
  ├─ 5–12 target utterances
  ├─ expected interlocutor responses (for roleplay branching)
  ├─ register variants (peer / employer / elder)
  ├─ phoneme focus set (which contrasts this situation stresses)
  └─ failure modes (what happens if you say it wrong)
```

Example: *"Asking your supervisor to correct an underpaid wage"* is a situation. *"Money vocabulary"* is not.

### 8.2 Curriculum organisation

```
Domain (occupation)
  └─ Track (e.g. "Week 1 survival", "Wage & rights", "Medical")
       └─ Unit (thematic cluster)
            └─ Situation
                 └─ Exercise (listen / speak / discriminate / roleplay / read)
```

### 8.3 Content generation strategy

**Pre-generated offline (before the event):** seed situations for 3 occupations × 3 language pairs, generated with a large model, human-reviewed for register accuracy. Ship as bundled JSON.

**Generated on-device (live):** personalised variants, distractors, and camera/ambient-derived micro-lessons.

This split matters. Fully on-device generation from a 1–2B model will produce awkward L2 output. Pre-generating the backbone and letting the SLM personalise around it is the honest engineering answer.

### 8.4 The user model

Stored entirely locally:

| Field | Purpose |
|---|---|
| L1, L2, dialect | Selects interference set and content variant |
| Occupation / domain | Curriculum selection |
| Literacy level | Determines whether text is shown at all |
| Known-word set | Diff target for ambient and camera mining |
| Phoneme error profile | Drives drill generation |
| Hesitation history per item | Drives spaced repetition scheduling |
| Session context history | Location types, times of day, commute pattern |

### 8.5 Language pair prioritisation

Build these three first — they map to the largest real migration corridors:

| L1 | L2 | Corridor |
|---|---|---|
| Hindi/Bhojpuri | Tamil | Bihar/UP → Tamil Nadu |
| Odia | Malayalam | Odisha → Kerala |
| Hindi | Kannada | UP/Bihar → Karnataka |

Demo with pair 1. It is the largest, the most linguistically distant (Indo-Aryan → Dravidian, different script, different phonology), and therefore the most impressive if it works.

---

## 9. Implementation approach

*No code — sequence and reasoning only.*

### Phase A — Foundations (pre-event, this week)

*Ordered by risk. Do not reorder — each step gates the next.*

1. **Convert IndicConformer (per-language, demo pair) to ONNX** and run it on an Android device you own. Verify **both** CTC and RNNT decode paths work — the CTC posteriors are your differentiator, not a nice-to-have.
2. **Prove NPU execution.** Instrument the runtime and confirm ops are not silently falling back to CPU. Do not proceed until this is verified.
3. **Export FastPitch + HiFi-GAN V1 to ONNX.** Two graphs, and AI4Bharat ships via Coqui, which is not mobile-friendly. **Hard deadline: Wednesday.** If it is still fighting you then, switch to Piper and move on.
4. **Build the G2P layer** for the demo language, including Hindi schwa-deletion rules if Hindi is the L1. Alignment quality depends entirely on this.
5. **Pre-render lesson audio with Indic Parler-TTS** on laptop or Colab. Vary the description prompt for speaker and speech rate. Downsample 44.1kHz → 22kHz mono.
6. **Pre-generate branching dialogue trees** with a large model for one occupation and one language pair. Ship as bundled JSON.
7. **Assemble the L1-interference confusion set** for the demo pair.
8. Build and test the Flutter ↔ Kotlin platform channel skeleton with a dummy model.
9. Install Office Kit; practise Remote PC, screen mirroring, and shared clipboard until it's muscle memory.
10. **Record a prototype clip** — offline ASR working on your device — for the submission form's optional URL field.

### Phase B — Hour 0–6 (Red Light: phone only)

1. Scaffold the Flutter shell from a pre-planned structure. Navigation, theme, placeholder screens.
2. Wire the platform channel to the ASR model. Get one utterance transcribing on-device.
3. Get TTS speaking one sentence.
4. Prove the loop: speak → transcribe → respond → speak back. Even hard-coded. **This is the make-or-break milestone.**

### Phase C — Hour 6–14

1. Build the roleplay loop properly: ASR → intent match against the pre-generated dialogue tree → pre-rendered audio playback.
2. Implement streaming and barge-in.
3. Build the pronunciation scoring path: G2P → forced alignment → per-phoneme GOP.
4. Load the L1-interference confusion set for the demo language pair.

### Phase D — Hour 14–22 (Green Light window: use the laptop)

1. Model conversion, quantisation experiments, and profiling on the laptop via Office Kit Remote PC.
2. Camera OCR pipeline.
3. Ambient mining service with a visible, honest indicator.
4. Zero-literacy voice navigation shell.

### Phase E — Hour 22–28

1. Teacher batch assessment + Office Kit export.
2. Thermal tiering policy.
3. Visual polish. This is where the Flutter investment repays.

### Phase F — Hour 28–30

1. **Freeze code.** No new features.
2. Rehearse the demo six times end-to-end, on the actual device, in airplane mode.
3. Prepare fallback recordings in case live inference fails on stage.

---

## 10. Hackathon alignment

### 10.1 Scoring rubric mapping

| Criterion | Weight | Judged by | How Boli scores |
|---|---|---|---|
| End product quality | 30% | Jury | Flutter shell polish; a finished-feeling app beats a technically deeper prototype that looks unfinished |
| Novelty and impact | 20% | Jury | Migrant framing + the "impossible in the cloud" argument. **Not** "language app" |
| Creative phone use | 15% | **Device data** | Camera OCR, ambient mic, motion sensors, NFC peer practice — all logged by HackTracker, unfakeable |
| Technical depth | 15% | Jury | L1-interference pronunciation scoring, forced alignment, NPU residency management |
| Office Kit usage | 10% | **Device data** | Model conversion via Remote PC; teacher report export |
| Demo and presentation | 10% | Jury | Airplane-mode moment |

**25% of the score is device telemetry, not pitch.** The phone must genuinely be used. This product does that by construction.

### 10.2 Red Light / Green Light plan

55% of the 30 hours is Red Light — phone-primary building. Plan accordingly:

| Phase | Work |
|---|---|
| **Red Light** | Flutter UI construction, content authoring, testing on-device, running the app, recording demo footage, Kotlin edits via Office Kit Remote PC from the phone |
| **Green Light** | Model conversion, quantisation, profiling, anything requiring heavy compute |

**Open question to confirm with organisers:** whether Red Light permits driving the laptop via Office Kit Remote PC (phone as input surface) or requires the laptop to be closed entirely. This materially changes the plan. Email sameera@reskilll.com before the event.

### 10.3 Office Kit integration

Office Kit provides Free Transfer (file access from PC), Screen Mirroring (with Super Clipboard), Remote PC (control the laptop from the phone, with virtual mouse/touchpad and Privacy Mode), Task Handoff (notifications and screenshots to PC), and Notes Sync.

**Two honest uses:**
1. **Remote PC** dispatches model conversion and quantisation to the laptop while the phone stays the control surface.
2. **Free Transfer** exports teacher assessment reports from phone to laptop.

Both are real workflow needs, not theatre. That matters because the 10% is telemetry-scored.

### 10.4 Device specifics

The loaner is an **iQOO 15**: Snapdragon 8 Elite Gen 5, OriginOS 6 on Android 16, NFC, ultrasonic in-display fingerprint sensor, IR blaster, 50MP periscope camera, 7000mAh battery, ~14,000mm² vapour chamber, Wi-Fi 7, Bluetooth 6.0.

Android 16 means the newest NFC stack — including Observe Mode and polling-loop APIs introduced in Android 15 — is available if peer-practice features go deeper.

---

## 11. 30-hour build plan and cut list

### Build exactly six features

| Priority | Feature | Why |
|---|---|---|
| 1 | **Offline roleplay dialogue loop** | The demo. If this works, nothing else is essential |
| 2 | **Phoneme-level pronunciation feedback with L1 targeting** | The technical depth (15%) |
| 3 | **Camera OCR → micro-lesson** | The creative-phone-use criterion (15%) |
| 4 | **Ambient vocabulary mining** | The moat argument (novelty, 20%) |
| 5 | **Zero-literacy voice-first shell** | The impact story, and forces the right architecture |
| 6 | **Office Kit teacher export** | Free 10% |

### Cut list, in order

Cut from the bottom when you fall behind. Decide this now, not at 3am.

1. Front-camera articulation feedback — cut first, always
2. NFC peer practice — impressive but a whole subsystem
3. Handwriting practice — cut unless the demo needs script
4. Dialect selection — ship one dialect, mention the rest
5. Leagues and social features — nobody scores this
6. Thermal tiering — becomes a slide instead of a feature
7. Teacher batch assessment — degrade to single-student
8. Prosody scoring — degrade to phoneme-only

### Do not cut

- **The airplane-mode demonstration.** Three seconds, disproportionate impact.
- **Visual polish on the two screens the judges will actually see.** A rough-looking app loses the 30% regardless of what's underneath.

---

## 12. Risks and mitigations

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| R1 | **FastPitch/HiFi-GAN ONNX export fails** | High | Two graphs, Coqui-based release. **Wednesday deadline**, then fall back to Piper/VITS and accept higher CER |
| R2 | **Runtime TTS unusable entirely** | Medium | Pre-render lesson audio *and* a bank of a few thousand common words with Parler. Runtime synthesis then only covers genuinely novel vocabulary — cuttable with almost no visible loss on stage |
| R3 | **Models silently run on CPU, not NPU** | High | Instrument and verify in Phase A. Do not proceed until proven |
| R4 | **CTC alignment too coarse for GOP** | Medium | Indic abugida scripts are near-phonemic, so character-level CTC approximates phoneme alignment. Hindi schwa deletion is the exception — handle in G2P |
| R5 | **Memory exhaustion** | Low | Downgraded: the two-model stack is ~100MB and fits co-resident on a 3GB device |
| R6 | **Language gap discovered mid-build** | Medium | Punjabi and Kashmiri absent from Indic Parler; Bhojpuri absent everywhere. Ramesh's L1 ships as Hindi. Confirm coverage before authoring content |
| R7 | **Judged as "just another Duolingo clone"** | High | The migrant framing and the cloud-impossibility argument are the entire defence. Lead with them |
| R8 | **Red Light rules block laptop toolchain** | Medium | Confirm with organisers pre-event. Have a phone-only editing path ready |
| R9 | **Live demo fails on stage** | Medium | Pre-recorded fallback footage. Rehearse six times |
| R10 | **Thermal throttling during the demo** | Low | 7000mAh and a large vapour chamber help, but warm-up before going on stage rather than starting cold |
| R11 | **Ambient mining reads as surveillance** | Medium | Strict opt-in, visible indicator, audio discarded immediately, explain it plainly in the demo before someone asks |
| R12 | **Scope creep across 60 features** | High | The six-feature list in §11 is a commitment, not a suggestion |

---

## 13. Demo script

**Three minutes. One narrative. No feature tour.**

| Time | Beat |
|---|---|
| 0:00 | "This is Ramesh. He's 34, from Muzaffarpur. He has three weeks to speak enough Tamil to keep his job in Chennai." |
| 0:15 | **Put the phone in airplane mode. Show the screen.** "Everything from here runs on this device. No internet. No data cost. No audio leaves the phone." |
| 0:25 | Open the app by voice. No tapping, no reading. "Ramesh can't read Tamil. He can barely read Hindi." |
| 0:45 | **Roleplay.** Speak to the app as a site supervisor. It responds in Tamil. Interrupt it mid-sentence — it stops and listens. |
| 1:20 | **Pronunciation feedback.** Deliberately mispronounce a retroflex. The app says: *your ட came out as a த* — and shows exactly where in the word. "It knows this because Ramesh's first language is Bhojpuri, and Bhojpuri speakers make this specific error." |
| 1:50 | **Camera.** Point at a printed Tamil signboard. Instant translation, then a five-word lesson generated from it. |
| 2:15 | **Ambient mining.** "For the last three minutes the phone has been listening to this room. Here are the Tamil words it heard that Ramesh doesn't know yet. Tomorrow's lesson is already built. This feature cannot exist as a cloud product." |
| 2:40 | Close on the numbers: 140 million migrant workers, 30% of women in this group illiterate, zero products built for them. |
| 3:00 | "Still in airplane mode." |

**Rules:** no menus, no settings screens, no "and we also have." One story.

---

## 14. Beyond the hackathon

### Distribution

- **NGO and union partnerships.** Migrant worker welfare organisations already have trust and reach where advertising doesn't.
- **Employer distribution.** Construction firms and hospital chains have a direct interest in workers who can communicate. B2B2C.
- **Government alignment.** e-Shram registration touchpoints; Bhashini ecosystem alignment; NEP foundational-literacy framing for the school variant.

### Business model options

| Model | Notes |
|---|---|
| Free for individuals, always | Zero marginal inference cost makes this sustainable — the economics of on-device |
| Employer-paid onboarding packages | Company pays for domain-specific curricula and progress reporting |
| Institutional licence for schools/NGOs | Teacher dashboard, batch assessment, reporting |
| Verified skill attestation | Employers pay to verify credentials |

### Roadmap

**Phase 1 (0–3 months):** three language pairs, two occupations, core loop. Field-test with 50 workers.
**Phase 2 (3–9 months):** 10 pairs, six occupations, teacher tooling, dialect variants.
**Phase 3 (9–18 months):** all 22 scheduled languages via IndicTrans2 coverage, employer portal, attestation infrastructure.

### Research contributions worth publishing

- L1-interference confusion sets for Indian language pairs — a genuinely under-documented area
- On-device GOP scoring performance for Indic phonology
- Ambient vocabulary acquisition as a learning modality

---

## 15. Appendices

### Appendix A — Model reference

| Model | Params | Size | License | Languages | Source |
|---|---|---|---|---|---|
**In the stack:**

| Model | Params | Size | License | Languages | Role |
|---|---|---|---|---|---|
| **IndicConformer** (per-language) | ~120M | ~60 MB INT8 | MIT | 1 per checkpoint | On-device ASR + CTC alignment |
| **IndicConformer-600M-Multi** | 600M | ONNX + quantised variants | MIT | 22 scheduled | Optional multilingual runtime |
| **FastPitch + HiFi-GAN V1** | ~25–40M | ~40 MB | MIT | 16, male + female | On-device runtime TTS |
| **Indic Parler-TTS** | 880M base | n/a on device | MIT | 21 (20 Indic + En) | Build-time audio rendering |
| IndicTrans2 (distilled) | 1.1B full | ~200 MB | MIT | 22 scheduled | Optional on-device MT |
| IndicXlit | small | small | MIT | Multiple | Optional transliteration |

**Rejected, with reasons:**

| Model | Reason |
|---|---|
| Sarvam Edge (Saaras/Mayura/Bulbul) | Enterprise distribution; Saarika also underperforms IndicWhisper on published benchmarks |
| Sarvam-30B / 105B | ~60GB, ~15GB at INT4; MoE needs all experts resident |
| Sarvam-1 (2B) | Text-completion base model, not instruction-following |
| IndicWhisper | Most accurate, but 769M autoregressive; no posteriorgram |
| IndicF5 | Flow-matching, many denoising steps, needs reference audio |
| Indri 124M/350M | Hindi + English only, autoregressive GPT-2 |
| Piper/VITS | **Fallback only** — AI4Bharat's benchmark shows higher CER than FastPitch for Indic |

### Appendix B — Key statistics for the pitch

| Statistic | Value |
|---|---|
| Internal migrants (Census 2011) | 450 million (37% of population) |
| Inter-state migrant workers | 41.4 million |
| Estimated migrant workers today | ~140 million |
| Circular migrants | >200 million |
| Illiteracy among inter-state migrants 15–59 | 10–15% men, ~30% women |
| Class 3 students who cannot read Class 2 text | 76.6% |
| Class 3 students who cannot do simple arithmetic | 66.3% |
| Smartphone access, ages 14–16 | 89% |
| Scheduled Indian languages | 22 |

### Appendix C — Pre-event checklist

- [ ] Email organisers re: Red Light / Office Kit Remote PC rules
- [ ] Convert IndicConformer to ONNX; verify **both** CTC and RNNT decode
- [ ] Convert fallback ASR to ONNX, run on Android
- [ ] Build G2P layer incl. Hindi schwa-deletion rules
- [ ] **Export FastPitch + HiFi-GAN to ONNX — Wednesday deadline, then switch to Piper**
- [ ] Verify NPU execution, not CPU fallback
- [ ] Install Office Kit; practise Remote PC and clipboard
- [ ] Pre-render lesson audio with Indic Parler (22kHz mono)
- [ ] Pre-generate branching dialogue trees: 1 occupation × 1 language pair
- [ ] Build Flutter ↔ Kotlin channel skeleton
- [ ] Assemble L1-interference confusion set for Bhojpuri→Tamil
- [ ] Record a prototype clip for the submission form's optional URL field
- [ ] Fix "Android proficiency" and "LLM proficiency" fields on the submission form
- [ ] Write Prior Builds field with specific hackathon wins

### Appendix D — Glossary

| Term | Meaning |
|---|---|
| **ASR** | Automatic Speech Recognition — speech to text |
| **TTS** | Text-to-Speech — text to spoken audio |
| **MT / NMT** | (Neural) Machine Translation |
| **SLM** | Small Language Model — 1–3B parameters, phone-deployable |
| **NPU** | Neural Processing Unit — dedicated AI accelerator (Hexagon on Snapdragon) |
| **G2P** | Grapheme-to-Phoneme — spelling to pronunciation |
| **GOP** | Goodness of Pronunciation — per-phoneme scoring metric |
| **Forced alignment** | Mapping audio to known text at phoneme-level timestamps |
| **CTC** | Connectionist Temporal Classification — alignment-free sequence loss |
| **VAD** | Voice Activity Detection |
| **f0** | Fundamental frequency — perceived pitch |
| **L1 / L2** | First (native) language / second (target) language |
| **HCE** | Host Card Emulation — software NFC card emulation on Android |
| **DTW** | Dynamic Time Warping — comparing time series of different lengths |
| **WCPM** | Words Correct Per Minute — oral reading fluency metric |
| **Retroflex** | Consonants with tongue curled back (ट ठ ड ढ ण) — distinctive to Indian languages |
| **Aspiration** | The puff of air distinguishing क from ख |

### Appendix E — Sources

**Hackathon**
- iqoo.reskilll.com — official event portal (problem statements behind login)
- reskilll.com/blogs — City Battles announcement, strategy guide, on-device AI guide
- pc.vivoglobal.com — Office Kit download

**Models**
- huggingface.co/ai4bharat/indic-conformer-600m-multilingual — ASR, MIT, ONNX
- huggingface.co/ai4bharat/indic-parler-tts — build-time TTS
- github.com/AI4Bharat/Indic-TTS — FastPitch + HiFi-GAN
- arxiv.org/pdf/2211.09536 — *Towards Building TTS for the Next Billion Users* (the FastPitch-over-VITS finding)
- arxiv.org/pdf/2305.15386 — Vistaar ASR benchmark
- github.com/AI4Bharat/vistaar — Vistaar WER tables
- ai4bharat.iitm.ac.in — AI4Bharat model index
- github.com/AI4Bharat/IndicTrans2 — IndicTrans2 repository
- arxiv.org/pdf/2305.16307 — IndicTrans2 paper
- aikosh.indiaai.gov.in — IndiaAI model repository

**Data**
- asercentre.org — ASER 2024 report
- Census of India 2011 — migration tables
- World Bank / IHDS — circular migration estimates
- Economic Survey 2017, 2023–24 — migration estimates

**Platform**
- developer.android.com — thermal APIs, NFC/HCE, CameraX, sensors

---

*Document version 2.0 — model stack revised to the verified AI4Bharat / IIT Madras open stack. Prepared for iQOO Hackathon 2026 City Battles, Pune.*
