"""T1/T2 — fetch the IndicConformer per-language ONNX export.

Uses OpenVoiceOS's ONNX conversion of the official AI4Bharat checkpoint
(ai4bharat/indicconformer_stt_<lang>_hybrid_ctc_rnnt_large). MIT licensed.
The int8 weights are ~138MB and self-contained; fp32 is split across
model.onnx + model.onnx_data (external-data format).
"""
import sys
from pathlib import Path
from huggingface_hub import hf_hub_download

LANG = sys.argv[1] if len(sys.argv) > 1 else "mr"
REPO = f"OpenVoiceOS/ai4bharat-indicconformer-{LANG}-onnx"
OUT = Path(__file__).resolve().parent.parent / "models" / LANG

# fp32 needs its external-data sidecar alongside it or onnxruntime cannot load it.
FILES = ["config.json", "vocab.txt", "model.int8.onnx", "model.onnx", "model.onnx_data"]

OUT.mkdir(parents=True, exist_ok=True)
for f in FILES:
    p = hf_hub_download(repo_id=REPO, filename=f, local_dir=OUT)
    print(f"{f:20s} {Path(p).stat().st_size / 1e6:8.1f} MB")
print(f"\n-> {OUT}")
