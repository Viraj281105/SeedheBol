plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.boli.boli_proto"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.boli.boli_proto"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // Flutter's --target-platform only filters its own engine libs. The
        // onnxruntime AAR ships arm64-v8a, armeabi-v7a and x86_64 regardless,
        // which is ~33 MB of libonnxruntime.so this device can never load.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    androidResources {
        // Keep the model uncompressed so unpacking to filesDir is a straight copy.
        noCompress += listOf("onnx", "wav", "bin", "task", "data", "json")
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // CPU execution provider only. The iQOO 15 is Snapdragon 8 Elite Gen 5;
    // QNN/HTP delegation is possible for future work but not claimed here.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // ML Kit on-device OCR — Devanagari (Hindi + Marathi), Tamil, Telugu,
    // Kannada, Latin. No network calls, no API key, Apache 2.0.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // MediaPipe LLM Inference API — Gemma 3n E2B INT4 on-device.
    // Model loaded from external files dir, not bundled in APK.
    implementation("com.google.mediapipe:tasks-genai:0.10.22")

    // CameraX — frame capture for the OCR camera screen.
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Coroutines (already transitive via Flutter, declared explicitly for clarity)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

flutter {
    source = "../.."
}
