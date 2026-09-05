# 01 — System Architecture Deep Dive

## 1. High-Level Architectural Principles

Seedhebol's architecture is rooted in three non-negotiable invariants:
1. **Zero-Cloud Dependency**: Every ML inference step executes entirely within the mobile SoC boundaries.
2. **Sub-800ms Latency Budget**: Natural conversation cannot tolerate latency. Speech-to-speech roundtrips are strictly bounded under 800ms.
3. **Sub-100MB Memory Footprint**: To ensure smooth operation on 3GB RAM devices without garbage collection stutter or OS memory kills.

```
+-------------------------------------------------------------------------+
|                        FLUTTER PRESENTATION SHELL                       |
|  - Zero-Literacy Audio Radar UI        - High-Contrast Tactile Touch    |
|  - Real-Time Phonemic Error Overlay    - Commute Audio Player           |
+------------------------------------+------------------------------------+
                                     | JNI Platform Channels
+------------------------------------v------------------------------------+
|                         NATIVE ANDROID CORE (KOTLIN)                    |
|  - AudioRecord 16kHz PCM Stream        - AudioTrack Streaming Output    |
|  - CameraX Frame Analyzer              - PowerManager Thermal Monitor   |
+------------------------------------+------------------------------------+
                                     | C++ Direct Buffers
+------------------------------------v------------------------------------+
|                   ONNX RUNTIME + QUALCOMM QNN EXEP (NPU)                |
|  +---------------------------------+  +------------------------------+  |
|  | IndicConformer CTC/RNNT (~60MB) |  | FastPitch + HiFi-GAN (~40MB) |  |
|  +---------------------------------+  +------------------------------+  |
+------------------------------------+------------------------------------+
                                     |
+------------------------------------v------------------------------------+
|                         LOCAL PERSISTENCE & CACHE                       |
|  - Isar / SQLite User Progress DB      - Pre-rendered Parler Audio Bank |
|  - Cryptographic Skill Keystore        - Ephemeral 30s RAM Ring Buffer  |
+-------------------------------------------------------------------------+
```

## 2. Component Decoupling
Each subsystem is isolated via clean interfaces:
- **Audio Capture**: Emits immutable 16kHz PCM chunks through Kotlin `SharedFlow`.
- **Inference Pipeline**: Receives raw byte streams, interacts with native QNN C++ execution sessions, and outputs structured token/posterior objects.
- **UI Shell**: Subscribes to Riverpod state providers that consume inference events without any direct dependency on native C++ libraries.
