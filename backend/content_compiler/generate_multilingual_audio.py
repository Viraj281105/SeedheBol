#!/usr/bin/env python3
"""
tools/content_compiler/generate_multilingual_audio.py
=====================================================
Batch synthesizes comprehensive multilingual audio datasets for Seedhebol:
1. Zero-Literacy UI Voice Prompts (Hindi L1 navigation & feedback)
2. Corridor 1: Tamil (Construction, Safety, Daily survival)
3. Corridor 2: Malayalam (Healthcare nurse triage, Emergency)
4. Corridor 3: Kannada (Logistics, Delivery warehouse, Transit)
5. Telugu (Construction, Transit, Food)
6. Bengali (Migrant worker transit, Wages)

Outputs:
- 22.05 kHz 16-bit Mono PCM WAVs in `tools/content_compiler/data/audio_samples/`
- Full metadata manifest: `multilingual_audio_manifest.json`
"""

import asyncio
import json
import logging
import os
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("GenerateMultilingualAudio")

DATASET: List[Dict[str, Any]] = [
    # =========================================================================
    # 1. UI Prompts & Zero-Literacy Navigation (Hindi L1)
    # =========================================================================
    {
        "sample_id": "ui_01_tap_to_speak",
        "category": "ui_prompts",
        "lang_code": "hi",
        "voice": "hi-IN-MadhurNeural",
        "text": "बोलने के लिए नीचे दिए गए माइक बटन को दबाएं।",
        "translation_en": "Tap the microphone button below to speak.",
        "speaker_role": "System Voice Guide",
    },
    {
        "sample_id": "ui_02_listen_carefully",
        "category": "ui_prompts",
        "lang_code": "hi",
        "voice": "hi-IN-SwaraNeural",
        "text": "पहले ध्यान से आवाज सुनें, फिर वैसा ही बोलने की कोशिश करें।",
        "translation_en": "Listen carefully to the voice first, then try speaking the same way.",
        "speaker_role": "System Voice Guide",
    },
    {
        "sample_id": "ui_03_pronunciation_success",
        "category": "ui_prompts",
        "lang_code": "hi",
        "voice": "hi-IN-SwaraNeural",
        "text": "बहुत बढ़िया! आपका उच्चारण बिल्कुल सही है।",
        "translation_en": "Excellent! Your pronunciation is completely correct.",
        "speaker_role": "Diagnostic Feedback",
    },
    {
        "sample_id": "ui_04_retroflex_guidance",
        "category": "ui_prompts",
        "lang_code": "hi",
        "voice": "hi-IN-MadhurNeural",
        "text": "ध्यान दें! इस अक्षर के लिए अपनी जीभ को तालू के ऊपरी हिस्से पर मोड़कर बोलें।",
        "translation_en": "Notice! For this consonant, curl your tongue to the upper palate.",
        "speaker_role": "Phonetic Diagnostic Guide",
    },
    {
        "sample_id": "ui_05_camera_scan_intro",
        "category": "ui_prompts",
        "lang_code": "hi",
        "voice": "hi-IN-SwaraNeural",
        "text": "सामने लगे बोर्ड या पर्ची की फोटो खींचें, हम तुरंत पढ़कर सिखाएंगे।",
        "translation_en": "Take a photo of the signboard or slip, we will read and teach it instantly.",
        "speaker_role": "Camera OCR Guide",
    },
    {
        "sample_id": "ui_06_commute_mode_active",
        "category": "ui_prompts",
        "lang_code": "hi",
        "voice": "hi-IN-MadhurNeural",
        "text": "सफर मोड चालू है। फोन जेब में रख लीजिए और कानों से सुनकर सीखिए।",
        "translation_en": "Commute mode is active. Keep phone in pocket and learn by listening.",
        "speaker_role": "Commute Player",
    },

    # =========================================================================
    # 2. Tamil (Corridor 1: Chennai / Coimbatore)
    # =========================================================================
    {
        "sample_id": "ta_01_work_greeting",
        "category": "tamil",
        "lang_code": "ta",
        "voice": "ta-IN-ValluvarNeural",
        "text": "வணக்கம் மேஸ்திரி, இன்னைக்கு வேலை எங்கே ஆரம்பிக்கலாம்?",
        "translation_en": "Hello supervisor, where should we start work today?",
        "speaker_role": "Site Supervisor",
    },
    {
        "sample_id": "ta_02_water_query",
        "category": "tamil",
        "lang_code": "ta",
        "voice": "ta-IN-PallaviNeural",
        "text": "அண்ணா, இந்த தளத்துல சுத்தமான குடிதண்ணீர் எங்கே கிடைக்கும்?",
        "translation_en": "Brother, where can I find clean drinking water on this floor?",
        "speaker_role": "Worker",
    },
    {
        "sample_id": "ta_03_bus_platform",
        "category": "tamil",
        "lang_code": "ta",
        "voice": "ta-IN-PallaviNeural",
        "text": "கோயம்பேடு போற பஸ் எந்த பிளாட்பாரத்துல வரும்?",
        "translation_en": "Which platform does the Koyambedu bus arrive at?",
        "speaker_role": "Commuter",
    },
    {
        "sample_id": "ta_04_hospital_emergency",
        "category": "tamil",
        "lang_code": "ta",
        "voice": "ta-IN-ValluvarNeural",
        "text": "எனக்கு நெஞ்சு வலிக்குது, பக்கத்துல அரசு ஆஸ்பத்திரி எங்கே இருக்கு?",
        "translation_en": "I have chest pain, where is the nearby government hospital?",
        "speaker_role": "Patient",
    },
    {
        "sample_id": "ta_05_safety_gear",
        "category": "tamil",
        "lang_code": "ta",
        "voice": "ta-IN-ValluvarNeural",
        "text": "சேஃப்டி ஹெல்மெட் இல்லாம யாரும் ஸ்கஃபோல்டிங் மேல ஏறக்கூடாது!",
        "translation_en": "No one should climb the scaffolding without a safety helmet!",
        "speaker_role": "Safety Officer",
    },

    # =========================================================================
    # 3. Malayalam (Corridor 2: Kochi / Ernakulam Healthcare & Site)
    # =========================================================================
    {
        "sample_id": "ml_01_nurse_triage",
        "category": "malayalam",
        "lang_code": "ml",
        "voice": "ml-IN-SobhanaNeural",
        "text": "നിങ്ങൾക്ക് എവിടെയാണ് വേദനയുള്ളത്? ദയവായി ചൂണ്ടിക്കാണിക്കൂ.",
        "translation_en": "Where do you feel pain? Please point to it.",
        "speaker_role": "Staff Nurse",
    },
    {
        "sample_id": "ml_02_medicine_timing",
        "category": "malayalam",
        "lang_code": "ml",
        "voice": "ml-IN-SobhanaNeural",
        "text": "ഈ ഗുളിക ഭക്ഷണത്തിന് ശേഷം രാവിലെയും രാത്രിയും കഴിക്കണം.",
        "translation_en": "Take this tablet after meals, once in morning and once at night.",
        "speaker_role": "Staff Nurse",
    },
    {
        "sample_id": "ml_03_boiled_water",
        "category": "malayalam",
        "lang_code": "ml",
        "voice": "ml-IN-MidhunNeural",
        "text": "ചേട്ടാ, കുടിക്കാൻ തിളപ്പിച്ച വെള്ളം എവിടെയാ ഉള്ളത്?",
        "translation_en": "Brother, where is boiled drinking water kept?",
        "speaker_role": "Patient / Worker",
    },
    {
        "sample_id": "ml_04_emergency_alert",
        "category": "malayalam",
        "lang_code": "ml",
        "voice": "ml-IN-MidhunNeural",
        "text": "സൈറ്റിൽ ഒരാൾക്ക് കണ്ണിന് പരിക്കേറ്റു, പെട്ടെന്ന് ആംബുലൻസ് വിളിക്കൂ!",
        "translation_en": "Someone suffered an eye injury on site, call an ambulance immediately!",
        "speaker_role": "Coworker",
    },

    # =========================================================================
    # 4. Kannada (Corridor 3: Bengaluru Logistics & Commute)
    # =========================================================================
    {
        "sample_id": "kn_01_delivery_address",
        "category": "kannada",
        "lang_code": "kn",
        "voice": "kn-IN-GaganNeural",
        "text": "ನಮಸ್ಕಾರ ಸರ್, ಈ ಪಾರ್ಸೆಲ್ ಡೆಲಿವರಿ ಮಾಡಬೇಕು. ನಾಲ್ಕನೇ ಕ್ರಾಸ್ ಎಲ್ಲಿದೆ?",
        "translation_en": "Hello sir, I have to deliver this parcel. Where is 4th cross?",
        "speaker_role": "Delivery Partner",
    },
    {
        "sample_id": "kn_02_bus_stop",
        "category": "kannada",
        "lang_code": "kn",
        "voice": "kn-IN-SapnaNeural",
        "text": "ಮೆಜೆಸ್ಟಿಕ್ ಹೋಗೋ ಬಸ್ ಎಷ್ಟು ಹೊತ್ತಿಗೆ ಬರುತ್ತೆ ಅಣ್ಣಾ?",
        "translation_en": "What time will the Majestic bus arrive, brother?",
        "speaker_role": "Commuter",
    },
    {
        "sample_id": "kn_03_warehouse_sort",
        "category": "kannada",
        "lang_code": "kn",
        "voice": "kn-IN-GaganNeural",
        "text": "ಈ ಎಲ್ಲಾ ಬಾಕ್ಸ್‌ಗಳನ್ನು ನೀಟಾಗಿ ಎರಡನೇ ರ‍್ಯಾಕ್‌ನಲ್ಲಿ ಜೋಡಿಸಿಡಿ.",
        "translation_en": "Stack all these boxes neatly on the second rack.",
        "speaker_role": "Warehouse Supervisor",
    },
    {
        "sample_id": "kn_04_drinking_water",
        "category": "kannada",
        "lang_code": "kn",
        "voice": "kn-IN-SapnaNeural",
        "text": "ಇಲ್ಲಿ ಕುಡಿಯೋ ನೀರು ಎಲ್ಲಿ ಸಿಗುತ್ತೆ?",
        "translation_en": "Where can I get drinking water here?",
        "speaker_role": "Worker",
    },

    # =========================================================================
    # 5. Telugu (Hyderabad / Andhra Pradesh Construction & Survival)
    # =========================================================================
    {
        "sample_id": "te_01_safety_helmet",
        "category": "telugu",
        "lang_code": "te",
        "voice": "te-IN-MohanNeural",
        "text": "సైట్ లోపలికి వచ్చేటప్పుడు ప్రతి ఒక్కరూ హెల్మెట్ తప్పకుండా పెట్టుకోవాలి.",
        "translation_en": "Everyone must wear a helmet when entering the site.",
        "speaker_role": "Site In-charge",
    },
    {
        "sample_id": "te_02_lunch_canteen",
        "category": "telugu",
        "lang_code": "te",
        "voice": "te-IN-ShrutiNeural",
        "text": "మధ్యాహ్నం భోజనం కౌంటర్ ఎక్కడ ఉంది అన్నా?",
        "translation_en": "Where is the afternoon lunch counter located, brother?",
        "speaker_role": "Worker",
    },
    {
        "sample_id": "te_03_bus_stand",
        "category": "telugu",
        "lang_code": "te",
        "voice": "te-IN-MohanNeural",
        "text": "సికింద్రాబాద్ వెళ్లే బస్సు ఏ నంబరు?",
        "translation_en": "Which bus number goes to Secunderabad?",
        "speaker_role": "Commuter",
    },

    # =========================================================================
    # 6. Bengali (Eastern Corridor: Transit & Construction)
    # =========================================================================
    {
        "sample_id": "bn_01_train_departure",
        "category": "bengali",
        "lang_code": "bn",
        "voice": "bn-IN-BashkarNeural",
        "text": "দাদা, হাওড়া যাওয়ার ট্রেনটা কোন প্ল্যাটফর্মে আসবে?",
        "translation_en": "Brother, on which platform will the train to Howrah arrive?",
        "speaker_role": "Passenger",
    },
    {
        "sample_id": "bn_02_site_safety",
        "category": "bengali",
        "lang_code": "bn",
        "voice": "bn-IN-TanishaaNeural",
        "text": "সেফটি বেল্ট ছাড়া উঁচুতে কাজ করবেন না, বিপদ হতে পারে!",
        "translation_en": "Do not work at height without a safety belt, it can be dangerous!",
        "speaker_role": "Safety In-charge",
    },
    {
        "sample_id": "bn_03_wage_question",
        "category": "bengali",
        "lang_code": "bn",
        "voice": "bn-IN-BashkarNeural",
        "text": "আজকের পুরো দিনের মজুরি কখন পাওয়া যাবে?",
        "translation_en": "When will today's full daily wage be paid?",
        "speaker_role": "Worker",
    },
]


async def render_single_audio(
    item: Dict[str, Any],
    out_dir: Path,
    target_sr: int = 22050,
) -> Dict[str, Any]:
    """Renders a single multilingual speech sample using edge-tts and converts via ffmpeg."""
    import edge_tts

    cat_dir = out_dir / item["category"]
    cat_dir.mkdir(parents=True, exist_ok=True)

    wav_path = cat_dir / f"{item['sample_id']}.wav"
    temp_mp3 = cat_dir / f"{item['sample_id']}.temp.mp3"

    communicate = edge_tts.Communicate(item["text"], item["voice"], rate="+0%")
    await communicate.save(str(temp_mp3))

    cmd = [
        "ffmpeg", "-y", "-i", str(temp_mp3),
        "-ar", str(target_sr),
        "-ac", "1",
        "-sample_fmt", "s16",
        str(wav_path),
    ]
    subprocess.run(cmd, capture_output=True, check=True)

    if temp_mp3.exists():
        temp_mp3.unlink()

    meta = dict(item)
    meta["file_path"] = f"audio_samples/{item['category']}/{wav_path.name}"
    meta["sample_rate"] = target_sr
    meta["channels"] = 1
    meta["format"] = "PCM 16-bit Mono WAV"
    return meta


async def main_async() -> int:
    out_root = Path("tools/content_compiler/data/audio_samples")
    out_root.mkdir(parents=True, exist_ok=True)

    logger.info(f"Starting batch generation of {len(DATASET)} multilingual audio samples...")

    rendered_metadata: List[Dict[str, Any]] = []
    for idx, item in enumerate(DATASET, 1):
        logger.info(f"[{idx}/{len(DATASET)}] Rendering {item['category']} ({item['lang_code']}) - {item['sample_id']}...")
        meta = await render_single_audio(item, out_root)
        rendered_metadata.append(meta)

    manifest_path = out_root / "multilingual_audio_manifest.json"
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump({
            "dataset_title": "Seedhebol Multilingual Audio Samples & UI Voice Prompts",
            "total_samples": len(rendered_metadata),
            "supported_languages": ["Hindi", "Tamil", "Malayalam", "Kannada", "Telugu", "Bengali"],
            "target_sample_rate": 22050,
            "samples": rendered_metadata,
        }, f, indent=2, ensure_ascii=False)

    logger.info(f"Successfully generated all {len(rendered_metadata)} multilingual audio files.")
    logger.info(f"Manifest written to: {manifest_path}")
    return 0


def main() -> int:
    return asyncio.run(main_async())


if __name__ == "__main__":
    sys.exit(main())
