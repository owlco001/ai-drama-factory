// app模块：Android壳（UI层 + Room持久化 + JNI接入点）
plugins {
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.application")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.agentteam.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.agentteam.app"
        minSdk = 29            // PRD: Android 10+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }   // PRD兼容性要求
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    packaging { resources.excludes += "META-INF/*" }
}

dependencies {
    implementation(project(":core-engine"))

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Room 2.6（架构§1.1）：SQLite三层记忆/消息日志/任务DAG
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // JNI native库占位：真实libllama-android.so后续以cmake外部构建接入
}
