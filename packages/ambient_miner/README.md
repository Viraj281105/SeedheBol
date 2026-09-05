# `packages/ambient_miner` — DPDP-Compliant Ephemeral Ambient Token Miner

> **Privacy-Preserving Lexical Acquisition**: Passively listens to ambient public conversations on-device, extracting unknown target language words for future lessons with zero raw audio persistence.

---

## 🔒 Privacy & Legal Compliance (DPDP Act 2023)

- **Strict Volatile Isolation**: Audio captured from the device microphone is retained solely in an in-memory 30-second circular buffer (`DirectByteBuffer`).
- **Immediate Zeroization**: As soon as Voice Activity Detection (VAD) and ASR extraction conclude, the audio buffer is wiped.
- **Zero Raw Storage**: Raw PCM waveforms, audio spectrograms, and speaker embeddings are **never written to disk or transmitted over the network**.
- **Transparency Indicator**: An Android Foreground Service displays an explicit visual indicator whenever ambient monitoring is active.

---

## 🔄 Extraction Pipeline

```
[Ambient Audio] ──► [30s RAM Ring Buffer] ──► [Silero VAD Filter]
                                                    │ (Speech Detected)
                                                    ▼
                                     [Streaming IndicConformer ASR]
                                                    │
                                                    ▼
                                         [Raw Transcript Tokens]
                                                    │
                                                    ▼
                                    [Morphological Stemming / Lemmatization]
                                                    │
                                                    ▼
                                    [Diff against Local User Known-Words DB]
                                                    │
                                                    ▼
                                     [Queue New Unknown Lemmas for Commute]
```
