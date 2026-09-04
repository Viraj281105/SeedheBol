# Handoff — proto to product

Written 2026-09-05, ~09:xx, immediately before the iQOO Hackathon City Battles
Pune leg (5–6 September). This document exists because the prototype phase is
ending and core development is moving to a new chat session with no memory of
how any of this was decided. Read this before touching code.

There is **no `CLAUDE.md`** in this repo despite earlier conversations
referencing one (a "Trap A" / "Trap E" numbering scheme). It was never
created. `README.md` has the pitch and model-stack table; `docs/device-notes.md`
has on-device gotchas; `docs/onnx-signature.md` has graph I/O shapes. This
file is the one that ties the whole build together. If you create a
`CLAUDE.md` in the new phase, port the "traps" section below into it first.

## What SeedheBol is

Offline, voice-first Indic-language learning for India's internal migrant
workers. No English pivot (Indian language → Indian language directly), fully
offline (privacy is the reason, not an optimisation — see README.md), operable
without reading. Full pitch is in `README.md`; don't re-derive it.

## What is actually built and verified right now

### ASR — IndicConformer, all 9 languages
`hi bn te ta gu kn or ml mr`. Urdu excluded (out of scope by choice). Per
language: `models/<lang>/model.arm64.onnx` (~187MB, MatMul-only int8),
`nemo80.onnx` (shared log-mel front-end, no language-specific weights),
`vocab.txt`. Built by `scripts/fetch_model.py` + `scripts/quantize_arm.py`,
batched by `scripts/build_all_asr.sh`. **The shipped `model.int8.onnx`
quantizes Conv too and cannot load on an arm64 device at all** —
`ORT_NOT_IMPLEMENTED - ConvInteger(10)` — ONNX Runtime's arm64 CPU EP has no
kernel for it. Always re-quantize MatMul-only.

### TTS — FastPitch + HiFi-GAN, all 9 languages
Same 9 languages. Character-based (`use_phonemes: false` in Coqui's config) —
no G2P, no espeak-ng, no GPL-3.0 obligation, no fixed phrase table. Any text
whose characters are in `tokens.json` can be spoken. Per language:
`models/tts_fastpitch/<lang>_onnx/{fastpitch.onnx(.data), hifigan.onnx,
tokens.json}`, built by `scripts/export_fastpitch.py`, batched by
`scripts/build_all_tts.sh`. Int8 build via `scripts/quantize_tts.py` (only
`hi` and `mr` done so far — 217MB→180MB, and collapses external-data into one
file, worth doing for every language you actually ship).

**Speaker choice is per-language, not global** — see `reference/voices.json`,
produced by `scripts/pick_voice.py`. Each checkpoint has 2 speakers and they
are not equally good: Bengali 0.746→0.947, Telugu 0.851→0.953, Gujarati
0.900→0.958 (mean intelligibility, speaker 0 vs speaker 1). Always read this
file rather than hardcoding speaker 0.

**It sounds noticeably robotic**, and this is expected, not a bug to chase
right now: FastPitch is a deterministic non-autoregressive model (one mean
pitch/duration contour per input, no sampled variation), unlike Piper's VITS
which is flow-based and samples. The ONNX export exposes no pitch/energy
control. Fixing this for real means either fine-tuning HiFi-GAN on FastPitch's
own predicted mels (needs training compute + AI4Bharat's dataset, probably
out of reach) or swapping in a different vocoder. Don't spend hackathon time
on it; do plan for it in the product phase if voice quality becomes a
differentiator judges/users care about.

**9 languages cannot ship in one APK.** ~236MB TTS + ~186MB ASR per language
× 9 ≈ 3.8GB. Quantization doesn't fix this — it's a 17% cut, not 75%, because
the weight is in Conv1d layers and `ConvInteger` has no arm64 kernel (same
wall as the ASR model). The architecture has to be: one language bundled,
the rest downloaded on demand. This was scoped but not built. See "Space
optimization" below for what's actually worth trying.

**Verification methodology** (`scripts/verify_tts.py`): feed synthesis back
through the app's own ASR and score edit-distance similarity. This measures
*correctness* (are the phonemes right), not naturalness — a monotone robot
voice scores perfectly here if it says the right words. Don't cite these
numbers as evidence the voice sounds good.

### App integration — Marathi only, FastPitch is live
`boli_proto/android/app/src/main/kotlin/com/boli/boli_proto/FastPitchTts.kt`
replaced Piper (`OnnxTts.kt`, kept in the repo, unused, uninstantiated —
revert is one line in `MainActivity.kt`). Validated on a physical Pixel 8:
builds, plays audio, ASR still works. The whole app is currently hardcoded to
one resident language (`data.dart`: "only Marathi is actually resident on
device") — there is no language-switching UI wired to the native side at all,
despite the onboarding screen listing all 9+ languages cosmetically.

**Device model deployment**: large model files are `adb push`ed to
`/sdcard/Android/data/com.boli.boli_proto/files/`, not bundled in the APK
(see `resolveAsset()` in both `OnnxAsr.kt` and `FastPitchTts.kt` — pushed file
wins over bundled asset). `scripts/push_tts.sh <lang>` handles this for TTS
and also copies `tokens.json` into the Android assets tree (that one has to
happen *before* `flutter build`, unlike the two model files).

**CRITICAL, hit twice this session: reinstalling the APK can wipe
`/sdcard/Android/data/com.boli.boli_proto/files/`.** Not just uninstall —
`adb install -r` did it too, on this device, mid-session. This deletes
`model.arm64.onnx` (ASR) as well as the TTS models, since none of them are
bundled in the APK. **After any install, before testing, verify with:**
```bash
adb shell ls -la /sdcard/Android/data/com.boli.boli_proto/files/
```
If it's empty or short, re-push everything:
```bash
export MSYS_NO_PATHCONV=1   # Git Bash mangles /sdcard/... paths otherwise
adb push models/mr/model.arm64.onnx /sdcard/Android/data/com.boli.boli_proto/files/model.arm64.onnx
bash scripts/push_tts.sh mr
```
Do this *before* telling anyone TTS/ASR is broken — the first symptom looks
exactly like a code regression and isn't one.

### OCR — models downloaded and verified, NOT integrated, one real blocker
Set up in the final hour before this handoff, scoped deliberately to
"verify the models work in Python," same as TTS/ASR before they touched
Kotlin. **No camera pipeline, no Kotlin bridge, nothing app-side exists.**

PaddleOCR's Indic coverage, via RapidOCR's ONNX conversion
(`pip install rapidocr`, ~7MB per recognition model, manifest at
`.venv-ocr/Lib/site-packages/rapidocr/default_models.yaml` or
https://github.com/RapidAI/RapidOCR/blob/main/python/rapidocr/default_models.yaml):
four scripts, not four languages — **Devanagari covers both Hindi and
Marathi**, plus Tamil, Telugu, Kannada (PP-OCRv4 only; v5 has no Kannada
recognition model). Bengali, Gujarati, Odia, Malayalam have **no PaddleOCR
model at all** — Tesseract is the only fallback path for those four, and
nothing has been done there yet.

Exact usage (`scripts/verify_ocr.py`):
```python
from rapidocr import RapidOCR
from rapidocr.utils.typings import LangDet, LangRec, ModelType, OCRVersion
ocr = RapidOCR(params={
    "Rec.lang_type": LangRec.DEVANAGARI,   # or .TA, .TE, .KA
    "Rec.ocr_version": OCRVersion.PPOCRV5,  # PPOCRV4 for Kannada
    "Rec.model_type": ModelType.MOBILE,
    "Det.lang_type": LangDet.CH,   # there is no "multi" det model in PP-OCRv5, only "ch"
    "Det.ocr_version": OCRVersion.PPOCRV5,
    "Det.model_type": ModelType.MOBILE,
    "Cls.ocr_version": OCRVersion.PPOCRV5,
    "Cls.model_type": ModelType.MOBILE,
})
```
Params must be the enum values (`LangRec.DEVANAGARI`), not bare strings — bare
strings raise `TypeError: must be Enum Type`.

**THE BLOCKER: Devanagari does not detect at all, at default thresholds, on
any test image.** Tamil/Telugu/Kannada correctly detect+recognize *short*
phrases (longer phrases are missed by the shared detector, not misread — a
separate, smaller issue). Devanagari scored 0.000 across every phrase, every
font weight, every font size tried. Pushing `Det.thresh`/`Det.box_thresh`
down to 0.1 makes it find fragments ("स्त", plus junk), not a clean box —
this reads as the general-purpose "ch" detector genuinely struggling with
Devanagari's connected head-stroke (shirorekha), not a threshold or rendering
bug. Reproduced across multiple isolated tests, not a fluke.

**This is the one script that matters most** — Devanagari is Hindi *and*
Marathi, i.e. the language already shipping — **and it's the one that
doesn't work.** Do not start OCR integration assuming Devanagari works.
Options for the new phase, untried: a different/larger PP-OCR detector
variant (server instead of mobile), tuned `unclip_ratio`/box merging,
training or finding a Devanagari-specific detector, or accepting
Tesseract for Devanagari too despite it being slower/heavier.

**Test harness gotchas worth knowing before you rebuild this:**
- This machine's Pillow has no `libraqm` — `PIL.ImageDraw.text()` draws
  Devanagari/Tamil/Telugu/Kannada glyph-by-glyph with no conjunct forming or
  reordering. The image itself is wrong, independent of any OCR model. First
  verification run scored 0.21–0.59 on entirely correct models because of
  this. Fix: render through a real browser engine instead
  (`scripts/render_ocr_test_images.py` uses headless Edge), which does full
  complex-script shaping.
- Headless Edge on this machine **intermittently fails the `file://`
  navigation outright, exits 0 regardless, and screenshots its own dark
  error-page graphic instead** — a perfectly valid, decodable PNG, just not
  the content you asked for. Needed two fixes: `--allow-file-access-from-files`
  (Chromium blocks a `file://` page from loading a subresource — the
  `@font-face`, here — from a different directory) and a retry loop that
  checks the render is white-background (`mean pixel value > 200`) before
  accepting it, since byte-size alone isn't a reliable enough signal. See
  `_looks_like_our_page()` in `scripts/render_ocr_test_images.py` for the
  exact heuristic and don't trust a "successful" render until it's passed
  that check.

### What's genuinely working end-to-end, verifiable today
- ASR in airplane mode, RTF ~0.065, on-device, Marathi
- FastPitch TTS on-device, Marathi, arbitrary text (not just 22 phrases)
- The Duolingo-metaphor UI redesign (see README.md's stated design principles)
- 9-language ASR + TTS models built, quantized, verified off-device
- 4-script OCR recognition models downloaded and verified off-device, with
  one language (Devanagari) blocked at the detection stage

## What's unimplemented or knowingly faked (from the last full audit)
- Only Marathi is resident on device; the language picker is cosmetic
- App UI runs entirely in English, which contradicts the product's own
  literacy-optional design principle — worth fixing early in the product
  phase since it's the kind of contradiction a sharp reviewer calls out
- Progress screen shows hardcoded numbers, not real state
- 5 of 10 "situations" (the content unit — see README.md) are placeholders
- Only the microphone is used of any phone sensor/capability — Office Kit /
  "creative phone use" (25% of the hackathon rubric combined) is completely
  untouched. **What HackTracker/Office Kit actually require was unknown as of
  this handoff — it was going to be revealed on-site.** Find out first thing
  and treat it as high-priority, contained, non-ML-risk work — it doesn't
  touch anything above and can't break the working demo.
- Release-build APK previously SIGABRT'd on TTS (Piper era); unconfirmed
  whether this still reproduces with FastPitch. Debug builds are what's been
  tested throughout. Check this before assuming a release build is safe to
  demo from.

## Space optimization — tried, and what's actually left
`scripts/quantize_tts.py` (MatMul-only int8) is not the win it looks like:
217MB→180MB, not the ~68MB a full int8 pass would give, because the weight is
in Conv1d and there's no arm64 kernel for it — this is the same wall as the
ASR model, hit twice now. Untried, in rough order of effort:
1. **fp16 instead of int8** — halves precision without hitting the
   `ConvInteger` gap (it's not integer quantization, so existing float
   kernels just run at half width). Should get FastPitch toward ~110MB,
   HiFi-GAN toward ~28MB, near-zero quality loss. Highest value, cheapest to
   try, genuinely untested — do this first if size is still a problem.
2. **A shared HiFi-GAN across languages** — it's a mel-to-waveform vocoder,
   mostly acoustic rather than linguistic; one well-trained HiFi-GAN might
   generalize across languages. Untested; cheap to check by feeding one
   language's mel into another's vocoder and listening.
3. **QNN/NPU int8** on Snapdragon hardware (there's a OnePlus 10 Pro
   available) — the Hexagon HTP has real int8 Conv kernels. Real fix for the
   Conv-heavy HiFi-GAN, but needs QDQ calibration, static shapes, and
   per-chipset context binary caching. Bigger lift.
4. **A single multilingual acoustic model** instead of 9 checkpoints — the
   "big shift," last resort. AI4Bharat doesn't ship one; Meta's MMS-TTS
   might cover multiple Indic languages in one model at a likely quality
   cost. Untested.

**Per-user download shape is the correct architecture regardless of the
above**: a learner going Hindi→Marathi needs ~180MB (L1 TTS only — no reason
to ship ASR for a language you're not asked to speak) + ~420MB (L2 TTS+ASR)
≈ 600MB, not 3.8GB for all nine. This routing decision was agreed but not
built.

## Hackathon context
iQOO Hackathon 2026, City Battles, Pune leg, 5–6 September — this project was
**shortlisted**. Judging weights (from earlier research, verify on-site if
possible): End product 30%, Novelty 20%, Creative phone use 15%
(HackTracker), Technical depth 15%, Office Kit 10% (HackTracker), Demo 10%.
25% of the total is device-telemetry-driven and currently untouched — see
"Office Kit" above.

## Recommended first moves in the new session
1. Find out what HackTracker/Office Kit actually requires — likely the
   single highest-value, lowest-risk thing available, since it recovers 25%
   without touching the working demo path.
2. Decide the per-language download architecture properly (bundle Marathi,
   fetch others on demand) rather than leaving it implicit.
3. Try fp16 export before spending more time on int8 — cheap, might resolve
   the size problem outright.
4. Devanagari OCR detection needs a real fix before OCR integration starts —
   don't build camera UI on top of a detector that doesn't see the primary
   script.
5. Fix the English-only UI and hardcoded Progress numbers early — both are
   visible-on-screen contradictions of the product's own pitch.

Everything referenced above is committed. Recent relevant commits:
`c5b32e9` (OCR setup), `7cbaa2d` (FastPitch integration),
`678034a`/`57fe53d` (voice selection + findings write-up),
`b19219e`/`d640b79` (FastPitch export, all 9 languages).
