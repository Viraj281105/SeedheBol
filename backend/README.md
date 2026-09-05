# Boli Backend — Local Speech & Curriculum Pipelines

Complete local backend tools, curriculum compilers, model pipelines, and offline PC bridge services for the **Boli** on-device speech acquisition platform.

All tools and services run **100% locally** with zero cloud latency and zero network dependencies.

---

## 1. Directory Structure

```
backend/
├── content_compiler/      # Curriculum & L1-L2 phonetic confusion matrix generators
│   ├── compile_l1_confusion.py        # Compiles Bhojpuri -> Tamil confusion tables
│   ├── generate_situations.py         # Authoring & AST validation of 10 workplace scenarios
│   ├── generate_multilingual_audio.py # Audio generation for lessons
│   ├── g2p_reference.py               # Pure-Python G2P reference implementation
│   ├── gop_reference.py               # Reference Goodness-of-Pronunciation (GOP) scorer
│   └── README.md
├── model_pipeline/        # ONNX export, INT8 quantization & Qualcomm Hexagon NPU benchmarking
│   ├── benchmark_onnx_npu.py          # Latency (RTF), memory, and thermal profiling
│   ├── export_indic_conformer.py      # IndicConformer hybrid CTC-RNNT ONNX export
│   ├── export_fastpitch_hifigan.py    # FastPitch + HiFi-GAN V1 ONNX export
│   ├── prerender_lesson_audio.py      # Parler-TTS offline lesson pre-renderer
│   ├── requirements.txt
│   └── README.md
├── officekit_bridge/      # Local offline assessment sync & telemetry daemon
│   ├── remote_pc_worker.py            # Local Flask/REST daemon for phone -> PC assessment sync
│   ├── sync_assessments.py            # CLI test client for assessment synchronization
│   ├── verify_telemetry.py            # Telemetry validation tool
│   ├── requirements.txt
│   └── README.md
├── tests/                 # Automated unit and integration test suite
│   ├── test_content_compiler.py       # Curriculum AST & confusion integrity tests
│   ├── test_g2p_reference.py          # G2P & Hindi schwa-deletion tests
│   └── test_gop_reference.py          # GOP scoring & acoustic divergence tests
└── data/                  # Canonical curriculum and pre-rendered audio assets
    ├── bhojpuri_tamil_confusion.json  # Precompiled phonetic confusion map
    ├── construction_tamil.json        # 10 full-length situational roleplay lessons
    └── rendered_audio/                # High-fidelity lesson audio samples
```

---

## 2. Quickstart & Verification

### Run the Test Suite
Execute the entire backend test suite using the repository's local virtual environment:

```powershell
& "..\.venv\Scripts\python.exe" -m unittest discover -s tests
```

### Regenerate Curricula & Phonetic Confusion Tables
```powershell
# Compile the 10 workplace situations
& "..\.venv\Scripts\python.exe" content_compiler/generate_situations.py

# Compile the Bhojpuri -> Tamil phonetic confusion map
& "..\.venv\Scripts\python.exe" content_compiler/compile_l1_confusion.py
```

### Benchmark ONNX Inference (ASR / GOP / TTS)
```powershell
& "..\.venv\Scripts\python.exe" model_pipeline/benchmark_onnx_npu.py --model "..\models\ta\model.arm64.onnx"
```

### Start the Local Offline Bridge Daemon
For Anganwadi supervisors or site trainers syncing student progress to a local PC over Wi-Fi / USB / Vivo OfficeKit:
```powershell
& "..\.venv\Scripts\python.exe" officekit_bridge/remote_pc_worker.py --port 8080
```
Test sync locally:
```powershell
& "..\.venv\Scripts\python.exe" officekit_bridge/sync_assessments.py --host http://127.0.0.1:8080
```
