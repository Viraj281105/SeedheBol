"""T3 — re-quantize the acoustic model for ARM64.

The shipped model.int8.onnx quantizes both MatMul and Conv, producing 54
ConvInteger nodes. ONNX Runtime's arm64 CPU provider has no ConvInteger kernel,
so session creation fails on the Pixel 8 with:

    ORT_NOT_IMPLEMENTED - Could not find an implementation for ConvInteger(10)

x86 has the kernel, which is why this only shows up on device — the desktop
path never hit it.

Quantizing MatMul only keeps the conformer's attention and feed-forward blocks
(~85M of ~120M parameters) in int8 while leaving the convolution modules in
fp32. MatMulInteger does have arm64 kernels.
"""
import sys
from pathlib import Path

from onnxruntime.quantization import QuantType, quantize_dynamic

ROOT = Path(__file__).resolve().parent.parent
LANG = sys.argv[1] if len(sys.argv) > 1 else "mr"
MDIR = ROOT / "models" / LANG
SRC = MDIR / "model.onnx"          # fp32, with model.onnx_data alongside
DST = MDIR / "model.arm64.onnx"

print(f"quantizing {SRC} (MatMul only) -> {DST}")
quantize_dynamic(
    model_input=SRC,
    model_output=DST,
    op_types_to_quantize=["MatMul"],  # deliberately NOT Conv
    weight_type=QuantType.QInt8,
    per_channel=True,
    reduce_range=False,
    extra_options={"MatMulConstBOnly": True},
)
print(f"-> {DST}  {DST.stat().st_size / 1e6:.1f} MB")
