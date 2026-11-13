plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lucas.asrcapsule"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lucas.asrcapsule"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)
    
    // sherpa-onnx 离线 ASR 引擎
    implementation(libs.sherpa.onnx) {
        exclude(module = "sherpa-onnx-jvm")
        exclude(module = "sherpa-onnx-native-lib-win-x64")
        exclude(module = "sherpa-onnx-native-lib-win-arm64")
        exclude(module = "sherpa-onnx-native-lib-osx-x64")
        exclude(module = "sherpa-onnx-native-lib-osx-aarch64")
        exclude(module = "sherpa-onnx-native-lib-linux-x64")
        exclude(module = "sherpa-onnx-native-lib-linux-aarch64")
    }
}
