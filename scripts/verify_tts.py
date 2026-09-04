"""Verify the exported FastPitch + HiFi-GAN graphs for every language.

Unlike scripts/verify_fastpitch.py this needs no Coqui and no .pth checkpoints:
the character table was already dumped to tokens.json at export time, and that
table -- not Coqui -- is what the Kotlin side will read. Verifying against it
verifies the thing that actually ships.

Three checks per language, in increasing order of strictness:

  1. Vocabulary coverage. Any character of a test phrase that is missing from
     the table is silently dropped by the tokenizer, so the model says
     something different from what it was asked to say without any error. This
     catches that.

  2. Signal sanity. Non-empty, non-silent, non-clipping audio of a plausible
     duration. Catches a graph that exports and runs but emits noise.

  3. Intelligibility. The waveform is fed back through the IndicConformer ASR
     the app already runs and the transcript compared to the input text. This
     is the only check that measures the thing a pronunciation teacher needs:
     a learner who mishears the model learns the wrong sound. It is skipped
     for languages whose ASR model has not been built yet.

Usage:  python scripts/verify_tts.py             # every exported language
        python scripts/verify_tts.py hi mr       # just these
"""

import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as rt
import soundfile as sf

ROOT = Path(__file__).resolve().parent.parent
TTS = ROOT / "models" / "tts_fastpitch"
OUTDIR = ROOT / "reference" / "tts_fastpitch"
PHRASES = json.loads((ROOT / "reference" / "phrases.json").read_text(encoding="utf-8"))

ASR_SR = 16000
TTS_SR = 22050


def load_tokens(lang):
    spec = json.loads((TTS / f"{lang}_onnx" / "tokens.json").read_text(encoding="utf-8"))
    if "char_to_id" not in spec:
        raise SystemExit(f"{lang}: tokens.json has no char_to_id — re-run the export")
    return spec


def make_encoder(spec):
    """The exact tokenisation Kotlin has to reproduce. Deliberately trivial."""
    c2i = spec["char_to_id"]

    def encode(text):
        ids = [c2i[c] for c in text if c in c2i]
        if spec["add_blank"]:
            out = [spec["blank_id"]] * (len(ids) * 2 + 1)
            out[1::2] = ids
            ids = out
        if spec["use_eos_bos"]:
            ids = [spec["bos_id"]] + ids + [spec["eos_id"]]
        return ids

    return encode


def load_asr(lang):
    d = ROOT / "models" / lang
    if not (d / "model.arm64.onnx").exists() or not (d / "nemo80.onnx").exists():
        return None
    pre = rt.InferenceSession(str(d / "nemo80.onnx"), providers=["CPUExecutionProvider"])
    net = rt.InferenceSession(str(d / "model.arm64.onnx"), providers=["CPUExecutionProvider"])
    vocab = {
        int(l.rsplit(" ", 1)[1]): l.rsplit(" ", 1)[0]
        for l in (d / "vocab.txt").read_text(encoding="utf-8").splitlines()
        if l.strip()
    }
    blank = max(vocab)

    def transcribe(wav):
        # Resample 22.05k -> 16k. The two rates are kept deliberately separate;
        # sharing a path between synthesis and recognition is how the sample
        # rates get crossed.
        n = int(len(wav) * ASR_SR / TTS_SR)
        wav16 = np.interp(
            np.linspace(0, len(wav) - 1, n), np.arange(len(wav)), wav
        ).astype(np.float32)
        f, fl = pre.run(
            ["features", "features_lens"],
            {"waveforms": wav16[None, :], "waveforms_lens": np.array([n], dtype=np.int64)},
        )
        (lp,) = net.run(["logprobs"], {"audio_signal": f, "length": fl})
        ids = lp[0, : int((fl[0] - 1) // 4 + 1)].argmax(-1)
        out, prev = [], -1
        for i in ids:
            if i != prev and i != blank:
                out.append(vocab[int(i)])
            prev = int(i)
        return "".join(out).replace("▁", " ").strip()

    return transcribe


def similarity(a, b):
    """1 - normalised edit distance. 1.0 means the ASR heard it exactly."""
    if not a or not b:
        return 0.0
    d = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) + 1):
        d[i][0] = i
    for j in range(len(b) + 1):
        d[0][j] = j
    for i in range(1, len(a) + 1):
        for j in range(1, len(b) + 1):
            d[i][j] = min(
                d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] != b[j - 1])
            )
    return 1 - d[len(a)][len(b)] / max(len(a), len(b))


def check(lang, speakers=(0,)):
    onnx = TTS / f"{lang}_onnx"
    if not (onnx / "fastpitch.onnx").exists():
        print(f"{lang}: not exported")
        return None

    spec = load_tokens(lang)
    encode = make_encoder(spec)
    phrases = PHRASES.get(lang)
    if not phrases:
        print(f"{lang}: no test phrases")
        return None

    # ---- 1. vocabulary coverage ------------------------------------------
    c2i = spec["char_to_id"]
    missing = sorted({c for p in phrases for c in p if c not in c2i})
    if missing:
        print(f"  MISSING FROM VOCAB: {''.join(missing)!r} "
              f"({' '.join('U+%04X' % ord(c) for c in missing)})")

    fp = rt.InferenceSession(str(onnx / "fastpitch.onnx"), providers=["CPUExecutionProvider"])
    voc = rt.InferenceSession(str(onnx / "hifigan.onnx"), providers=["CPUExecutionProvider"])
    transcribe = load_asr(lang)

    OUTDIR.mkdir(parents=True, exist_ok=True)
    results = []
    for spk in speakers:
        scores = []
        for i, text in enumerate(phrases):
            ids = np.array([encode(text)], dtype=np.int64)
            mel = fp.run(None, {"input_ids": ids,
                                "speaker_id": np.array([spk], dtype=np.int64)})[0]
            wav = voc.run(None, {"mel": mel})[0].squeeze().astype(np.float32)

            # ---- 2. signal sanity -----------------------------------------
            dur = len(wav) / TTS_SR
            rms = float(np.sqrt(np.mean(wav ** 2)))
            peak = float(np.max(np.abs(wav)))
            flag = ""
            if rms < 0.005:
                flag = "  SILENT"
            elif peak >= 0.999:
                flag = "  CLIPPING"
            elif not (0.4 < dur < 12.0):
                flag = "  IMPLAUSIBLE DURATION"

            # ---- 3. intelligibility ---------------------------------------
            if transcribe is not None:
                heard = transcribe(wav)
                s = similarity(heard, text)
                scores.append(s)
                print(f"  spk{spk} {dur:4.1f}s  {s:.2f}  said {text}  heard {heard}{flag}")
            else:
                print(f"  spk{spk} {dur:4.1f}s  rms {rms:.3f}  {text}{flag}")

            if spk == speakers[0]:
                sf.write(OUTDIR / f"{lang}_{i:02d}.wav", wav, TTS_SR, subtype="PCM_16")

        if scores:
            mean = sum(scores) / len(scores)
            exact = sum(1 for s in scores if s == 1.0)
            print(f"  spk{spk} mean similarity {mean:.3f}   exact {exact}/{len(scores)}")
            results.append(mean)

    return (sum(results) / len(results)) if results else float("nan")


def main():
    langs = sys.argv[1:] or sorted(
        d.name[:-5] for d in TTS.glob("*_onnx") if (d / "fastpitch.onnx").exists()
    )
    summary = {}
    for lang in langs:
        print(f"=== {lang} ===")
        summary[lang] = check(lang)
        print()

    print("=== summary ===")
    for lang, mean in summary.items():
        if mean is None:
            print(f"  {lang:4s} not verified")
        elif mean != mean:  # NaN
            print(f"  {lang:4s} synthesises; no ASR model yet, intelligibility unmeasured")
        else:
            print(f"  {lang:4s} mean intelligibility {mean:.3f}")


if __name__ == "__main__":
    main()
