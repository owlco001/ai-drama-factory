// app模块：Android壳（七阶段Compose UI + Room持久化 + Foreground Service接线）
plugins {
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")   // Compose编译器插件（Kotlin 2.0+必需）
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
        versionCode = 4
        versionName = "1.1.0"  // v1.1：时代红线按剧本自动推断
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "META-INF/*" }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core-engine"))
    // AgnesProvider构造签名暴露io.ktor.client.HttpClient类型（默认参数），
    // app编译期需要该类在classpath上（运行时由core-engine传递提供）
    val ktorVer = "2.3.12"
    implementation("io.ktor:ktor-client-core:$ktorVer")
    implementation("io.ktor:ktor-client-okhttp:$ktorVer")

    implementation("androidx.core:core-ktx:1.13.1")

    // 第八轮：资产缩略图预览（本地 file:// / content:// 与生成图 http/https 异步加载）
    implementation("io.coil-kt:coil-compose:2.6.0")

    // ---- Compose + Material3（BOM对齐本地缓存版本：compose 1.7.3 / m3 1.3.0）----
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")   // @Preview渲染

    // Activity/Lifecycle/ViewModel-Compose（接core-engine的StateFlow）
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    // ViewModel协程作用域（Android实际实现）
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Room 2.6（架构§5六张表）+ Room版CheckpointStore适配器
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // EncryptedSharedPreferences（架构§6 Key安全存储）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ViewModel JVM单测（不依赖Robolectric，纯逻辑测试）
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // 第六轮：AgnesProvider 参数组装单测用 Ktor MockEngine
    testImplementation("io.ktor:ktor-client-mock:2.3.12")
}
