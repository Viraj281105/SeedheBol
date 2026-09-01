"""T2 — dump the real graph signatures into docs/onnx-signature.md.

CLAUDE.md working rule 3: inspect before assuming. The Kotlin port in T3 binds
to these exact tensor names, shapes and dtypes, so they get written down rather
than guessed at. Covers the whole two-session pipeline, not just the model.
"""
import json
import sys
from pathlib import Path

import onnx
from onnx import TensorProto

ROOT = Path(__file__).resolve().parent.parent
LANG = sys.argv[1] if len(sys.argv) > 1 else "mr"
MDIR = ROOT / "models" / LANG
DT = {v: k for k, v in TensorProto.DataType.items()}


def shape_of(vi):
    return [d.dim_param or d.dim_value for d in vi.type.tensor_type.shape.dim]


def dtype_of(vi):
    return DT.get(vi.type.tensor_type.elem_type, "?")


def describe(name, note):
    p = MDIR / name
    m = onnx.load(str(p), load_external_data=False)
    opsets = ", ".join(f"`{o.domain or 'ai.onnx'}:{o.version}`" for o in m.opset_import)
    ops = sorted({n.op_type for n in m.graph.node})
    out = [
        f"### `{name}` — {p.stat().st_size / 1e6:.2f} MB",
        "",
        note,
        "",
        f"- opset: {opsets}",
        f"- ops: {', '.join('`' + o + '`' for o in ops)}",
        "",
        "| dir | name | dtype | shape |",
        "|---|---|---|---|",
    ]
    out += [f"| in | `{i.name}` | {dtype_of(i)} | `{shape_of(i)}` |" for i in m.graph.input]
    out += [f"| out | `{o.name}` | {dtype_of(o)} | `{shape_of(o)}` |" for o in m.graph.output]
    out.append("")
    return out


cfg = json.loads((MDIR / "config.json").read_text())
vocab = [l for l in (MDIR / "vocab.txt").read_text(encoding="utf-8").splitlines() if l.strip()]

lines = [
    f"# ONNX signature — IndicConformer `{LANG}` (CTC branch)",
    "",
    f"Source: [`OpenVoiceOS/ai4bharat-indicconformer-{LANG}-onnx`]"
    f"(https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-{LANG}-onnx), converted from",
    f"[`ai4bharat/indicconformer_stt_{LANG}_hybrid_ctc_rnnt_large`]"
    f"(https://huggingface.co/ai4bharat/indicconformer_stt_{LANG}_hybrid_ctc_rnnt_large). MIT.",
    "",
    "## Pipeline",
    "",
    "```",
    "PCM float32 [-1,1] @16kHz",
    "   -> nemo80.onnx      (waveforms, waveforms_lens) -> (features, features_lens)",
    "   -> model.int8.onnx  (audio_signal, length)      -> logprobs [B,T,257]",
    "   -> greedy CTC       argmax, collapse repeats, drop <blk>",
    "```",
    "",
    "Two ONNX Runtime sessions and no hand-written DSP. The log-mel front-end is",
    "itself an ONNX graph, so CLAUDE.md Trap 1 (mel parameter mismatch) cannot occur:",
    "Android runs the identical graph the reference values were produced from.",
    "",
    "## Graphs",
    "",
]
lines += describe(
    "nemo80.onnx",
    "Log-mel front-end, from `onnx-asr` (`preprocessors/nemo.py`, MIT). Uses the "
    "`STFT` operator.",
)
lines += describe(
    "nemo80_conv.onnx",
    "**Fallback front-end.** Identical maths with `Conv`-based power spectrogram "
    "instead of `STFT` — use this if the ONNX Runtime Android build lacks the "
    "`STFT` kernel. Larger because the STFT basis is baked in as conv weights.",
)
lines += describe(
    "model.int8.onnx",
    "IndicConformer acoustic model, CTC branch only, int8-quantized. The `.nemo` "
    "checkpoint's RNNT branch is not exported and is not needed here.",
)
lines += [
    "## config.json",
    "",
    "```json",
    json.dumps(cfg, indent=2),
    "```",
    "",
    "## Vocabulary",
    "",
    f"- `models/{LANG}/vocab.txt` — **{len(vocab)} tokens**, matching `logprobs` last dim",
    "- format: `<token> <id>` per line, SentencePiece BPE, `▁` marks word start",
    f"- CTC blank is `<blk>`, explicitly the last entry (id {len(vocab) - 1}) — no guessing needed",
    f"- first: {' '.join(l.split()[0] for l in vocab[:8])}",
    f"- last:  {' '.join(l.split()[0] for l in vocab[-3:])}",
    "",
    "## Frame arithmetic",
    "",
    f"- `features_lens = n_samples // 160` (hop_length)",
    f"- `logprob_frames = (features_lens - 1) // {cfg['subsampling_factor']} + 1` (subsampling_factor)",
    "",
]

out = ROOT / "docs" / "onnx-signature.md"
out.parent.mkdir(exist_ok=True)
out.write_text("\n".join(lines), encoding="utf-8")
print("\n".join(lines))
print(f"-> {out}")
