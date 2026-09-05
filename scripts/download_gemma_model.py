#!/usr/bin/env python3
"""
download_gemma_model.py
Download on-device MediaPipe Gemma SLM models for SeedheBol.

Supported Models:
  1. gemma-2b: Gemma 2B IT INT4 CPU (~1.28 GB) [Default - Recommended for mobile CPU inference]
     Source: HuggingFace (metsman/gemma-2b-it-cpu-int4-org)
  2. gemma-3n: Gemma 3n E2B IT INT4 (~2.99 GB) [Multimodal MatFormer]
     Source: HuggingFace (realbyte/gemma-3n-E2B-it-int4-mediapipe)

Usage:
  python download_gemma_model.py                     # Downloads Gemma 2B INT4 (~1.28 GB)
  python download_gemma_model.py --model 3n          # Downloads Gemma 3n INT4 (~2.99 GB)
  python download_gemma_model.py --push-to-device    # Downloads and runs adb push to phone
  python download_gemma_model.py --verify-only       # Checks local model files
"""

import argparse
import os
import shutil
import subprocess
import sys
import time
import urllib.request

MODELS = {
    "2b": {
        "name": "Gemma 2B IT INT4 CPU",
        "filename": "gemma-2b-it-cpu-int4.bin",
        "url": "https://huggingface.co/metsman/gemma-2b-it-cpu-int4-org/resolve/main/gemma-2b-it-cpu-int4.bin",
        "expected_size_bytes": 1346513476,  # ~1.28 GB
    },
    "3n": {
        "name": "Gemma 3n E2B IT INT4",
        "filename": "gemma-3n-e2b-it-int4.task",
        "url": "https://huggingface.co/realbyte/gemma-3n-E2B-it-int4-mediapipe/resolve/main/gemma-3n-E2B-it-int4.task",
        "expected_size_bytes": 3136226711,  # ~2.99 GB
    }
}

DEFAULT_ADB_PATHS = [
    "adb",
    r"D:\Installed\AndroidSDK\platform-tools\adb.exe",
    os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
]


def find_adb():
    for path in DEFAULT_ADB_PATHS:
        if shutil.which(path):
            return path
        if os.path.exists(path):
            return path
    return None


def format_bytes(num_bytes):
    if num_bytes >= 1024 * 1024 * 1024:
        return f"{num_bytes / (1024**3):.2f} GB"
    elif num_bytes >= 1024 * 1024:
        return f"{num_bytes / (1024**2):.2f} MB"
    elif num_bytes >= 1024:
        return f"{num_bytes / 1024:.2f} KB"
    return f"{num_bytes} B"


def download_with_progress(url, dest_path, expected_size=None):
    os.makedirs(os.path.dirname(os.path.abspath(dest_path)), exist_ok=True)
    temp_path = dest_path + ".part"
    existing_size = os.path.getsize(temp_path) if os.path.exists(temp_path) else 0

    headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
    if existing_size > 0:
        headers["Range"] = f"bytes={existing_size}-"
        print(f"Resuming download from {format_bytes(existing_size)}...")

    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req) as resp:
            content_length = resp.headers.get("Content-Length")
            total_size = int(content_length) + existing_size if content_length else expected_size

            mode = "ab" if existing_size > 0 else "wb"
            downloaded = existing_size
            start_time = time.time()
            last_print = 0

            with open(temp_path, mode) as f:
                while True:
                    chunk = resp.read(1024 * 1024)  # 1MB chunks
                    if not chunk:
                        break
                    f.write(chunk)
                    downloaded += len(chunk)

                    now = time.time()
                    if now - last_print > 0.5 or (total_size and downloaded >= total_size):
                        elapsed = now - start_time
                        speed = (downloaded - existing_size) / elapsed if elapsed > 0 else 0
                        pct = (downloaded / total_size * 100) if total_size else 0
                        eta = (total_size - downloaded) / speed if (speed > 0 and total_size) else 0
                        print(
                            f"\rProgress: [{downloaded/1048576:.1f} MB / {total_size/1048576:.1f} MB] "
                            f"({pct:.1f}%) | Speed: {speed/1048576:.2f} MB/s | ETA: {int(eta)}s",
                            end="",
                            flush=True,
                        )
                        last_print = now

            print("\nDownload finished.")
    except Exception as e:
        print(f"\nError during download: {e}")
        return False

    if os.path.exists(dest_path):
        os.remove(dest_path)
    os.rename(temp_path, dest_path)
    return True


def push_to_android(file_path):
    adb = find_adb()
    if not adb:
        print("ERROR: adb executable not found. Please connect your device and specify adb path.")
        return False

    # Check connected devices
    res = subprocess.run([adb, "devices"], capture_output=True, text=True)
    lines = [line.strip() for line in res.stdout.splitlines() if line.strip() and not line.startswith("List of")]
    if not lines:
        print("No Android device or emulator currently attached to ADB.")
        print("When your phone is connected via USB with USB Debugging enabled, run:")
        print(f"  {adb} push {file_path} /sdcard/Android/data/com.boli.boli_proto/files/gemma/")
        return False

    target_dir = "/sdcard/Android/data/com.boli.boli_proto/files/gemma/"
    print(f"Creating directory on device: {target_dir}")
    subprocess.run([adb, "shell", f"mkdir -p {target_dir}"])

    filename = os.path.basename(file_path)
    print(f"Pushing {filename} to device...")
    push_res = subprocess.run([adb, "push", file_path, target_dir])
    if push_res.returncode == 0:
        print(f"Successfully pushed {filename} to {target_dir}")
        subprocess.run([adb, "shell", f"ls -lh {target_dir}"])
        return True
    else:
        print(f"adb push failed with code {push_res.returncode}")
        return False


def main():
    parser = argparse.ArgumentParser(description="Download MediaPipe Gemma SLM models for SeedheBol")
    parser.add_argument("--model", choices=["2b", "3n"], default="2b", help="Model variant: '2b' (~1.28GB) or '3n' (~2.99GB)")
    parser.add_argument("--outdir", default=None, help="Destination directory (default: <repo>/models/gemma)")
    parser.add_argument("--push-to-device", action="store_true", help="Automatically push model to phone via adb")
    parser.add_argument("--verify-only", action="store_true", help="Only verify existing downloaded models")
    args = parser.parse_args()

    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    outdir = args.outdir or os.path.join(repo_root, "models", "gemma")

    cfg = MODELS[args.model]
    dest_file = os.path.join(outdir, cfg["filename"])

    print("=" * 65)
    print(f"  SeedheBol Gemma SLM Downloader & Deployer")
    print("=" * 65)
    print(f"Model Choice : {cfg['name']} ({cfg['filename']})")
    print(f"Target Path  : {dest_file}")
    print(f"Source URL   : {cfg['url']}")
    print("=" * 65)

    if os.path.exists(dest_file):
        size = os.path.getsize(dest_file)
        print(f"File already exists: {dest_file} ({format_bytes(size)})")
        if size >= cfg["expected_size_bytes"] * 0.95:
            print("Integrity check: PASS (file is complete).")
            if args.push_to-device:
                push_to_android(dest_file)
            return
        else:
            print("File appears incomplete or truncated. Resuming/redownloading...")

    if args.verify_only:
        print("Verify only mode. Exiting.")
        return

    print("Starting download...")
    success = download_with_progress(cfg["url"], dest_file, cfg["expected_size_bytes"])

    if success:
        print(f"\nModel saved to: {dest_file}")
        if args.push_to_device:
            push_to_android(dest_file)
        else:
            print("\nTo push this model to your connected Android device, run:")
            print(f"  python scripts/download_gemma_model.py --model {args.model} --push-to-device")
    else:
        print("\nDownload failed or interrupted.")
        sys.exit(1)


if __name__ == "__main__":
    main()
