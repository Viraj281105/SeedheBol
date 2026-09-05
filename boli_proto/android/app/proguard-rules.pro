# ProGuard / R8 Rules for Boli & MediaPipe GenAI

# MediaPipe GenAI & Protobuf warnings suppression
-dontwarn com.google.protobuf.**
-dontwarn com.google.mediapipe.**
-keep class com.google.mediapipe.tasks.genai.** { *; }
-keep class com.google.protobuf.** { *; }

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ML Kit OCR
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Flutter & Plugins
-keep class io.flutter.** { *; }
-keep class com.boli.boli_proto.** { *; }
-dontwarn com.google.android.play.core.**
