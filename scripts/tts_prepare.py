"""TTS — precompute Piper phoneme IDs for every lesson phrase, and verify.

Piper is a VITS model that consumes espeak-ng IPA phoneme IDs, not text. Running
espeak-ng on Android would mean an NDK build of the C library plus its data
files; for a fixed demo phrase set that is disproportionate. So phonemisation
happens here, once, and the app ships a text -> ids lookup table.

Consequence, stated honestly: the device can speak the lesson phrases and
nothing else. Free-text synthesis needs the phonemiser on-device.

Verification closes the loop rather than trusting an ear: each synthesised
utterance is fed back through the IndicConformer ASR that the app already runs,
and the transcript is compared to the phrase we asked Piper to say. That
measures intelligibility, which is the metric that matters for a pronunciation
teacher -- a learner who mishears the model learns the wrong sound.

NOTE ON SAMPLE RATES (CLAUDE.md Trap A): Piper emits 22.05 kHz. The ASR path is
16 kHz. The resampling below exists ONLY to drive this offline check; it is test
harness code and is deliberately not mirrored into the Kotlin TTS path, which
hands 22.05 kHz straight to AudioTrack and never touches the ASR pipeline.
"""

import json
import re
import sys
from pathlib import Path

import numpy as np
import onnxruntime as rt
import soundfile as sf

ROOT = Path(__file__).resolve().parent.parent
TTS = ROOT / "models" / "tts_mr"
OUT = ROOT / "boli_proto" / "android" / "app" / "src" / "main" / "assets"
SPEAKER = 3          # one of nine; picked for clarity, see --list-speakers
NOISE, LENGTH, NOISE_W = 0.667, 1.0, 0.8

cfg = json.loads((TTS / "piper_mr.json").read_text(encoding="utf-8"))
PMAP: dict[str, list[int]] = cfg["phoneme_id_map"]
SR = cfg["audio"]["sample_rate"]


def phonemise(texts: list[str]) -> list[str]:
    import espeakng_loader
    from phonemizer.backend.espeak.wrapper import EspeakWrapper

    EspeakWrapper.set_library(espeakng_loader.get_library_path())
    EspeakWrapper.set_data_path(espeakng_loader.get_data_path())
    from phonemizer.backend import EspeakBackend

    backend = EspeakBackend("mr", preserve_punctuation=True, with_stress=True)
    return [p.strip() for p in backend.phonemize(texts)]


def to_ids(phonemes: str) -> list[int]:
    """Piper's convention: BOS, then every phoneme followed by a PAD, then EOS."""
    ids = list(PMAP["^"])
    for ch in phonemes:
        if ch in PMAP:
            ids += PMAP[ch]
            ids += PMAP["_"]
    ids += PMAP["$"]
    return ids


def synthesise(sess: rt.InferenceSession, ids: list[int]) -> np.ndarray:
    audio = sess.run(
        None,
        {
            "input": np.array([ids], dtype=np.int64),
            "input_lengths": np.array([len(ids)], dtype=np.int64),
            "scales": np.array([NOISE, LENGTH, NOISE_W], dtype=np.float32),
            "sid": np.array([SPEAKER], dtype=np.int64),
        },
    )[0]
    return audio.squeeze()  # [B,1,1,T] -> [T]


def phrases_from_data_dart() -> list[str]:
    """Read the phrase set straight out of the app so the two cannot drift."""
    src = (ROOT / "boli_proto" / "lib" / "data.dart").read_text(encoding="utf-8")
    found = []
    for field in ("marathi", "native"):
        found += re.findall(rf"{field}:\s*'([^']+)'", src)
    # Devanagari only, de-duplicated, order preserved.
    seen, out = set(), []
    for t in found:
        t = t.strip()
        if t and re.search(r"[ऀ-ॿ]", t) and t not in seen:
            seen.add(t)
            out.append(t)
    return out


def main() -> None:
    texts = phrases_from_data_dart()
    print(f"{len(texts)} phrases from lib/data.dart")

    phonemes = phonemise(texts)
    table = {t: to_ids(p) for t, p in zip(texts, phonemes)}

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "tts_phonemes.json").write_text(
        json.dumps(table, ensure_ascii=False), encoding="utf-8"
    )
    print(f"-> {OUT / 'tts_phonemes.json'} ({len(table)} entries)")

    if "--no-verify" in sys.argv:
        return

    # ---- verify: synthesise, then read it back with the app's own ASR --------
    tts = rt.InferenceSession(str(TTS / "piper_mr.onnx"), providers=["CPUExecutionProvider"])
    mdir = ROOT / "models" / "mr"
    pre = rt.InferenceSession(str(mdir / "nemo80.onnx"), providers=["CPUExecutionProvider"])
    asr = rt.InferenceSession(str(mdir / "model.arm64.onnx"), providers=["CPUExecutionProvider"])
    vocab = {
        int(l.rsplit(" ", 1)[1]): l.rsplit(" ", 1)[0]
        for l in (mdir / "vocab.txt").read_text(encoding="utf-8").splitlines()
        if l.strip()
    }
    blank = max(vocab)

    def transcribe_22k(wav22: np.ndarray) -> str:
        # Test-harness resampling only. Not used on device.
        # Do NOT pad with digital silence to "help" the recogniser. Tried it:
        # exact matches halved. The conformer normalises per mel bin across
        # time, and silence sits on the log(x + 2**-24) floor, so padding drags
        # every bin's mean down and degrades the whole utterance.
        n = int(len(wav22) * 16000 / SR)
        wav16 = np.interp(
            np.linspace(0, len(wav22) - 1, n), np.arange(len(wav22)), wav22
        ).astype(np.float32)
        w = wav16[None, :]
        wl = np.array([w.shape[1]], dtype=np.int64)
        f, fl = pre.run(["features", "features_lens"], {"waveforms": w, "waveforms_lens": wl})
        (lp,) = asr.run(["logprobs"], {"audio_signal": f, "length": fl})
        ids = lp[0, : int((fl[0] - 1) // 4 + 1)].argmax(-1)
        out, prev = [], -1
        for i in ids:
            if i != prev and i != blank:
                out.append(vocab[int(i)])
            prev = int(i)
        return "".join(out).replace("▁", " ").strip()

    sample_dir = ROOT / "reference" / "tts"
    sample_dir.mkdir(parents=True, exist_ok=True)

    def sim(a: str, b: str) -> float:
        """Character-level similarity; exact match is too strict a bar when the
        recogniser itself carries 10-15% WER."""
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

    exact = 0
    scores = []
    for i, t in enumerate(texts):
        audio = synthesise(tts, table[t])
        heard = transcribe_22k(audio)
        # CTC emits no punctuation, so compare on letters and spaces only.
        strip = lambda x: re.sub(r"[?।,.!]", "", x).strip()
        ok = strip(heard) == strip(t)
        exact += ok
        scores.append(sim(strip(heard), strip(t)))
        if i < 3:
            sf.write(sample_dir / f"{i:02d}.wav", audio, SR, subtype="PCM_16")
        print(f"  {'OK ' if ok else '   '} {len(audio)/SR:4.1f}s  said {t!r}  heard {heard!r}")

    print(f"\nround-trip exact match: {exact}/{len(texts)}")
    print(f"mean character similarity: {sum(scores) / len(scores):.3f}")
    print(f"phrases at or above 0.80 similarity: {sum(x >= .8 for x in scores)}/{len(scores)}")
    print(
        "\nNote: Piper is stochastic (noise_scale 0.667, noise_w 0.8), so this\n"
        "figure moves a little between runs. It is an intelligibility proxy —\n"
        "the recogniser itself carries 10-15% WER, so exact match understates."
    )


if __name__ == "__main__":
    main()
