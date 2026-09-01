"""T2 — the deployable path: two onnxruntime sessions, nothing else.

This is the reference implementation for the Kotlin port in T3. Every step here
has a one-to-one counterpart in OnnxAsr.kt, deliberately:

    PCM float32 [-1,1]
        -> nemo80.onnx        (waveforms, waveforms_lens) -> (features, features_lens)
        -> model.arm64.onnx   (audio_signal, length)      -> logprobs [B,T,257]
        -> greedy CTC decode  (argmax, collapse repeats, drop <blk>)

The log-mel front-end is an ONNX graph shipped by onnx-asr, not hand-written DSP.
That is the whole point: CLAUDE.md Trap 1 (mel parameter mismatch) cannot occur
if Android runs the identical graph these reference values came from.

The acoustic model is model.arm64.onnx, not the upstream model.int8.onnx --
see docs/device-notes.md for why (ConvInteger has no arm64 kernel).
"""
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as rt
import soundfile as sf

ROOT = Path(__file__).resolve().parent.parent
LANG = sys.argv[2] if len(sys.argv) > 2 else "mr"
WAV = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "assets" / "sample.wav"
MDIR = ROOT / "models" / LANG
REF = ROOT / "reference"
REF.mkdir(exist_ok=True)

# --- 1. audio in ------------------------------------------------------------
audio, sr = sf.read(WAV, dtype="float32", always_2d=True)
assert sr == 16000, f"expected 16kHz, got {sr} (Trap 3)"
audio = audio.mean(axis=1)  # to mono
waveforms = audio[None, :].astype(np.float32)
waveforms_lens = np.array([waveforms.shape[1]], dtype=np.int64)
print(f"audio    {WAV.name}: {waveforms.shape[1]} samples, {waveforms.shape[1] / sr:.2f}s @ {sr}Hz")

# --- 2. log-mel front-end (ONNX graph, not hand-rolled) ---------------------
pre = rt.InferenceSession(str(MDIR / "nemo80.onnx"), providers=["CPUExecutionProvider"])
features, features_lens = pre.run(
    ["features", "features_lens"],
    {"waveforms": waveforms, "waveforms_lens": waveforms_lens},
)
print(f"features {features.shape} {features.dtype}  lens={features_lens.tolist()}")

# --- 3. acoustic model ------------------------------------------------------
enc = rt.InferenceSession(str(MDIR / "model.arm64.onnx"), providers=["CPUExecutionProvider"])
(logprobs,) = enc.run(["logprobs"], {"audio_signal": features, "length": features_lens})
print(f"logprobs {logprobs.shape} {logprobs.dtype}")

# --- 4. greedy CTC decode ---------------------------------------------------
vocab = {}
for line in (MDIR / "vocab.txt").read_text(encoding="utf-8").splitlines():
    if not line.strip():
        continue
    tok, idx = line.rsplit(" ", 1)
    vocab[int(idx)] = tok
BLANK = max(vocab)  # <blk>, explicitly the last entry
assert vocab[BLANK] == "<blk>", f"unexpected blank token {vocab[BLANK]!r}"

subsampling = json.loads((MDIR / "config.json").read_text())["subsampling_factor"]
n_frames = int((features_lens[0] - 1) // subsampling + 1)

ids = logprobs[0, :n_frames].argmax(axis=-1)
out, prev = [], -1
for i in ids:
    if i != prev and i != BLANK:
        out.append(vocab[int(i)])
    prev = int(i)
text = "".join(out).replace("▁", " ").strip()

print(f"\ntranscript: {text}")

# --- 5. reference artefacts — the ground truth the Kotlin port is checked on -
np.save(REF / "melspec.npy", features)
np.save(REF / "logits.npy", logprobs)
(REF / "transcript.txt").write_text(text + "\n", encoding="utf-8")
(REF / "preproc_config.json").write_text(
    json.dumps(
        {
            "_source": "onnx-asr preprocessors/nemo.py — values read from source, not guessed",
            "_graph": "nemo80.onnx (ai.onnx opset 17); nemo80_conv.onnx is the STFT-free fallback",
            "sample_rate": 16000,
            "n_fft": 512,
            "win_length": 400,
            "hop_length": 160,
            "n_mels": 80,
            "window": "hann_symmetric_400_zero_padded_to_512",
            "preemphasis": 0.97,
            "center_pad": "reflect-free zero pad of n_fft//2 each side",
            "mel_scale": "slaney",
            "mel_norm": "slaney",
            "log": "natural log of (mel + 2**-24)",
            "log_zero_guard_value": 2**-24,
            "normalize": "per_feature (per mel bin, over valid frames), ddof=1, eps=1e-5",
            "dither": 0.0,
            "features_lens": "waveforms_lens // hop_length",
            "subsampling_factor": subsampling,
            "vocab_size": len(vocab),
            "blank_id": BLANK,
        },
        indent=2,
    ),
    encoding="utf-8",
)
print(f"\n-> reference/melspec.npy      {features.shape}")
print(f"-> reference/logits.npy       {logprobs.shape}")
print(f"-> reference/transcript.txt")
print(f"-> reference/preproc_config.json")
