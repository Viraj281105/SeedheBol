# CLAUDE.md — Boli Prototype Build Brief

> **Read this file completely before writing any code.**
> This is a 7-hour, single-claim prototype. Scope discipline matters more than quality.

---

## 1. Mission

Prove exactly one thing: **AI4Bharat's IndicConformer ASR model runs fully offline on an Android phone.**

That is the whole prototype. Not a language-learning app. Not a UI. One claim, proven on video, with a public repo as the receipt.

**Why this claim:** the submission is for a phone-first hackathon where the product thesis is on-device Indic speech. Every screener will privately doubt that Indic ASR runs on a phone with no network. A polished UI proves nothing — it is the cheapest thing to fake. A phone in airplane mode transcribing Hindi is unfakeable.

**Reuse value:** this is also Phase A step 1 of the real build. Nothing here is throwaway.

---

## 2. Success tiers — ship whichever you reach

Build strictly in this order. Each tier is independently shippable. **Never skip ahead.**

| Tier | Cumulative time | Deliverable | Ships as |
|---|---|---|---|
| **T0** | 0:45 | Public repo with README, architecture, honest status | Prototype URL |
| **T1** | 2:45 | IndicConformer transcribing a WAV in Python on laptop | Video: model works |
| **T2** | 4:30 | Same transcription via `onnxruntime` (not the training framework) | Video: deployable path |
| **T3** | 6:30 | Flutter app, bundled WAV input, on-device inference | Video: runs on phone |
| **T4** | 7:15 | Live mic + airplane mode | **The money shot** |

*Flutter adds roughly 45 minutes over a bare Kotlin activity. That is a deliberate, accepted cost — see §3.5.*

**Realistic expectation: T2 or T3.** That is still ahead of nearly every other submission. T0 must be done in the first 45 minutes so the prototype field is never empty.

---

## 3. Non-goals — do not build these

Violating this list is the main way this build fails.

- ❌ No UI design. One button, one status line, one transcript field. Default theme. Do not open a colour picker.
- ❌ No Flutter state management (Riverpod, Bloc, Provider). A single `StatefulWidget` with `setState` is correct here.
- ❌ No Dart-side audio or inference packages. All of it lives in Kotlin — see §3.5.
- ❌ No TTS. No translation. No lessons. No gamification. No login.
- ❌ No pronunciation scoring / GOP / forced alignment. That is the real build, not the prototype.
- ❌ No model optimisation, quantisation, or NPU tuning. CPU inference is acceptable here.
- ❌ No streaming ASR. Record → stop → transcribe is fine.
- ❌ No multi-language switching. One language.
- ❌ No tests beyond the acceptance checks below.

If you find yourself writing a data class hierarchy, stop.

---

## 3.5 Flutter architecture — the boundary is the whole design

Flutter is the UI. **Kotlin does everything else.** Flutter cannot reach ONNX Runtime, and Dart audio packages will not give you the raw 16kHz PCM the model needs.

```
Flutter (Dart)                     Kotlin (Android)
──────────────                     ────────────────
button + status + transcript
        │
        │  MethodChannel "boli/asr"
        │  invokeMethod("transcribe")
        ├──────────────────────────►  AudioRecord (16kHz mono PCM16)
        │                             log-mel front-end
        │                             ONNX Runtime inference
        │                             greedy CTC decode
        ◄──────────────────────────  returns String
```

**Contract: exactly one channel, two methods, both returning `String`.**

| Method | Used in | Behaviour |
|---|---|---|
| `transcribeAsset` | T3 | Transcribes the bundled `sample.wav`. No permissions needed |
| `transcribeMic` | T4 | Records until stopped, then transcribes |

Do not stream partial results across the channel. Do not pass audio buffers to Dart. Do not add a third method. Every additional crossing is a place this build loses thirty minutes.

**Why Flutter is worth the ~45 minutes here:** the platform-channel skeleton is Phase A step 8 of the real build, and the real app is Flutter. This is not prototype scaffolding you throw away — it is the exact bridge the product needs, tested early.

---

## 4. Two paths — try Path A first, hard timebox

### Path A — sherpa-onnx (30-minute timebox)

`sherpa-onnx` (k2-fsa) ships prebuilt Android support, built-in kaldi-native-fbank feature extraction, and offline recognizer APIs. It has existing support for NeMo-family CTC models, and IndicConformer is a NeMo model.

**This matters more now that you're on Flutter:** sherpa-onnx publishes a Dart/Flutter package on pub.dev with Android support. If it loads an IndicConformer export, you skip both the mel front-end *and* most of the platform-channel work — check `pub.dev/packages/sherpa_onnx` as step zero.

**If sherpa-onnx can load an IndicConformer export, the entire timeline collapses to ~3 hours** because it solves the feature-extraction problem for you (see §6, Trap 1).

Spend **30 minutes maximum** checking:
1. Does sherpa-onnx list a compatible NeMo CTC model loader?
2. Are there pre-converted AI4Bharat / IndicConformer models in the sherpa-onnx model zoo or on HuggingFace?
3. Does their Android example app build?

**If yes → use it, skip to T3 directly.**
**If unclear after 30 minutes → abandon and go to Path B. Do not keep digging.**

### Path B — ONNX Runtime manual (the default assumption)

Export/obtain ONNX, write the mel front-end yourself, run via `onnxruntime-android`. Everything below assumes Path B.

---

## 5. Hour-by-hour plan

### T0 — Repo and README (0:00–0:45)

Do this **first**, before touching a model.

1. Create public GitHub repo `boli-proto` (or `boli`).
2. Commit the provided `README.md`.
3. Push. **The prototype URL field is now satisfied.**

**Acceptance:** repo URL loads for a logged-out visitor.

---

### T1 — Python baseline (0:45–2:45)

Goal: transcribe a WAV with IndicConformer on the laptop, and capture reference values you will check the Android port against.

1. Python venv. Install what the model card requires (likely `transformers` with `trust_remote_code=True`, and/or `nemo_toolkit[asr]`).
2. Pull `ai4bharat/indic-conformer-600m-multilingual` from HuggingFace.
3. **Inspect the repo contents before assuming anything** — list the actual files. Note whether ONNX files are present, their names, and whether the config exposes the preprocessor parameters.
4. Record a 5–8 second WAV of yourself speaking in the demo language. **16kHz, mono, 16-bit PCM.** Save as `assets/sample.wav`.
5. Transcribe it. Confirm the output is sensible.
6. **Dump reference artefacts to `reference/`:**
   - `melspec.npy` — the log-mel features fed to the encoder, with its exact shape
   - `logits.npy` — raw model output
   - `transcript.txt` — decoded text
   - `preproc_config.json` — every feature-extraction parameter you can read out of the model config

Step 6 is not optional. It is the only way to debug the Android port in §T3 without guessing.

**Acceptance:** `python scripts/transcribe.py assets/sample.wav` prints correct text, and `reference/` contains all four artefacts.

---

### T2 — ONNX path in Python (2:45–4:30)

Goal: same transcription, but through `onnxruntime`, proving the deployable path.

1. If ONNX is already in the HF repo, download it. Otherwise export from NeMo (`export()` on the ASR model).
2. **Inspect the graph before writing inference code:**
   ```
   python -c "import onnx; m=onnx.load('model.onnx'); print([(i.name,[d.dim_value or d.dim_param for d in i.type.tensor_type.shape.dim]) for i in m.graph.input]); print([o.name for o in m.graph.output])"
   ```
   Write the input/output names, shapes and dtypes into `docs/onnx-signature.md`. **Do not guess these.**
3. Determine whether the graph includes feature extraction or expects log-mel input. **It almost certainly expects log-mel** — this is Trap 1.
4. Extract the tokenizer/vocab into a plain text or JSON file you can bundle in the APK.
5. Run inference via `onnxruntime`, feeding `reference/melspec.npy`. Compare logits against `reference/logits.npy` — they should match closely.
6. Implement greedy CTC decode (argmax per frame, collapse repeats, drop blanks). Confirm the transcript matches.

**Acceptance:** `python scripts/transcribe_onnx.py` produces the same transcript as T1, and logits match the reference within tolerance.

---

### T3 — Flutter app, bundled WAV (4:30–6:30)

**Critical sequencing: do NOT start with the microphone.** Use a fixed WAV so you can compare against known-good reference values. Mic bugs and feature-extraction bugs look identical, and debugging both at once will cost you the build.

**Build the channel before the model.** Get a stub returning a hardcoded string end-to-end first — it takes ten minutes and it means that when real inference fails, you know it isn't the bridge.

1. `flutter create --platforms=android boli_proto` (Kotlin is the default Android language).
2. **Stub the channel.** `MethodChannel("boli/asr")` in `MainActivity.kt`, `transcribeAsset` returns `"hello"`. Flutter button calls it, displays the result. **Run on the Pixel. Do not proceed until this works.**
3. Add `onnxruntime-android` to `android/app/build.gradle.kts` dependencies.
4. Place the ONNX model, vocab, and `sample.wav` in **`android/app/src/main/assets/`** — not Flutter's `assets/`. See Trap 5.
5. Kotlin: read the WAV via `AssetManager`, parse the header, extract PCM to `FloatArray` normalised to [-1, 1].
6. **Implement the log-mel front-end in Kotlin** matching `reference/preproc_config.json` exactly. See Trap 1.
7. **Verify before proceeding:** log the first 20 mel values and compare against `reference/melspec.npy`. They must match to ~3 decimal places. **If they don't, stop and fix this — nothing downstream will work.**
8. Run ONNX Runtime inference. CPU execution provider.
9. Port the greedy CTC decoder from T2.6.
10. Return the transcript through the channel; render it in Flutter.

**Acceptance:** app launches on the Pixel 8, button transcribes the bundled WAV, output matches `reference/transcript.txt`.

---

### T4 — Mic and airplane mode (6:30–7:15)

1. `RECORD_AUDIO` in `AndroidManifest.xml`. Request at runtime from **Kotlin** — do not add `permission_handler` for one permission.
2. Add `transcribeMic` to the channel. `AudioRecord`: 16000 Hz, mono, `ENCODING_PCM_16BIT`.
3. Press-to-record, release-to-transcribe. No VAD, no streaming, no waveform.
4. Convert `ShortArray` → normalised `FloatArray` (divide by 32768), feed the existing T3 pipeline unchanged.
5. **Assert the actual sample rate at runtime** — see Trap 3.
6. **Enable airplane mode. Test again.** It must still work.

**Acceptance:** airplane mode on, speak Hindi or Marathi, transcript appears.

---

## 6. Known traps

### Trap 1 — The mel front-end mismatch ⚠️ **most likely failure**

NeMo's ONNX export typically contains **only the acoustic model**, not the `AudioToMelSpectrogramPreprocessor`. You must reimplement it in Kotlin, and any parameter mismatch produces confident garbage rather than an error.

Expected NeMo Conformer defaults — **verify these against the model config, do not trust them blindly:**

| Parameter | Typical value |
|---|---|
| sample_rate | 16000 |
| n_fft | 512 |
| win_length | 400 (25 ms) |
| hop_length | 160 (10 ms) |
| n_mels | 80 |
| window | hann |
| preemphasis | 0.97 |
| normalize | `per_feature` |
| log | true, with zero-guard |
| dither | 1e-5 (**set to 0 for reproducibility**) |

`per_feature` normalisation means mean/variance normalisation **per mel bin across time**, not global. Getting this wrong is the classic silent failure.

**Mitigation:** T3 step 6 exists solely to catch this. Do not skip it.

**Escape hatch:** if the Kotlin mel implementation is still wrong after 45 minutes, ship T2 and film the Python demo instead. A working laptop demo beats a broken phone demo.

### Trap 2 — Model size

The 600M multilingual model is large. If it won't load on device or is unusably slow, switch to a **per-language IndicConformer checkpoint (~120M)** and redo T1–T2 with it. Faster and smaller; the only cost is one language.

### Trap 3 — Sample rate

Everything is 16kHz. Phone mics often default to 44.1kHz. Set `AudioRecord` explicitly and assert the actual rate at runtime.

### Trap 4 — Time sink on Gradle/NDK

If `onnxruntime-android` fights the build for more than 30 minutes, that is a signal to reconsider Path A (sherpa-onnx ships a working AAR and a Flutter package).

### Trap 5 — Flutter assets are not Android assets ⚠️

Flutter's `assets/` directory is packed into `flutter_assets/` and is awkward to reach from Kotlin. **Put the model, vocab and WAV in `android/app/src/main/assets/`**, where `AssetManager` reads them directly. Declaring them in `pubspec.yaml` is wrong for this build.

Also: ONNX Runtime cannot memory-map a file inside an APK. You must copy the asset to `context.filesDir` on first launch and open it from there. Do this once and cache it.

### Trap 6 — WSL2 has no USB passthrough ⚠️

`adb` inside WSL2 will not see the Pixel 8 over USB without `usbipd-win` faff.

**Do this instead:** Python, NeMo and ONNX export run **in WSL2**. Android Studio, Gradle and `adb` run **natively on Windows**. Move files between them via `\\wsl$\` or `/mnt/c/`.

Alternative if you want everything in WSL2: `adb pair` / `adb connect` over Wi-Fi. Pixel 8 supports wireless debugging under Developer Options. Works, but adds a flake source on demo day.

### Trap 7 — APK size and rebuild time ⚠️

The 600M model in FP32 is roughly 2.4GB; even INT8 lands near 600MB. Bundling that into the APK makes every install take minutes, and you will install many times.

**Do this instead during development:** `adb push model.onnx /sdcard/Download/` and read from external storage. Rebuilds stay fast. Bundle into assets only at the very end, or skip bundling entirely and mention the download-on-first-run design in the README.

This alone may save you an hour of waiting.

### Trap 8 — The Pixel 8 is Tensor, not Snapdragon

Pixel 8 runs Google Tensor G3. **Do not chase the QNN execution provider** — that is Qualcomm, and it belongs to the event device, not this one. Use the CPU execution provider here; NNAPI is optional and often slower for this model class.

This is fine and expected. NPU work is Phase A of the real build, explicitly out of scope for the prototype (§3). The claim you are proving is *offline*, not *accelerated*.

8GB of RAM on the Pixel 8 means the 600M model will load comfortably.

---

## 7. Repo structure

```
boli-proto/
├── README.md                  # T0 — ship first
├── CLAUDE.md                  # this file
├── assets/
│   └── sample.wav             # 16kHz mono reference recording
├── reference/                 # T1 outputs — the debugging ground truth
│   ├── melspec.npy
│   ├── logits.npy
│   ├── transcript.txt
│   └── preproc_config.json
├── scripts/
│   ├── transcribe.py          # T1
│   ├── transcribe_onnx.py     # T2
│   └── export_onnx.py         # T2, if export needed
├── docs/
│   └── onnx-signature.md      # T2 — actual graph I/O, not guesses
└── boli_proto/                # T3 — flutter create
    ├── lib/
    │   └── main.dart          # button, status, transcript. ~80 lines
    └── android/app/src/main/
        ├── AndroidManifest.xml
        ├── assets/            # model.onnx, vocab, sample.wav (NOT flutter assets)
        └── kotlin/.../
            ├── MainActivity.kt    # MethodChannel handler
            ├── MelFrontend.kt     # the risky part
            ├── OnnxAsr.kt         # session + inference + CTC decode
            └── MicRecorder.kt     # T4 only
```

---

## 8. Working rules

1. **Commit at every tier boundary.** Tag them `t0`, `t1`, `t2`, `t3`, `t4`. If time runs out, the last tag is what ships.
2. **Verify against reference, never against intuition.** Every numerical step compares to `reference/`.
3. **Inspect before assuming.** Model file names, ONNX I/O signatures, config parameters — read them, don't guess.
4. **Timebox ruthlessly.** If a step exceeds its allocation by 50%, take the escape hatch and move on.
5. **Update the README status section at each tier.** It is the deliverable, not an afterthought.
6. **Log the elapsed time at each tier boundary** so the schedule stays honest.

---

## 9. Video capture (final 15 minutes)

60–90 seconds, unlisted YouTube.

1. Airplane mode toggled ON, **on camera**, clearly visible
2. Speak the sentence
3. Transcript appears
4. Show the model file on the device (file manager or a size label in-app)
5. One text card: model name, size, license, "100% on-device"

No voiceover pitching the product. The deck does that. The video does one job.

---

## 10. Confirmed environment

| | |
|---|---|
| **Test device** | Google Pixel 8 — Tensor G3, 8GB RAM. Real hardware, so **T4 is achievable** |
| **Dev environment** | WSL2 on Windows. Python/NeMo in WSL2, Android Studio + adb on Windows host (Trap 6) |
| **Demo language** | Hindi or Marathi. Both are Devanagari and both are covered by IndicConformer |
| **UI framework** | Flutter, per §3.5. Kotlin owns audio and inference |

**Language note:** record your `sample.wav` in whichever of the two you speak more naturally. Marathi is arguably the better choice — it is less represented in ASR demos than Hindi, and a Marathi transcription in Pune reads as deliberate rather than default.

**Not a concern for this prototype:** Hindi schwa deletion. That trap belongs to the G2P and TTS layers of the real build. This prototype is ASR-only, so it does not apply.
