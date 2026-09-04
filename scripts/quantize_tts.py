"""Quantise the FastPitch + HiFi-GAN graphs to int8 for the device.

fp32 is 217 MB + 56 MB per language. Nine languages is 2.5 GB, which is not a
shippable app, so quantisation is not an optimisation here -- it is the thing
that makes multi-language TTS possible at all.

MatMul only, never Conv, for the same reason as the ASR model: ONNX Runtime's
arm64 CPU provider has no ConvInteger kernel and the session fails to create on
device. HiFi-GAN is almost entirely transposed convolution, so it barely shrinks
and is left alone; FastPitch is mostly attention and feed-forward, so it is
where the weight actually is.

Usage:  python scripts/quantize_tts.py hi
"""

import shutil
import sys
from pathlib import Path

from onnxruntime.quantization import QuantType, quantize_dynamic

ROOT = Path(__file__).resolve().parent.parent
LANG = sys.argv[1] if len(sys.argv) > 1 else "hi"
SRC = ROOT / "models" / "tts_fastpitch" / f"{LANG}_onnx"
DST = ROOT / "models" / "tts_fastpitch" / f"{LANG}_int8"
DST.mkdir(parents=True, exist_ok=True)


def mb(p):
    total = p.stat().st_size
    data = p.with_suffix(p.suffix + ".data")
    if data.exists():
        total += data.stat().st_size
    return total / 1e6


quantize_dynamic(
    model_input=str(SRC / "fastpitch.onnx"),
    model_output=str(DST / "fastpitch.onnx"),
    op_types_to_quantize=["MatMul"],
    weight_type=QuantType.QInt8,
    per_channel=True,
    reduce_range=False,
    extra_options={"MatMulConstBOnly": True},
)
print(f"fastpitch  {mb(SRC / 'fastpitch.onnx'):6.1f} MB -> {mb(DST / 'fastpitch.onnx'):6.1f} MB")

# HiFi-GAN is convolutional throughout; quantising its few MatMuls buys nothing,
# so it is copied across unchanged rather than pointlessly rewritten.
shutil.copy2(SRC / "hifigan.onnx", DST / "hifigan.onnx")
shutil.copy2(SRC / "tokens.json", DST / "tokens.json")
print(f"hifigan    {mb(SRC / 'hifigan.onnx'):6.1f} MB -> copied unchanged")
print(f"-> {DST}")
