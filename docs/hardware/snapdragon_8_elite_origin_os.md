# Hardware Profiling — Qualcomm Snapdragon 8 Elite & OriginOS 6

## 1. Device Hardware Specifications (iQOO 15)
- **SoC**: Qualcomm Snapdragon 8 Elite Gen 5 (3nm process)
- **NPU**: Qualcomm Hexagon NPU with Vector & Tensor Accelerators
- **Memory**: 12GB / 16GB LPDDR5X
- **Battery & Thermal**: 7000mAh dual-cell with 14,000mm² Vapor Chamber cooling
- **OS**: OriginOS 6 based on Android 16 (API Level 36)

## 2. Thermal Headroom Monitoring & Degradation Matrix
The native layer polls `PowerManager.getThermalHeadroom(30)` at 1Hz:

```
[Thermal Headroom < 0.70] ──► Full Pipeline (ASR + FastPitch TTS + Ambient Mining)
[Thermal Headroom 0.70-0.85] ──► Switch TTS to Pre-rendered Audio; Narrow ASR Beam
[Thermal Headroom 0.85-0.95] ──► Pause Ambient Miner; Run CTC-Only Decode
[Thermal Headroom > 0.95] ──► Fallback to Cached Phrasebook Mode (Zero NPU Load)
```
