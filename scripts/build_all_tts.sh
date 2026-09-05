#!/usr/bin/env bash
# Fetch, extract and export AI4Bharat FastPitch + HiFi-GAN for every supported
# language. Each source zip is ~1.5 GB and is deleted after extraction; the
# extracted .pth checkpoints are kept only until the ONNX graphs exist.
#
# Urdu is absent from the Indic-TTS release (Piper covers it instead), so the
# list below is the nine languages this stack can synthesise.
#
# Usage:  bash scripts/build_all_tts.sh [lang ...]

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PY="${VIRTUAL_ENV:-.venv}/Scripts/python.exe"
BASE="https://github.com/AI4Bharat/Indic-TTS/releases/download/v1-checkpoints-release"
DIR="models/tts_fastpitch"
LANGS=("${@:-hi bn te ta gu kn or ml}")
# shellcheck disable=SC2206
LANGS=(${LANGS[@]})

mkdir -p "$DIR"

for L in "${LANGS[@]}"; do
  echo "=============================================================="
  echo "  $L"
  echo "=============================================================="

  if [ -f "$DIR/${L}_onnx/fastpitch.onnx" ]; then
    echo "  already exported, skipping"
    continue
  fi

  if [ ! -d "$DIR/$L" ]; then
    echo "  downloading ${L}.zip …"
    while true; do
      if curl -L --retry 10 --retry-delay 3 --retry-all-errors --continue-at - "$BASE/${L}.zip" -o "$DIR/${L}.zip"; then
        break
      fi
      echo "  download interrupted, retrying in 5s…"
      sleep 5
    done
    SZ=$(stat -c%s "$DIR/${L}.zip" 2>/dev/null || echo 0)
    if [ "$SZ" -lt 100000000 ]; then
      echo "  DOWNLOAD TOO SMALL ($SZ bytes) — skipping"
      rm -f "$DIR/${L}.zip"
      continue
    fi
    echo "  extracting … ($((SZ / 1000000)) MB)"
    "$PY" -m zipfile -e "$DIR/${L}.zip" "$DIR" || { echo "  UNZIP FAILED"; continue; }
    rm -f "$DIR/${L}.zip"
  fi

  echo "  exporting to ONNX …"
  PYTHONUTF8=1 "$PY" scripts/export_fastpitch.py "$L" 2>&1 \
    | grep -aE "speakers:|-> |dry run" || echo "  EXPORT FAILED"

  if [ -f "$DIR/${L}_onnx/fastpitch.onnx" ]; then
    echo "  verifying …"
    PYTHONUTF8=1 "$PY" scripts/verify_fastpitch.py "$L" 2>&1 \
      | grep -aE "tokeniser parity|mean similarity" || true
    # The .pth checkpoints are ~1.6 GB per language and are not needed again.
    rm -rf "${DIR:?}/$L"
  fi
done

echo
echo "=============================================================="
echo "  exported languages"
ls -d "$DIR"/*_onnx 2>/dev/null | while read -r d; do
  L=$(basename "$d" _onnx)
  FP=$(stat -c%s "$d/fastpitch.onnx.data" 2>/dev/null || stat -c%s "$d/fastpitch.onnx" 2>/dev/null || echo 0)
  HG=$(stat -c%s "$d/hifigan.onnx" 2>/dev/null || echo 0)
  printf "  %-4s fastpitch %6.1f MB   hifigan %5.1f MB\n" \
    "$L" "$(echo "$FP" | awk '{print $1/1e6}')" "$(echo "$HG" | awk '{print $1/1e6}')"
done
