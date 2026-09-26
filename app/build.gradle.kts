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
    // Real org.json for JVM tests (android.jar only has stubs)
    testImplementation("org.json:json:20240303")
}
