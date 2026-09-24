// 阶段 M4 — 上限验证 bench 模块（独立 APK，不引 sherpa）
//
// 目的：量化「如果 ORT 支持 GPU/NPU，Demucs 能快多少」。
// 依赖 `onnxruntime-android-qnn`（含 XNNPACK + QNN + NNAPI + CPU 的 Java API）。
//
// ⚠️ 与主工程**完全隔离**：这里单独加载一份 ORT（VERS_1.29.0），
//    不与 sherpa 的 ORT（VERS_1.28.2）共存 —— 从而绕开坑 49 的符号冲突。
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "pub.miku.neuralstem.bench"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "pub.miku.neuralstem.bench"
        minSdk = 26            // ORT qnn 包要求较高 minSdk
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.onnxruntime.android.qnn)
}
