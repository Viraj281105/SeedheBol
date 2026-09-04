"""Verify PaddleOCR (via RapidOCR's ONNX conversion) for the four Indic scripts
it actually supports: Devanagari (Hindi + Marathi share this script), Tamil,
Telugu, Kannada.

This is deliberately the same shape as scripts/verify_tts.py: prove the
models work in Python, off-device, before anything touches Kotlin or a
camera. Detection + orientation classification + recognition run as a real
pipeline (RapidOCR), not a mocked recognition-only check, because a det model
that mislocates the text line would pass a rec-only test and fail on device.

Test images are pre-rendered by scripts/render_ocr_test_images.py (run that
first). They are synthetic — clean text on a white background, not a photo —
which proves the ONNX graphs are wired up correctly (right dict, right
script, right pipeline). It does NOT prove anything about a phone camera's
photo of a hand-painted sign in bad light; that is a separate, harder problem
for the next phase, not this one.

Rendering is a browser screenshot, not PIL: this machine's Pillow has no
libraqm, so PIL.ImageDraw.text() draws Devanagari/Tamil/Telugu/Kannada
glyph-by-glyph with no reordering or conjunct forming, and the resulting
image is simply wrong independent of any OCR model -- the first run of this
script scored 0.21-0.59 similarity that way, on entirely correct models.

Usage:  python scripts/render_ocr_test_images.py     # once, or when phrases change
        python scripts/verify_ocr.py
"""

import json
from pathlib import Path

from rapidocr import RapidOCR
from rapidocr.utils.typings import LangDet, LangRec, ModelType, OCRVersion

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "reference" / "ocr_test_images"

# lang_type key (RapidOCR's Rec.lang_type) -> (sample phrases, enum).
# Sample phrases are pulled from the same set used to validate TTS/ASR, so a
# phrase that fails here can be cross-checked against how it sounds too.
PHRASES = json.loads((ROOT / "reference" / "phrases.json").read_text(encoding="utf-8"))

CASES = {
    "devanagari": (PHRASES["hi"][:4], LangRec.DEVANAGARI),
    "ta": (PHRASES["ta"][:4], LangRec.TA),
    "te": (PHRASES["te"][:4], LangRec.TE),
    "ka": (PHRASES["kn"][:4], LangRec.KA),
}


def similarity(a: str, b: str) -> float:
    if not a or not b:
        return 0.0
    d = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) + 1):
        d[i][0] = i
    for j in range(len(b) + 1):
        d[0][j] = j
    for i in range(1, len(a) + 1):
        for j in range(1, len(b) + 1):
            d[i][j] = min(d[i-1][j] + 1, d[i][j-1] + 1, d[i-1][j-1] + (a[i-1] != b[j-1]))
    return 1 - d[len(a)][len(b)] / max(len(a), len(b))


def main():
    summary = {}
    for lang, (phrases, lang_rec) in CASES.items():
        print(f"=== {lang} ===")
        # Kannada has no PP-OCRv5 recognition model (RapidOCR/PaddleOCR only
        # ship it for v4) -- everything else uses v5.
        rec_version = OCRVersion.PPOCRV4 if lang == "ka" else OCRVersion.PPOCRV5
        ocr = RapidOCR(params={
            "Rec.lang_type": lang_rec,
            "Rec.ocr_version": rec_version,
            "Rec.model_type": ModelType.MOBILE,
            # PP-OCRv5 only ships a "ch" (general-purpose) detector, not a
            # "multi" one -- the script-specific part is entirely in Rec.
            "Det.lang_type": LangDet.CH,
            "Det.ocr_version": OCRVersion.PPOCRV5,
            "Det.model_type": ModelType.MOBILE,
            "Cls.ocr_version": OCRVersion.PPOCRV5,
            "Cls.model_type": ModelType.MOBILE,
        })
        scores = []
        for i, text in enumerate(phrases):
            img_path = OUT / f"{lang}_{i:02d}.png"
            if not img_path.exists():
                raise SystemExit(
                    f"{img_path} missing — run scripts/render_ocr_test_images.py first"
                )

            result = ocr(str(img_path))
            heard = "".join(result.txts) if result and result.txts else ""
            s = similarity(heard, text)
            scores.append(s)
            print(f"  {s:.2f}  wrote {text!r}  read {heard!r}")

        mean = sum(scores) / len(scores)
        summary[lang] = mean
        print(f"  mean similarity: {mean:.3f}\n")

    print("=== summary ===")
    for lang, mean in summary.items():
        print(f"  {lang:12s} {mean:.3f}")


if __name__ == "__main__":
    main()
