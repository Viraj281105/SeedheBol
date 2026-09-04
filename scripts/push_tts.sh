#!/usr/bin/env bash
# Push the FastPitch + HiFi-GAN model files to the device's external files
# dir, where OnnxAsr/FastPitchTts prefer a pushed file over the bundled asset.
# This is how the model is updated without a reinstall (CLAUDE.md Trap 7).
#
# tokens.json is the one FastPitch file small enough to bundle in the APK
# instead (see FastPitchTts.kt), so it has to be in place BEFORE `flutter
# build`, not pushed afterwards. This script copies it there too, so a stale
# copy from a previous language never silently survives a switch.
#
# Usage:  bash scripts/push_tts.sh [lang]   # default mr

set -euo pipefail
cd "$(dirname "$0")/.." || exit 1
export MSYS_NO_PATHCONV=1

LANG="${1:-mr}"
SRC="models/tts_fastpitch/${LANG}_int8"
DST="/sdcard/Android/data/com.boli.boli_proto/files"
ASSETS="boli_proto/android/app/src/main/assets"

[ -f "$SRC/fastpitch.onnx" ] || { echo "no int8 build for $LANG — run scripts/quantize_tts.py $LANG first"; exit 1; }

cp "$SRC/tokens.json" "$ASSETS/tts_tokens.json"
echo "tts_tokens.json <- $LANG (rebuild the app if this changed the language)"

adb shell mkdir -p "$DST"
adb push "$SRC/fastpitch.onnx" "$DST/fastpitch.onnx"
adb push "$SRC/hifigan.onnx" "$DST/hifigan.onnx"
echo "pushed $LANG FastPitch to $DST"
