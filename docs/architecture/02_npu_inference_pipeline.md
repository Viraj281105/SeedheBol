# 02 — NPU Inference Pipeline & Execution Provider Optimization

## 1. Qualcomm Hexagon NPU & QNN Architecture
The Qualcomm Snapdragon 8 Elite Gen 5 includes an advanced **Hexagon NPU** with dedicated tensor and scalar vector extensions.

### 1.1 ONNX Runtime Configuration
The C++ JNI bridge configures ONNX Runtime with Qualcomm QNN Execution Provider options:

```cpp
Ort::SessionOptions session_options;
session_options.SetIntraOpNumThreads(2);
session_options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

// Append QNN Execution Provider (HTP Backend)
const char* qnn_lib_path = "libQnnHtp.so";
std::unordered_map<std::string, std::string> qnn_options = {
    {"backend_path", qnn_lib_path},
    {"htp_performance_mode", "burst"},
    {"htp_graph_finalization_optimization_mode", "3"},
    {"enable_htp_fp16_precision", "1"}
};
OrtSessionOptionsAppendExecutionProvider_QNN(session_options, qnn_options);
```

## 2. IndicConformer Execution Flow
- **Input**: 80-channel log-mel filterbank energies extracted from 16kHz audio.
- **Encoder**: 17 Conformer blocks with 512 model dimensions.
- **CTC Decoder Head**: Emits continuous posterior matrices across frames for forced alignment.
- **RNNT Decoder Head**: Greedy decode generates real-time partial text hypotheses.

## 3. FastPitch + HiFi-GAN Vocoding
- **FastPitch Stage**: Converts phoneme IDs + pitch/duration targets into intermediate mel-spectrograms in a single feed-forward pass.
- **HiFi-GAN Stage**: Converts mel-spectrogram to raw 22kHz PCM audio. Total execution time on Hexagon NPU is `< 180ms`.
