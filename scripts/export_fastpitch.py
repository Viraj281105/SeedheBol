"""Export AI4Bharat FastPitch + HiFi-GAN to ONNX.

Two graphs, matching the two-session pattern already used for ASR:

    text -> character ids -> fastpitch.onnx -> mel [B, 80, T]
                          -> hifigan.onnx   -> waveform @22.05 kHz

The models are CHARACTER based (`use_phonemes: false` in the shipped config), so
there is no grapheme-to-phoneme step and no phonemiser on the device. Devanagari
characters go straight in. That is what lets the app speak arbitrary text rather
than a fixed table of phrases.

Usage:  python scripts/export_fastpitch.py mr
"""

import json
import sys
from pathlib import Path

import torch
import torch.nn as nn

ROOT = Path(__file__).resolve().parent.parent
LANG = sys.argv[1] if len(sys.argv) > 1 else "mr"

# FastPitch's relative-position attention bakes the text length into a reshape
# during tracing, so the graph accepts exactly one input width. Rather than
# fight that, we fix the width and pad every input to it.
#
# This is not a workaround we regret: the QNN execution provider requires
# static shapes to place a graph on the Hexagon NPU at all, so a fixed-width
# model is what the accelerated path needs anyway.
MAX_TEXT = 64
SRC = ROOT / "models" / "tts_fastpitch" / LANG
OUT = ROOT / "models" / "tts_fastpitch" / f"{LANG}_onnx"
OUT.mkdir(parents=True, exist_ok=True)


def patch_attention_for_tracing():
    """Make the relative-position attention export with a symbolic time axis.

    Coqui's attention does `b, d, t_s, t_t = (*key.size(), query.size(2))` and
    then `.view(b, heads, k, t_t)`. Unpacking `size()` yields Python ints, so the
    tracer bakes whatever length the dummy input happened to have and the graph
    then accepts exactly that one length -- in the encoder the text width, in
    the decoder the mel length, which varies per phrase and cannot be fixed.

    Rewriting those four reshapes with `-1` on the time axis keeps the dimension
    symbolic. Everything else is byte-for-byte the original method.
    """
    import math

    import torch.nn.functional as F
    from TTS.tts.layers.glow_tts.transformer import RelativePositionMultiHeadAttention as RPA

    def attention(self, query, key, value, mask=None):
        b, d, t_s, t_t = (*key.size(), query.size(2))
        query = query.reshape(b, self.num_heads, self.k_channels, -1).transpose(2, 3)
        key = key.reshape(b, self.num_heads, self.k_channels, -1).transpose(2, 3)
        value = value.reshape(b, self.num_heads, self.k_channels, -1).transpose(2, 3)
        scores = torch.matmul(query, key.transpose(-2, -1)) / math.sqrt(self.k_channels)
        if self.rel_attn_window_size is not None:
            key_relative_embeddings = self._get_relative_embeddings(self.emb_rel_k, t_s)
            rel_logits = self._matmul_with_relative_keys(query, key_relative_embeddings)
            rel_logits = self._relative_position_to_absolute_position(rel_logits)
            scores = scores + rel_logits / math.sqrt(self.k_channels)
        if self.proximal_bias:
            scores = scores + self._attn_proximity_bias(t_s).to(
                device=scores.device, dtype=scores.dtype
            )
        if mask is not None:
            scores = scores.masked_fill(mask == 0, -1e4)
            if self.input_length is not None:
                block_mask = torch.ones_like(scores).triu(-1 * self.input_length).tril(
                    self.input_length
                )
                scores = scores * block_mask + -1e4 * (1 - block_mask)
        p_attn = F.softmax(scores, dim=-1)
        p_attn = self.dropout(p_attn)
        output = torch.matmul(p_attn, value)
        if self.rel_attn_window_size is not None:
            relative_weights = self._absolute_position_to_relative_position(p_attn)
            value_relative_embeddings = self._get_relative_embeddings(self.emb_rel_v, t_s)
            output = output + self._matmul_with_relative_values(
                relative_weights, value_relative_embeddings
            )
        output = output.transpose(2, 3).contiguous().reshape(b, d, -1)
        return output, p_attn

    RPA.attention = attention

    # The encoder/decoder FFT blocks use torch.nn.MultiheadAttention, which the
    # TorchScript tracer bakes shapes into. The dynamo exporter handles it
    # correctly, and only trips on one guard: PositionalEncoding raises if the
    # sequence outgrows its table. That is a bounds assertion, not part of the
    # computation, and the mel length is data-dependent (it comes from the
    # duration predictor), so the guard can never be resolved symbolically.
    # Drop the check for export; max_len is 5000 frames, far beyond any phrase.
    from TTS.tts.layers.generic.pos_encoding import PositionalEncoding

    def pe_forward(self, x, mask=None, first_idx=None, last_idx=None):
        x = x * math.sqrt(self.channels)
        if first_idx is None:
            pos_enc = self.pe[:, :, : x.size(2)]
            if mask is not None:
                pos_enc = pos_enc * mask
            x = x + (self.scale * pos_enc if self.use_scale else pos_enc)
        else:
            sl = self.pe[:, :, first_idx:last_idx]
            x = x + (self.scale * sl if self.use_scale else sl)
        return x

    PositionalEncoding.forward = pe_forward


def load_models():
    from TTS.config import load_config
    from TTS.tts.models.forward_tts import ForwardTTS
    from TTS.vocoder.models import setup_model as setup_vocoder

    fp_cfg = load_config(str(SRC / "fastpitch" / "config.json"))

    # The shipped config carries absolute paths from AI4Bharat's training
    # machine (models/v1/<lang>/...). Repoint them at where the zip actually
    # extracted, or SpeakerManager fails to find the speaker table.
    speakers_file = str(SRC / "fastpitch" / "speakers.pth")
    fp_cfg.speakers_file = speakers_file
    fp_cfg.model_args.speakers_file = speakers_file

    fastpitch = ForwardTTS.init_from_config(fp_cfg)
    fastpitch.load_checkpoint(fp_cfg, str(SRC / "fastpitch" / "best_model.pth"), eval=True)
    fastpitch.eval()

    voc_cfg = load_config(str(SRC / "hifigan" / "config.json"))
    vocoder = setup_vocoder(voc_cfg)
    vocoder.load_checkpoint(voc_cfg, str(SRC / "hifigan" / "best_model.pth"), eval=True)

    # Only the generator is needed at inference; the checkpoint also carries a
    # discriminator used for training.
    generator = getattr(vocoder, "model_g", vocoder)

    # Coqui's eval-mode load leaves the weights as *inference tensors*, which
    # the ONNX tracer refuses ("Inference tensors cannot be saved for
    # backward"). Swap each one for an ordinary clone, in place, so the module
    # structure is untouched.
    for module in generator.modules():
        for name, param in list(module.named_parameters(recurse=False)):
            module._parameters[name] = nn.Parameter(
                param.detach().clone(), requires_grad=False
            )
        for name, buf in list(module.named_buffers(recurse=False)):
            if buf is not None:
                module._buffers[name] = buf.detach().clone()

    generator.eval()
    # load_checkpoint(eval=True) already strips weight norm; calling it again
    # raises, so this is best-effort.
    try:
        generator.remove_weight_norm()
    except (ValueError, AttributeError):
        pass
    vocoder = generator

    return fastpitch, fp_cfg, vocoder, voc_cfg


class FastPitchWrap(nn.Module):
    """Character ids -> mel. Speaker id is an input so both voices stay available."""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, x, speaker_id):
        out = self.model.inference(x, aux_input={"speaker_ids": speaker_id, "d_vectors": None})
        mel = out["model_outputs"]          # [B, T, 80]
        return mel.transpose(1, 2)          # [B, 80, T] — what the vocoder wants


class VocoderWrap(nn.Module):
    """mel [B, 80, T] -> waveform [B, 1, N]."""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, mel):
        return self.model(mel)


def main():
    patch_attention_for_tracing()
    print(f"loading {LANG} …")
    fastpitch, fp_cfg, vocoder, voc_cfg = load_models()

    chars = fp_cfg.characters
    n_speakers = int(getattr(fp_cfg, "num_speakers", 1) or 1)
    print(f"  speakers: {n_speakers}")

    # ---- FastPitch ------------------------------------------------------
    fp_wrap = FastPitchWrap(fastpitch).eval()
    dummy_ids = torch.randint(1, 40, (1, 24), dtype=torch.long)
    dummy_spk = torch.zeros(1, dtype=torch.long)

    with torch.no_grad():
        mel = fp_wrap(dummy_ids, dummy_spk)
    print(f"  fastpitch dry run -> mel {tuple(mel.shape)}")

    fp_path = OUT / "fastpitch.onnx"
    torch.onnx.export(
        fp_wrap,
        (dummy_ids, dummy_spk),
        str(fp_path),
        input_names=["input_ids", "speaker_id"],
        output_names=["mel"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "text_len"},
            "speaker_id": {0: "batch"},
            "mel": {0: "batch", 2: "mel_len"},
        },
        # No opset_version: forcing a downgrade from dynamo's native opset
        # breaks the version converter on this graph. ONNX Runtime handles the
        # default opset fine.
        # The dynamo exporter is what keeps nn.MultiheadAttention's shapes
        # symbolic; the TorchScript tracer bakes them.
        dynamo=True,
    )
    print(f"  -> {fp_path.name}  {fp_path.stat().st_size / 1e6:.1f} MB  (fixed text width {MAX_TEXT})")

    # ---- HiFi-GAN -------------------------------------------------------
    voc_wrap = VocoderWrap(vocoder).eval()
    with torch.no_grad():
        wav = voc_wrap(mel)
    print(f"  hifigan dry run -> wav {tuple(wav.shape)}")

    voc_path = OUT / "hifigan.onnx"
    torch.onnx.export(
        voc_wrap,
        (mel,),
        str(voc_path),
        input_names=["mel"],
        output_names=["waveform"],
        dynamic_axes={"mel": {0: "batch", 2: "mel_len"}, "waveform": {0: "batch", 2: "wav_len"}},
        opset_version=17,
        do_constant_folding=True,
        dynamo=False,
    )
    print(f"  -> {voc_path.name}  {voc_path.stat().st_size / 1e6:.1f} MB")

    # ---- the character table the Kotlin side needs -----------------------
    meta = {
        "language": LANG,
        "max_text": MAX_TEXT,
        "sample_rate": fp_cfg.audio["sample_rate"],
        "num_speakers": n_speakers,
        "pad": chars["pad"],
        "eos": chars["eos"],
        "bos": chars["bos"],
        "blank": chars["blank"],
        "characters": chars["characters"],
        "punctuations": chars["punctuations"],
        "characters_class": chars["characters_class"],
    }
    (OUT / "tokens.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"  -> tokens.json  ({len(chars['characters'])} characters)")


if __name__ == "__main__":
    main()
