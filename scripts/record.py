"""T1 — record assets/sample.wav at exactly 16kHz mono PCM16.

The model's front-end assumes 16kHz. Recording at 44.1k and resampling later
is a silent-failure path (Trap 3), so the rate is pinned here at capture time.
"""
import sys
from pathlib import Path
import sounddevice as sd
import soundfile as sf

SECONDS = float(sys.argv[1]) if len(sys.argv) > 1 else 6.0
OUT = Path(__file__).resolve().parent.parent / "assets" / "sample.wav"
OUT.parent.mkdir(parents=True, exist_ok=True)

sd.default.samplerate = 16000
sd.default.channels = 1

print(f"Recording {SECONDS:.0f}s at 16kHz mono. Speak Marathi now.")
audio = sd.rec(int(SECONDS * 16000), dtype="int16")
sd.wait()
sf.write(OUT, audio, 16000, subtype="PCM_16")

info = sf.info(OUT)
print(f"\nWrote {OUT}")
print(f"  {info.samplerate} Hz | {info.channels} ch | {info.subtype} | {info.duration:.2f}s")
assert info.samplerate == 16000 and info.channels == 1, "wrong capture format"
