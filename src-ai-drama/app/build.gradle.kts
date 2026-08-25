// app模块：Android壳（UI层占位 + Room持久化 + Foreground Service桩）
plugins {
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.android.application")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.dramafactory.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.dramafactory.app"
        minSdk = 29            // PRD: Android 10+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "META-INF/*" }
}

dependencies {
    implementation(project(":core-engine"))

    implementation("androidx.core:core-ktx:1.13.1")

    // Room 2.6（架构§5六张表）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // EncryptedSharedPreferences（架构§6 Key安全存储）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
