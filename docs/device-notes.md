# Device notes — Pixel 8, ONNX Runtime 1.20.0

Findings that only appear on real hardware. Recorded because each one cost time
and none of them reproduce on the laptop.

## ConvInteger has no arm64 CPU kernel

**Symptom.** The app builds, installs, and launches. Session creation for the
acoustic model then fails:

```
ORT_NOT_IMPLEMENTED - Could not find an implementation for
ConvInteger(10) node with name '/pre_encode/conv/conv.0/Conv_quant'
```

**Cause.** The upstream `model.int8.onnx` is dynamically quantized across both
MatMul *and* Conv, giving 154 `MatMulInteger` nodes and 54 `ConvInteger` nodes
(3 per conformer block × 17 blocks, plus 2 in the pre-encode subsampling stack
and 1 in the decoder). ONNX Runtime ships a `ConvInteger` implementation for
x86 but registers no arm64 CPU kernel.

This is the worst shape of bug for this build: the desktop path never touches
it, and searching the shipped `libonnxruntime.so` for the string `ConvInteger`
finds it, because the symbol is present even where the kernel is not.

**Fix.** Re-quantize from fp32 with `op_types_to_quantize=["MatMul"]`, leaving
the convolution modules in fp32 — see `scripts/quantize_arm.py`. `MatMulInteger`
does have arm64 kernels. The conformer keeps ~85M of its ~120M parameters in
int8, since attention and feed-forward dominate the parameter count and the
conv modules are comparatively small.

## STFT is available, contrary to expectation

`nemo80.onnx` uses the opset-17 `STFT` operator, which reduced/mobile ONNX
Runtime builds often omit. The `com.microsoft.onnxruntime:onnxruntime-android`
AAR is the *full* build and does include it — `onnxruntime::STFT::Compute` and
the `STFT_Onnx_ver17` schema are both present, and the session loads on device.

`nemo80_conv.onnx` (Conv-based power spectrogram, same maths, no `STFT`) is kept
as a fallback but has not been needed.

## Assets must be uncompressed and copied out of the APK

ONNX Runtime cannot memory-map a file inside an APK. Assets are copied to
`filesDir` on first launch and opened from there. `noCompress += listOf("onnx",
"wav")` in `build.gradle.kts` keeps that copy a straight byte copy.

`OnnxAsr.resolveAsset` prefers `/sdcard/Download/boli/<name>` when present, so
a model can be swapped with `adb push` without reinstalling the APK.

## Zero padding degrades the conformer, it does not help it

While verifying TTS by feeding synthesised audio back through the ASR, the
round trip dropped leading words. The obvious fix — pad the utterance with
silence so the recogniser has a run-up — **halved** exact matches, 12/24 to
6/24.

The cause is the preprocessor's `per_feature` normalisation. It computes mean
and variance per mel bin across time, and digital silence sits on the
`log(x + 2**-24)` floor, so padding drags every bin's mean down and degrades
the whole utterance rather than just the padding.

Worth knowing before anyone reaches for silence padding as a VAD substitute.

## TTS and ASR sample rates never meet

Piper runs at 22050 Hz and hands PCM straight to `AudioTrack`. The recogniser
runs at 16000 Hz from `AudioRecord`. `OnnxTts.kt` contains no resampling of any
kind, and shares no code with `MicRecorder`/the mel front-end. The only
resampling in the repository lives in `scripts/tts_prepare.py`, where it exists
solely to drive the offline round-trip check.

## FastPitch, nine languages

`scripts/build_all_tts.sh` and `scripts/build_all_asr.sh` build the full stack:
FastPitch + HiFi-GAN and IndicConformer for hi, mr, bn, te, ta, gu, kn, or, ml.
Urdu is absent from the Indic-TTS release and stays on Piper.

**The models are character based.** `use_phonemes: false` in the shipped
config, so Devanagari goes straight into the graph. No espeak-ng, no G2P, no
precomputed phoneme table, and therefore no fixed vocabulary of speakable
phrases and no GPL-3.0 obligation. This is the single largest difference from
the Piper path.

**Speaker choice is per language, not global.** Each checkpoint carries two
speakers and they are not equally intelligible:

| | speaker 0 | speaker 1 |
|---|---|---|
| bn | 0.746 | **0.947** |
| te | 0.851 | **0.953** |
| gu | 0.900 | **0.958** |

`scripts/pick_voice.py` sweeps both and writes `reference/voices.json`. Read
that file rather than hardcoding a speaker id.

**Levels vary about fourfold between languages.** Bengali synthesises at
roughly a quarter of Kannada's RMS. Peak normalisation is worth applying for
its own sake — a quiet voice is unusable on a building site — but note that the
peak-norm entries in `voices.json` were chosen on a six-phrase sample and the
margin over raw level is small enough to be sampling noise. The speaker choice
is not: that effect is an order of magnitude larger.

**Quantisation does not solve the size.** MatMul-only int8 takes FastPitch from
217 MB to 180 MB, not to the ~68 MB a full pass would give, because the weight
sits in Conv1d and `ConvInteger` has no arm64 CPU kernel — the same gap that
broke the ASR model on the Pixel 8. Quality is unaffected (0.990 vs 0.988).
At ~236 MB per language, nine languages cannot ship in one APK; one bundled
plus per-language download is the only shape that fits.

**HiFi-GAN dominates synthesis cost.** For a 1.1 s phrase on desktop: FastPitch
120 ms, HiFi-GAN 548 ms. The vocoder is 82% of the work, and it is transposed
convolution, so it is also the part least likely to quantise. Synthesise a
lesson's phrases when the lesson opens, not when the button is tapped; the
existing PCM cache makes every repeat free.

**Verification is Coqui-free.** `scripts/verify_tts.py` reads the character
table from the exported `tokens.json`, which is what Kotlin reads too, so it
keeps working after the 1.5 GB source checkpoints are deleted. It checks
vocabulary coverage first: a character missing from the table is silently
dropped, and the model then says something other than what it was asked to,
with no error raised anywhere.
