# ONNX signature — IndicConformer `mr` (CTC branch)

Source: [`OpenVoiceOS/ai4bharat-indicconformer-mr-onnx`](https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-mr-onnx), converted from
[`ai4bharat/indicconformer_stt_mr_hybrid_ctc_rnnt_large`](https://huggingface.co/ai4bharat/indicconformer_stt_mr_hybrid_ctc_rnnt_large). MIT.

## Pipeline

```
PCM float32 [-1,1] @16kHz
   -> nemo80.onnx      (waveforms, waveforms_lens) -> (features, features_lens)
   -> model.int8.onnx  (audio_signal, length)      -> logprobs [B,T,257]
   -> greedy CTC       argmax, collapse repeats, drop <blk>
```

Two ONNX Runtime sessions and no hand-written DSP. The log-mel front-end is
itself an ONNX graph, so CLAUDE.md Trap 1 (mel parameter mismatch) cannot occur:
Android runs the identical graph the reference values were produced from.

## Graphs

### `nemo80.onnx` — 0.09 MB

Log-mel front-end, from `onnx-asr` (`preprocessors/nemo.py`, MIT). Uses the `STFT` operator.

- opset: `ai.onnx:17`
- ops: `Add`, `Cast`, `Concat`, `Div`, `Less`, `Log`, `MatMul`, `Mul`, `Pad`, `Range`, `ReduceSum`, `ReduceSumSquare`, `STFT`, `Shape`, `Slice`, `Sqrt`, `Squeeze`, `Sub`, `Transpose`, `Unsqueeze`, `Where`

| dir | name | dtype | shape |
|---|---|---|---|
| in | `waveforms` | FLOAT | `['batch_size', 'N']` |
| in | `waveforms_lens` | INT64 | `['batch_size']` |
| out | `features` | FLOAT | `['batch_size', 80, 'T']` |
| out | `features_lens` | INT64 | `['batch_size']` |

### `nemo80_conv.onnx` — 1.14 MB

**Fallback front-end.** Identical maths with `Conv`-based power spectrogram instead of `STFT` — use this if the ONNX Runtime Android build lacks the `STFT` kernel. Larger because the STFT basis is baked in as conv weights.

- opset: `ai.onnx:17`
- ops: `Add`, `Cast`, `Concat`, `Conv`, `Div`, `Less`, `Log`, `MatMul`, `Mul`, `Pad`, `Range`, `ReduceSum`, `ReduceSumSquare`, `Reshape`, `Shape`, `Slice`, `Sqrt`, `Squeeze`, `Sub`, `Transpose`, `Unsqueeze`, `Where`

| dir | name | dtype | shape |
|---|---|---|---|
| in | `waveforms` | FLOAT | `['batch_size', 'N']` |
| in | `waveforms_lens` | INT64 | `['batch_size']` |
| out | `features` | FLOAT | `['batch_size', 80, 'T']` |
| out | `features_lens` | INT64 | `['batch_size']` |

### `model.int8.onnx` — 137.68 MB

IndicConformer acoustic model, CTC branch only, int8-quantized. The `.nemo` checkpoint's RNNT branch is not exported and is not needed here.

- opset: `ai.onnx:16`
- ops: `Add`, `And`, `Cast`, `Concat`, `Constant`, `ConstantOfShape`, `ConvInteger`, `Div`, `DynamicQuantizeLinear`, `Equal`, `Expand`, `Floor`, `Gather`, `Less`, `LogSoftmax`, `MatMul`, `MatMulInteger`, `Mul`, `Not`, `Pad`, `Pow`, `Range`, `ReduceMean`, `Relu`, `Reshape`, `Shape`, `Sigmoid`, `Slice`, `Softmax`, `Split`, `Sqrt`, `Squeeze`, `Sub`, `Tile`, `Transpose`, `Unsqueeze`, `Where`

| dir | name | dtype | shape |
|---|---|---|---|
| in | `audio_signal` | FLOAT | `['audio_signal_dynamic_axes_1', 80, 'audio_signal_dynamic_axes_2']` |
| in | `length` | INT64 | `['length_dynamic_axes_1']` |
| out | `logprobs` | FLOAT | `['logprobs_dynamic_axes_1', 'logprobs_dynamic_axes_2', 257]` |

## config.json

```json
{
  "model_type": "nemo-conformer-ctc",
  "features_size": 80,
  "subsampling_factor": 4,
  "max_tokens_per_step": 10
}
```

## Vocabulary

- `models/mr/vocab.txt` — **257 tokens**, matching `logprobs` last dim
- format: `<token> <id>` per line, SentencePiece BPE, `▁` marks word start
- CTC blank is `<blk>`, explicitly the last entry (id 256) — no guessing needed
- first: <unk> या ्या ▁क ▁आ ▁प ▁स ▁म
- last:  ऌ ऺ <blk>

## Frame arithmetic

- `features_lens = n_samples // 160` (hop_length)
- `logprob_frames = (features_lens - 1) // 4 + 1` (subsampling_factor)
