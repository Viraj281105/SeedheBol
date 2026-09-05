# `tools/model_pipeline` — Offline Model Conversion & Quantization Toolkit

> **Pre-Event & Build-Time Pipeline**: Python utilities for exporting IndicConformer and FastPitch to ONNX, INT8 quantization for Qualcomm Hexagon NPU, and batch audio pre-rendering using Indic Parler-TTS.

---

## 🛠️ CLI Utilities & Scripts

### 1. `export_indic_conformer.py`
Exports AI4Bharat's IndicConformer PyTorch checkpoint to ONNX with separate output tensors for the CTC posterior head and RNNT decoder:
```bash
python export_indic_conformer.py \
  --checkpoint ai4bharat/indic-conformer-600m-multilingual \
  --output ../../apps/mobile/android/app/src/main/assets/models/indic_conformer_int8.onnx \
  --quantize int8
```

### 2. `export_fastpitch_hifigan.py`
Exports FastPitch acoustic model and HiFi-GAN V1 neural vocoder as twin non-autoregressive ONNX graphs:
```bash
python export_fastpitch_hifigan.py \
  --language tamil \
  --output_dir ../../apps/mobile/android/app/src/main/assets/models/tts/
```

### 3. `prerender_lesson_audio.py`
Uses **Indic Parler-TTS** on laptop/cloud to pre-render curriculum audio across registers and speech speeds (0.7x, 1.0x, 1.2x), downsampled to 22kHz mono PCM for minimal asset weight:
```bash
python prerender_lesson_audio.py \
  --curriculum ../../data/curricula/bhojpuri_tamil_construction.json \
  --output_dir ../../apps/mobile/android/app/src/main/assets/audio/
```
