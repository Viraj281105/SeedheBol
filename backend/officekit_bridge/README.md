# `tools/officekit_bridge` — Vivo Office Kit Integration & Telemetry Validator

> **Hardware Telemetry & Handoff CLI**: Automates file transfer and Remote PC execution verification for the iQOO Hackathon scoring rubric.

---

## 🚀 Key Workflows

### 1. Remote PC Model Quantization Dispatch
Dispatches heavy quantization and model conversion tasks from the iQOO 15 phone directly to the host laptop via Vivo Office Kit Remote PC.

### 2. Free Transfer Assessment Syncer
Listens on the host laptop filesystem for incoming oral reading assessment SQLite/CSV dumps transmitted via Office Kit Free Transfer.

### 3. Telemetry Verification Script
Verifies that device activity logs confirm active usage of the microphone, camera, motion sensors, NFC, and Hexagon NPU for the HackTracker scoring system:
```bash
python verify_telemetry.py --device-serial <ADB_SERIAL>
```
