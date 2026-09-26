import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.kairo.gallery"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.kairo.gallery"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // Snapdragon phones are 64-bit ARM; the NPU runtime ships only for arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // NPU: the Hexagon DSP loads libQnnHtpV81Skel.so from the extracted native lib dir, so keep libs on disk.
            useLegacyPackaging = true
            // Hexagon (DSP) binaries can't be stripped by the Android NDK tools.
            keepDebugSymbols += "**/libQnnHtpV*Skel.so"
            // Kairo targets the Snapdragon 8 Elite Gen 5 (Hexagon V81): drop the other NPU generations (~40 MB).
            listOf("68", "69", "73", "75", "79").forEach { v ->
                excludes += "**/libQnnHtpV${v}Skel.so"
                excludes += "**/libQnnHtpV${v}Stub.so"
                excludes += "**/libQnnHtpV${v}CalculatorStub.so"
            }
            // Only the HTP (NPU) backend is used; the legacy DSP and QNN-GPU backends are not.
            excludes += "**/libQnnDsp*.so"
            excludes += "**/libQnnGpu*.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // On-device LLM (Gemma 4 E2B .litertlm)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")

    // On-device CLIP for semantic image search (.tflite, GPU with CPU fallback)
    implementation("com.google.ai.edge.litert:litert:2.2.0")
    // Qualcomm NPU (Hexagon HTP) runtime for CLIP; same QAIRT version (2.47) that LiteRT 2.2.0 is built against.
    // LiteRT's Qualcomm dispatch/compiler plugin (V81) lives in src/main/jniLibs/arm64-v8a.
    implementation("com.qualcomm.qti:qnn-runtime:2.47.0")

    // Offline OCR (bundled model, no download needed)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Background indexing
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // EXIF (GPS)
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // UI
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
}
