# Boli

**Functional Indian languages, offline, for people who move for work.**

On-device speech recognition and language learning for Indian regional languages. No network calls. No data cost. No reading required.

Built for **iQOO Hackathon 2026 · City Battles** — Pune.

---

## The problem

India's internal migration is one of the largest sustained human movements in the world, and no consumer software is built for it.

| | |
|---|---|
| Internal migrants (Census 2011) | **450 million** — 37% of the population |
| Inter-state migrant workers | 41.4 million |
| Estimated migrant workers today | ~140 million |
| Illiteracy among inter-state migrants aged 15–59 | **10–15% of men, ~30% of women** |
| Class 3 students who cannot read a Class 2 text | **76.6%** |
| Smartphone access, ages 14–16 | 89% |

A construction worker from Bihar arrives in Chennai with three weeks to become functional in Tamil. He has a ₹8,000 Android phone, a 1.5GB daily data pack mostly spent on video calls home, and a 90-minute commute with his hands occupied. He cannot read Tamil script. He may not read fluently in any language.

**Every existing product fails him:**

- Language apps pivot every translation through English, which destroys register and idiom
- They require constant connectivity he doesn't have
- They assume literacy he doesn't have
- They teach tourist vocabulary, not the forty phrases you need to not get fired in week one

---

## The approach

Three structural decisions, which no existing product makes together:

**1. Indian language to Indian language, directly.** No English pivot. Bhojpuri to Tamil, not Bhojpuri → English → Tamil.

**2. Fully offline.** Every model runs on the device. Zero data cost. Works in airplane mode, on a construction site, in a village.

**3. Voice-first, literacy-optional.** The entire app is operable without reading anything.

### Why on-device is not an optimisation

The flagship feature — **ambient vocabulary mining**, where the phone passively hears the language around you and builds tomorrow's lesson from it — is *unshippable* by anyone streaming audio to a server. Continuous ambient microphone capture sent to a cloud endpoint is a privacy catastrophe and a regulatory non-starter.

Running locally is what makes the feature legal to exist. That is a moat, not a rubric hack.

---

## Model stack

Everything MIT-licensed, publicly downloadable, no vendor access required. **~100MB on device.**

| Role | Model | License | Runs |
|---|---|---|---|
| ASR + forced alignment | [IndicConformer](https://huggingface.co/ai4bharat/indicconformer_stt_mr_hybrid_ctc_rnnt_large) 120M per-language (AI4Bharat, IIT Madras) | MIT | On-device |
| Runtime TTS | FastPitch + HiFi-GAN V1 (AI4Bharat) | MIT | On-device |
| Lesson audio | [Indic Parler-TTS](https://huggingface.co/ai4bharat/indic-parler-tts) | MIT | Build time |
| Translation *(optional)* | IndicTrans2 | MIT | On-device |

### Why IndicConformer over IndicWhisper

IndicWhisper is more accurate — lowest WER in 39 of 59 Vistaar benchmarks. We don't use it. It's a 769M-parameter autoregressive encoder-decoder: six times larger and the most NPU-hostile decode pattern available.

IndicConformer's per-language checkpoints are ~120M parameters with a **hybrid CTC-RNNT decoder**, which gives two decoders from one model:

- **CTC path** — non-autoregressive, streaming, and it emits the per-frame posteriors that forced alignment and pronunciation scoring require
- **RNNT path** — higher accuracy, for final transcription

One model serves both transcription and phoneme-level pronunciation feedback. IndicWhisper would give a better transcript and no posteriorgram at all.

### Why FastPitch over VITS

AI4Bharat's own evaluation ([arXiv:2211.09536](https://arxiv.org/pdf/2211.09536)) compared FastPitch, Glow-TTS and VITS on Tamil and Hindi. VITS scored lowest MCD but produced **less intelligible speech** — higher CER — with only average prosody. They selected FastPitch + HiFi-GAN V1.

For a language-learning app, intelligibility is the metric that matters. If the learner mishears the model, they learn the wrong pronunciation and the scorer then penalises them for reproducing what they were taught.

---

## What this repository contains

This is a **prototype**, built to validate a single claim: *IndicConformer runs fully offline on an Android phone.*

It is not the product. It is the load-bearing technical assumption underneath the product, tested first.

### Status

| | Status |
|---|---|
| Repository, architecture, model selection | ✅ |
| IndicConformer transcription, Marathi | ✅ |
| ONNX Runtime inference path, end to end | ✅ |
| Android app, on-device inference | 🔄 |
| Live microphone, airplane mode verified | 🔄 |

*Updated during the build.*

**On-device footprint: 137.8 MB** — `model.int8.onnx` (137.7 MB) plus the
log-mel front-end graph `nemo80.onnx` (0.09 MB). Laptop CPU real-time factor
is 0.15, measured on 14–22 second Marathi clips.

The pipeline is two ONNX Runtime sessions and no hand-written signal processing:

```
PCM float32 @16kHz
   -> nemo80.onnx      log-mel front-end   -> features [B,80,T]
   -> model.int8.onnx  IndicConformer CTC  -> logprobs [B,T,257]
   -> greedy CTC decode
```

This matters more than it looks. The usual failure mode when porting a NeMo ASR
model to a phone is reimplementing its mel front-end by hand and getting one
parameter wrong — slaney vs HTK mel scaling, say — which yields no error, just
confident nonsense. Shipping the front-end *as an ONNX graph* means the phone
runs bit-for-bit the same computation as the laptop reference. See
[`docs/onnx-signature.md`](docs/onnx-signature.md).

### What is deliberately not here

No UI framework, no lessons, no gamification, no TTS, no pronunciation scoring. Those are the product. This repo tests whether the product is possible.

---

## Roadmap

**Phase 1** — three language pairs mapped to the largest migration corridors (Hindi/Bhojpuri→Tamil, Odia→Malayalam, Hindi→Kannada), two occupations, core loop. Field-test with 50 workers.

**Phase 2** — 10 pairs, six occupations, teacher batch-assessment tooling, dialect variants.

**Phase 3** — all 22 scheduled languages, employer portal, signed skill attestations verifiable by employers.

### Known coverage gaps

- **Punjabi and Kashmiri** are absent from Indic Parler-TTS
- **Bhojpuri** is not a scheduled language and is absent from every model in this stack — L1 currently ships as Hindi

---

## Credits

Models by [**AI4Bharat**](https://ai4bharat.iitm.ac.in), IIT Madras — IndicConformer, Indic Parler-TTS, Indic-TTS, IndicTrans2. Their ASR work spans 300,000 hours of raw speech collected across 400+ districts.

Notably, AI4Bharat lists *"ensure their functionality in offline settings"* among their **forward-looking** goals. India's leading Indic speech lab considers on-device operation unsolved. This repository is a small step at that edge.

---

## License

Code: MIT. Models retain their respective licenses (all MIT).
