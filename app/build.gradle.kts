plugins {
    id("com.android.application")
}

android {
    namespace = "com.youjixingtu.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.youjixingtu.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 6
        versionName = "1.5"
        vectorDrawables.useSupportLibrary = true
        // 真机只需 ARM 架构；排除 x86/x86_64 可省约 22MB（OCR 引擎 so）
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // 本地中文 OCR（离线、免费、不联网），用于识别加油小票文字
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // ML Kit 需要 image 标签（Task API 图片输入）
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
