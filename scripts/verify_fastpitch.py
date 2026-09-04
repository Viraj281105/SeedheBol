"""Verify the exported FastPitch + HiFi-GAN ONNX graphs.

Two things are checked, in order:

  1. Tokenisation is reproducible without Coqui. Coqui's own tokenizer is used
     as ground truth, and a plain-Python reimplementation must produce the same
     id sequence -- because the Kotlin side will have to reimplement it too.

  2. The audio is intelligible. The synthesised waveform is fed back through
     the IndicConformer ASR the app already runs, and the transcript is compared
     to the text we asked it to say. That measures the thing that actually
     matters for a pronunciation teacher; a learner who mishears the model
     learns the wrong sound.

Usage:  python scripts/verify_fastpitch.py mr
"""

import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as rt
import soundfile as sf

ROOT = Path(__file__).resolve().parent.parent
LANG = sys.argv[1] if len(sys.argv) > 1 else "mr"
SRC = ROOT / "models" / "tts_fastpitch" / LANG
ONNX = ROOT / "models" / "tts_fastpitch" / f"{LANG}_onnx"
OUTDIR = ROOT / "reference" / "tts_fastpitch"
OUTDIR.mkdir(parents=True, exist_ok=True)

PHRASES = [
    "नमस्कार",
    "मला मदत हवी आहे",
    "पाणी कुठे मिळेल",
    "पगार कधी मिळेल",
    "कृपया हळू बोला",
    "माझं नाव राहुल आहे",
]


def coqui_tokenizer():
    """Ground-truth tokenizer, straight from the shipped config."""
    from TTS.config import load_config
    from TTS.tts.utils.text.tokenizer import TTSTokenizer

    cfg = load_config(str(SRC / "fastpitch" / "config.json"))
    tok, _ = TTSTokenizer.init_from_config(cfg)
    return tok


def main():
    tok = coqui_tokenizer()
    chars = tok.characters

    # ---- dump the id map so Kotlin can reproduce it ----------------------
    char_to_id = {c: chars.char_to_id(c) for c in chars.vocab}
    spec = {
        "language": LANG,
        "sample_rate": 22050,
        "add_blank": bool(getattr(tok, "add_blank", False)),
        "use_eos_bos": bool(getattr(tok, "use_eos_bos", False)),
        "pad_id": chars.char_to_id(chars.pad),
        "blank_id": chars.char_to_id(chars.blank) if chars.blank else None,
        "bos_id": chars.char_to_id(chars.bos) if chars.bos else None,
        "eos_id": chars.char_to_id(chars.eos) if chars.eos else None,
        "vocab_size": len(chars.vocab),
        "char_to_id": char_to_id,
    }
    (ONNX / "tokens.json").write_text(
        json.dumps(spec, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"tokenizer: {len(chars.vocab)} symbols, add_blank={spec['add_blank']}, "
          f"use_eos_bos={spec['use_eos_bos']}")

    # ---- plain-Python reimplementation, must match -----------------------
    def encode(text: str) -> list[int]:
        ids = [char_to_id[c] for c in text if c in char_to_id]
        if spec["add_blank"]:
            out = [spec["blank_id"]] * (len(ids) * 2 + 1)
            out[1::2] = ids
            ids = out
        if spec["use_eos_bos"]:
            ids = [spec["bos_id"]] + ids + [spec["eos_id"]]
        return ids

    mismatches = 0
    for p in PHRASES:
        if encode(p) != tok.text_to_ids(p):
            mismatches += 1
            print(f"  MISMATCH on {p!r}")
    print(f"tokeniser parity: {len(PHRASES) - mismatches}/{len(PHRASES)} phrases match Coqui\n")

    # ---- synthesise -------------------------------------------------------
    fp = rt.InferenceSession(str(ONNX / "fastpitch.onnx"), providers=["CPUExecutionProvider"])
    voc = rt.InferenceSession(str(ONNX / "hifigan.onnx"), providers=["CPUExecutionProvider"])

    # ---- the app's own recogniser, as the judge ---------------------------
    mdir = ROOT / "models" / "mr"
    pre = rt.InferenceSession(str(mdir / "nemo80.onnx"), providers=["CPUExecutionProvider"])
    asr = rt.InferenceSession(str(mdir / "model.arm64.onnx"), providers=["CPUExecutionProvider"])
    vocab_asr = {
        int(l.rsplit(" ", 1)[1]): l.rsplit(" ", 1)[0]
        for l in (mdir / "vocab.txt").read_text(encoding="utf-8").splitlines()
        if l.strip()
    }
    blank = max(vocab_asr)

    def transcribe(wav22: np.ndarray) -> str:
        n = int(len(wav22) * 16000 / 22050)
        wav16 = np.interp(
            np.linspace(0, len(wav22) - 1, n), np.arange(len(wav22)), wav22
        ).astype(np.float32)
        f, fl = pre.run(["features", "features_lens"],
                        {"waveforms": wav16[None, :], "waveforms_lens": np.array([n], dtype=np.int64)})
        (lp,) = asr.run(["logprobs"], {"audio_signal": f, "length": fl})
        ids = lp[0, : int((fl[0] - 1) // 4 + 1)].argmax(-1)
        out, prev = [], -1
        for i in ids:
            if i != prev and i != blank:
                out.append(vocab_asr[int(i)])
            prev = int(i)
        return "".join(out).replace("▁", " ").strip()

    def sim(a: str, b: str) -> float:
        if not a or not b:
            return 0.0
        d = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
        for i in range(len(a) + 1):
            d[i][0] = i
        for j in range(len(b) + 1):
            d[0][j] = j
        for i in range(1, len(a) + 1):
            for j in range(1, len(b) + 1):
                d[i][j] = min(d[i-1][j] + 1, d[i][j-1] + 1,
                              d[i-1][j-1] + (a[i-1] != b[j-1]))
        return 1 - d[len(a)][len(b)] / max(len(a), len(b))

    for spk in range(2):
        print(f"--- speaker {spk} ---")
        scores = []
        for i, text in enumerate(PHRASES):
            ids = np.array([encode(text)], dtype=np.int64)
            mel = fp.run(None, {"input_ids": ids,
                                "speaker_id": np.array([spk], dtype=np.int64)})[0]
            wav = voc.run(None, {"mel": mel})[0].squeeze()
            heard = transcribe(wav)
            s = sim(heard, text)
            scores.append(s)
            if spk == 0:
                sf.write(OUTDIR / f"{LANG}_{i:02d}.wav", wav, 22050, subtype="PCM_16")
            print(f"  {len(wav)/22050:4.1f}s  {s:.2f}  said {text!r}  heard {heard!r}")
        print(f"  mean similarity: {sum(scores)/len(scores):.3f}\n")


if __name__ == "__main__":
    main()
