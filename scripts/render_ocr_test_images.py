"""Render OCR test phrases with PROPER Indic script shaping.

Pillow on this machine has no libraqm, so PIL.ImageDraw.text() draws
Devanagari/Tamil/Telugu/Kannada glyph-by-glyph with no reordering or
conjunct forming -- the image itself is wrong, independent of any OCR model.
A browser's text layout engine does full complex-script shaping, so this
renders each phrase as an HTML page and rasterises it with headless Edge,
which is already installed on this machine, rather than pulling in a new
shaping stack (uharfbuzz + freetype) under time pressure.

Usage:  python scripts/render_ocr_test_images.py
"""

import json
import shutil
import subprocess
import tempfile
import time
import uuid
from pathlib import Path

from PIL import Image
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
FONTS = ROOT / "reference" / "ocr_fonts"
OUT = ROOT / "reference" / "ocr_test_images"
OUT.mkdir(parents=True, exist_ok=True)

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

PHRASES = json.loads((ROOT / "reference" / "phrases.json").read_text(encoding="utf-8"))

CASES = {
    "devanagari": ("devanagari.ttf", PHRASES["hi"][:4]),
    "ta": ("tamil.ttf", PHRASES["ta"][:4]),
    "te": ("telugu.ttf", PHRASES["te"][:4]),
    "ka": ("kannada.ttf", PHRASES["kn"][:4]),
}

WIDTH, HEIGHT = 900, 160

HTML = """<!doctype html><html><head><meta charset="utf-8"><style>
@font-face {{ font-family: F; src: url("{font_uri}"); }}
html,body {{ margin:0; padding:0; background:#fff; }}
body {{ width:{w}px; height:{h}px; display:flex; align-items:center;
        justify-content:center; }}
div {{ font-family:F; font-size:56px; color:#000; white-space:nowrap; }}
</style></head><body><div>{text}</div></body></html>"""


def _looks_like_our_page(png_path: Path) -> bool:
    """True if this is a white-background render, false if it's blank/missing
    or Edge's own dark error page.

    On this machine, headless Edge intermittently fails to navigate to the
    file:// URL -- exit code 0 regardless -- and the screenshot instead
    captures Edge's own dark "page didn't load" graphic, which is a
    perfectly valid, decodable PNG. Byte size alone once looked like a
    reliable tell (the error page always compressed to ~12-13KB) but a
    long phrase can legitimately land in that range too, so this checks
    the one property that cannot be confused: our page is white
    (mean channel value in the 240s), the error page is dark grey (~40s).
    """
    if not png_path.exists():
        return False
    arr = np.asarray(Image.open(png_path).convert("L"))
    return float(arr.mean()) > 200


def render(text: str, font_path: Path, out_png: Path, attempts: int = 6):
    html_path = out_png.with_suffix(".html")
    font_uri = font_path.resolve().as_uri()
    html_path.write_text(
        HTML.format(font_uri=font_uri, w=WIDTH, h=HEIGHT, text=text), encoding="utf-8"
    )
    try:
        for attempt in range(1, attempts + 1):
            # A fresh --user-data-dir per attempt: headless Edge on this
            # machine intermittently fails the file:// navigation outright
            # (still exits 0) and screenshots its own dark error page
            # instead -- a real, valid PNG, just not ours. Retrying with a
            # clean profile is what actually clears it; a fixed delay alone
            # did not.
            profile_dir = Path(tempfile.gettempdir()) / f"edge-ocr-{uuid.uuid4().hex}"
            try:
                subprocess.run(
                    [
                        EDGE, "--headless", "--disable-gpu",
                        "--allow-file-access-from-files",
                        f"--user-data-dir={profile_dir}",
                        f"--screenshot={out_png.resolve()}",
                        f"--window-size={WIDTH},{HEIGHT}",
                        "--hide-scrollbars",
                        "--force-device-scale-factor=1",
                        html_path.resolve().as_uri(),
                    ],
                    check=True, capture_output=True, timeout=30,
                )
            finally:
                shutil.rmtree(profile_dir, ignore_errors=True)

            time.sleep(0.5)
            if _looks_like_our_page(out_png):
                if attempt > 1:
                    print(f"    (took {attempt} attempts)")
                return
            print(f"    attempt {attempt} got Edge's error page, retrying...")
        raise RuntimeError(f"{out_png.name}: never got a real render in {attempts} attempts")
    finally:
        try:
            html_path.unlink()
        except PermissionError:
            pass  # Edge or AV still briefly holds the handle; harmless scratch file.


def main():
    for lang, (font_file, phrases) in CASES.items():
        for i, text in enumerate(phrases):
            out_png = OUT / f"{lang}_{i:02d}.png"
            print(f"{lang}_{i:02d}.png <- {text}")
            render(text, FONTS / font_file, out_png)


if __name__ == "__main__":
    main()
