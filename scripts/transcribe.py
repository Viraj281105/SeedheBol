"""T1 — baseline transcription via onnx-asr's own NeMo CTC implementation.

This is the "does the model actually work" check and the source of
reference/transcript.txt. It is deliberately NOT the implementation we port to
Kotlin: scripts/transcribe_onnx.py reimplements the front-end from scratch, and
the two must agree. Two independent paths agreeing is the only real evidence
the mel front-end is right.
"""
import sys
from pathlib import Path
import onnx_asr

ROOT = Path(__file__).resolve().parent.parent
LANG = sys.argv[2] if len(sys.argv) > 2 else "mr"
WAV = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "assets" / "sample.wav"

model = onnx_asr.load_model(str(ROOT / "models" / LANG), quantization="int8")
text = model.recognize(str(WAV))

print(f"wav:        {WAV}")
print(f"transcript: {text}")

out = ROOT / "reference" / "transcript.txt"
out.parent.mkdir(exist_ok=True)
out.write_text(text.strip() + "\n", encoding="utf-8")
print(f"-> {out}")
