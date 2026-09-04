"""Choose which of each language's two voices to ship.

Every AI4Bharat FastPitch checkpoint carries two speakers, and they are not
equally good. Bengali speaker 0 scores 0.746 while speaker 1 scores 0.947 on
the same phrases through the same recogniser -- so picking a speaker globally,
or picking speaker 0 because it is first, ships a noticeably worse voice in
some languages for no reason.

Peak normalisation is evaluated at the same time. Levels vary about fourfold
between languages (Bengali synthesises at roughly a quarter of Kannada's RMS),
which matters twice over: a quiet voice is harder to hear on a building site,
and the recogniser scores it lower too.

The judge is the app's own IndicConformer. It is not a perfect oracle -- it has
its own error modes, and a low score can mean the recogniser struggled rather
than the synthesiser -- but it is the same recogniser that will grade the
learner, so agreement between the two halves of the app is the property worth
optimising.

Writes reference/voices.json, which the app reads to pick a speaker id.

Usage:  python scripts/pick_voice.py [lang ...]
"""

import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as rt

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_tts import load_asr, make_encoder, similarity  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
TTS = ROOT / "models" / "tts_fastpitch"
PHRASES = json.loads((ROOT / "reference" / "phrases.json").read_text(encoding="utf-8"))


def score(lang, fp, voc, encode, transcribe, spk, normalise):
    scores = []
    for text in PHRASES[lang]:
        ids = np.array([encode(text)], dtype=np.int64)
        mel = fp.run(None, {"input_ids": ids,
                            "speaker_id": np.array([spk], dtype=np.int64)})[0]
        wav = voc.run(None, {"mel": mel})[0].squeeze().astype(np.float32)
        if normalise:
            peak = float(np.max(np.abs(wav)))
            if peak > 1e-6:
                wav = wav / peak * 0.9
        scores.append(similarity(transcribe(wav), text))
    return sum(scores) / len(scores), sum(1 for s in scores if s == 1.0)


def main():
    langs = sys.argv[1:] or sorted(
        d.name[:-5] for d in TTS.glob("*_onnx") if (d / "fastpitch.onnx").exists()
    )
    chosen = {}
    for lang in langs:
        d = TTS / f"{lang}_onnx"
        spec = json.loads((d / "tokens.json").read_text(encoding="utf-8"))
        encode = make_encoder(spec)
        fp = rt.InferenceSession(str(d / "fastpitch.onnx"), providers=["CPUExecutionProvider"])
        voc = rt.InferenceSession(str(d / "hifigan.onnx"), providers=["CPUExecutionProvider"])
        transcribe = load_asr(lang)
        if transcribe is None:
            print(f"{lang}: no ASR model, cannot judge")
            continue

        best = None
        for spk in (0, 1):
            for norm in (False, True):
                mean, exact = score(lang, fp, voc, encode, transcribe, spk, norm)
                tag = "peak-norm" if norm else "raw"
                print(f"  {lang} spk{spk} {tag:9s}  mean {mean:.3f}  exact {exact}/6")
                if best is None or mean > best[0]:
                    best = (mean, spk, norm, exact)
        mean, spk, norm, exact = best
        chosen[lang] = {"speaker_id": spk, "peak_normalise": norm,
                        "intelligibility": round(mean, 3), "exact": exact}
        print(f"  {lang} -> speaker {spk}, "
              f"{'peak normalised' if norm else 'raw level'}, {mean:.3f}\n")

    out = ROOT / "reference" / "voices.json"
    existing = {}
    if out.exists():
        existing = json.loads(out.read_text(encoding="utf-8"))
    existing.update(chosen)
    out.write_text(json.dumps(existing, ensure_ascii=False, indent=2), encoding="utf-8")

    print("=== chosen voices ===")
    for lang, v in sorted(existing.items()):
        print(f"  {lang:4s} speaker {v['speaker_id']}  "
              f"{'norm' if v['peak_normalise'] else 'raw '}  "
              f"{v['intelligibility']:.3f}  exact {v['exact']}/6")


if __name__ == "__main__":
    main()
