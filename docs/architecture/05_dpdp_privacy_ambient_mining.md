# 05 — DPDP Compliance & Ambient Vocabulary Mining

## 1. Regulatory Context (India's DPDP Act 2023)
Continuous acoustic monitoring streaming audio to a remote server constitutes unauthorized surveillance, violates user consent boundaries, and breaches the Digital Personal Data Protection (DPDP) Act.

## 2. The Seedhebol On-Device Compliance Invariant
Seedhebol proves that ambient language acquisition is only legal and defensible when executed **entirely on-device with zero raw audio persistence**:

1. **Volatile Circular Memory Buffer**: Audio samples reside in an isolated 30-second RAM circular buffer (`DirectByteBuffer`).
2. **Local Token Extraction**: Voice Activity Detection (VAD) gates execution; if regional L2 speech is identified, on-device ASR extracts lemma tokens.
3. **Immediate Overwrite / Zeroization**: The audio memory segment is zeroed immediately following token emission.
4. **No Waveform Storage**: No raw PCM, FLAC, MP3, or acoustic embeddings are ever written to disk or transmitted off the device.
5. **Transparency & Consent**: The user must explicitly opt in. An active foreground service notification with a glowing mic icon is always visible when ambient mining is active.
