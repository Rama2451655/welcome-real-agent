plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rama.riya"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.rama.riya"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "4.4"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    packaging {
        jniLibs { pickFirsts += setOf("**/libc++_shared.so") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
