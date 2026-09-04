#!/usr/bin/env bash
# Fetch and ARM-quantise the IndicConformer ASR model for every supported
# language.
#
# Per language: ~482 MB fp32 download -> model.arm64.onnx (~187 MB). The fp32
# source and the shipped int8 build are deleted afterwards; the shipped int8
# quantises Conv as well, and ONNX Runtime's arm64 CPU provider has no
# ConvInteger kernel, so it cannot load on device at all.
#
# nemo80.onnx is the NeMo log-mel front-end exported as a graph. It has no
# language-specific weights, so one copy is shared across every language.
#
# Usage:  bash scripts/build_all_asr.sh [lang ...]

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PY=".venv/Scripts/python.exe"
[ -x "$PY" ] || PY="python"
LANGS=("${@:-hi bn te ta gu kn or ml}")
# shellcheck disable=SC2206
LANGS=(${LANGS[@]})

FRONTEND="models/mr/nemo80.onnx"

for L in "${LANGS[@]}"; do
  echo "=============================================================="
  echo "  $L"
  echo "=============================================================="

  if [ -f "models/$L/model.arm64.onnx" ]; then
    echo "  already built, skipping"
    continue
  fi

  if [ ! -f "models/$L/model.onnx_data" ]; then
    echo "  fetching …"
    "$PY" scripts/fetch_model.py "$L" || { echo "  FETCH FAILED"; continue; }
  fi

  echo "  quantising (MatMul only) …"
  "$PY" scripts/quantize_arm.py "$L" || { echo "  QUANTIZE FAILED"; continue; }

  cp "$FRONTEND" "models/$L/nemo80.onnx"

  # Session-load check: this is the exact failure the Pixel 8 hit, and it only
  # shows up when the graph is actually loaded.
  "$PY" - "$L" <<'PYEOF'
import sys
from pathlib import Path
import onnxruntime as rt
lang = sys.argv[1]
d = Path("models") / lang
s = rt.InferenceSession(str(d / "model.arm64.onnx"), providers=["CPUExecutionProvider"])
i = {x.name: x.shape for x in s.get_inputs()}
o = [x.name for x in s.get_outputs()]
v = [l for l in (d / "vocab.txt").read_text(encoding="utf-8").splitlines() if l.strip()]
print(f"  loads OK  inputs={i}  outputs={o}  vocab={len(v)}")
PYEOF

  rm -f "models/$L/model.onnx" "models/$L/model.onnx_data" "models/$L/model.int8.onnx"
  rm -rf "models/$L/.cache"
done

echo
echo "=============================================================="
echo "  built languages"
for d in models/*/; do
  L=$(basename "$d")
  [ -f "$d/model.arm64.onnx" ] || continue
  SZ=$(stat -c%s "$d/model.arm64.onnx")
  printf "  %-4s %6.1f MB\n" "$L" "$(echo "$SZ" | awk '{print $1/1e6}')"
done
