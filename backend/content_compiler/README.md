# `tools/content_compiler` — Situational Curricula & Dialogue Tree Generator

> **Curriculum Authoring & Validation**: Generates verified, domain-specific situational dialogue graphs across migration corridors.

---

## 🎯 Supported Migration Corridors & Occupations

- **Corridors**:
  - `bhojpuri_tamil` (Bihar $\to$ Tamil Nadu)
  - `odia_malayalam` (Odisha $\to$ Kerala)
  - `hindi_kannada` (UP/Bihar $\to$ Karnataka)
- **Occupations**:
  - `construction` (Safety warnings, tool requests, wage negotiation, supervisor disputes)
  - `healthcare` (Patient triage, symptom queries, dosage explanations, consent)
  - `logistics_delivery` (Customer directions, OTP confirmation, delivery verification)

---

## ⚡ Usage

```bash
python generate_situations.py \
  --corridor bhojpuri_tamil \
  --domain construction \
  --output_file ../../apps/mobile/android/app/src/main/assets/curricula/construction_tamil.json
```
