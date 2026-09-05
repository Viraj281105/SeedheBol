# `packages/npu_engine` — On-Device NPU Acceleration & ONNX Runtime Bridge

> **Hardware Acceleration Engine**: Interfaces with Qualcomm QNN Execution Provider on Snapdragon 8 Elite Hexagon NPU for streaming ASR, forced alignment, and TTS vocoding.

---

## 🎯 Scope & Responsibilities

- **ONNX Runtime QNN Session Management**: Initializes and manages INT8 quantized execution sessions targeting the Qualcomm Hexagon NPU.
- **Hybrid CTC-RNNT Acoustic Decoder**:
  - **CTC Head**: Emits per-frame posterior distributions $P(y_t = k | X)$ used for Goodness-of-Pronunciation (GOP) forced alignment.
  - **RNNT Head**: Emits streaming lexical hypotheses for conversational roleplay.
- **FastPitch + HiFi-GAN Vocoder Engine**: Executes feed-forward, non-autoregressive speech synthesis in a single forward pass (< 200ms).
- **Execution Provider Fallback**: Automatically downgrades from `QNNExecutionProvider` to `CPUExecutionProvider` if NPU memory limits or thermal ceilings are breached.

---

## 📊 Model Specifications

| Model Role | Architecture | Base Weights | Optimized Format | Runtime Memory |
|---|---|---|---|---|
| **Acoustic ASR** | IndicConformer-Large | AI4Bharat (~120M params) | INT8 ONNX (QNN HTP) | ~60 MB |
| **Acoustic Synthesizer** | FastPitch | AI4Bharat (~25M params) | INT8 ONNX | ~25 MB |
| **Neural Vocoder** | HiFi-GAN V1 | AI4Bharat (~15M params) | FP16/INT8 ONNX | ~15 MB |
