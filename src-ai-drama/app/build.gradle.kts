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
        versionCode = 84
        versionName = "1.9.14"  // v1.9.14: 渲染队列暴露失败原因（fail_reason）；pollResult error 字段兼容字符串/对象，修复原因丢失
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
        buildConfig = true   // 更新通道：读取 BuildConfig.VERSION_CODE 比对远程版本
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions {
        jvmTarget = "17"
        // 构建修复：Compose BOM 2024.10.01 实际解析出 material3:**1.3.1**
        // （下方依赖注释写的是 1.3.0，已漂移，建议一并订正）。
        // material3 1.3.1 中 Surface 等 API 带 @ExperimentalMaterial3Api，
        // 其 @RequiresOptIn level 为 ERROR —— 未显式 opt-in 时，**任何干净环境**
        // （CI / 新机器 / 清空 Gradle 缓存）都会在 MainActivity.kt:24 编译失败：
        //   e: This material API is experimental and is likely to change
        //      or to be removed in the future.
        // 本地能编过通常只是因为缓存或 IDE 代为处理，并非真的没问题。
        freeCompilerArgs += listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
    packaging { resources.excludes += "META-INF/*" }

    // 固定签名 keystore：保证所有发布包同签名 → 可覆盖安装。
    // 否则默认 debug 签名每次构建不同，用户已装的旧包会因「签名冲突」无法覆盖更新。
    //
    // 安全：store/key 密码与 alias 一律从 gitignored 的 local.properties 读取，
    // 缺失时回退到环境变量；绝不硬编码、绝不入库（旧 keystore 曾因入库 + 裸密码
    // 导致签名身份泄露，已被轮换废弃）。local.properties 需自行备份（密码管理器）。
    // 手动解析 gitignored 的 local.properties（纯 Kotlin 标准库，规避 DSL 中 java.util.* 不可见问题）
    val keyProps = mutableMapOf<String, String>().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) {
            f.readLines().forEach { raw ->
                val line = raw.trim()
                if (line.isNotEmpty() && !line.startsWith("#") && line.contains("=")) {
                    val eq = line.indexOf('=')
                    val k = line.substring(0, eq).trim()
                    val v = line.substring(eq + 1).trim()
                    if (k.isNotEmpty()) put(k, v)
                }
            }
        }
    }
    fun secret(name: String): String =
        (keyProps[name] ?: System.getenv(name))
            ?: error("缺少签名密钥配置：$name（请在 local.properties 或环境变量中设置，切勿提交到仓库）")
    val KEYSTORE_DIR = "keystore"
    val KEYSTORE_FILE = "drama-release.jks"
    val KEY_ALIAS = secret("AI_DRAMA_KEY_ALIAS")
    val STORE_PWD = secret("AI_DRAMA_STORE_PASSWORD")
    val KEY_PWD = secret("AI_DRAMA_KEY_PASSWORD")
    signingConfigs {
        create("releaseSigned") {
            storeFile = file("$KEYSTORE_DIR/$KEYSTORE_FILE")
            storePassword = STORE_PWD
            keyAlias = KEY_ALIAS
            keyPassword = KEY_PWD
        }
    }
    // debug 也用固定签名（与发布同 keystore），使 assembleDebug 产出可覆盖安装的包。
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("releaseSigned")
            isDebuggable = true
        }
        release {
            signingConfig = signingConfigs.getByName("releaseSigned")
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

repositories {
    // 默认仓库；ffmpeg-kit 现已改用 community 维护版（Maven Central，见下方依赖注释），
    // 不再依赖 jitpack.io（旧 5.1 在 jitpack 仅为纯 Java 壳，APK 无 .so）。
    google()
    mavenCentral()
}

dependencies {
    implementation(project(":core-engine"))
    // AgnesProvider构造签名暴露io.ktor.client.HttpClient类型（默认参数），
    // app编译期需要该类在classpath上（运行时由core-engine传递提供）
    val ktorVer = "2.3.12"
    implementation("io.ktor:ktor-client-core:$ktorVer")
    implementation("io.ktor:ktor-client-okhttp:$ktorVer")

    implementation("androidx.core:core-ktx:1.13.1")

    // v1.7.18：自定义模型/视频参数配置以 JSON 持久化（provider_configs.extra_params），
    // 解析与组装需要 kotlinx-serialization-json（与 core-engine 同版本，避免传递依赖版本漂移）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

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
    // v1.8.1：图标只用 core 的 49 个 + res/drawable 下自定义 vector（ic_camera/ic_videocam/
    // ic_video_library/ic_link/ic_shield/ic_movie/ic_image/ic_sparkle/ic_prop）。
    // 不用 material-icons-extended：它给 debug APK 加 32MB（3000+ 未用图标全进 dex）。
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

    // T014：视频拼接 —— ffmpeg-kit-min-gpl 8.1.7（含 x264，LGPL+GPL）。
    // 优化说明：从 ffmpeg-kit-full 切换到 min-gpl，native libs 从约 50MB 降至约 15MB（APK 省 ~30MB）。
    // min-gpl 包含 x264（H.264 编码），满足短剧片段 concat + 重编码 + mp4 封装需求。
    // 包名 dev.ffmpegkit-maintained（Maven Central 维护版），原 arthenica 包已停止维护。
    // Android SDK 35 + 16KB page 兼容；MovieAssembler.kt 调用 ffmpeg 命令行构建 libx264。
    // Package 下 com.arthenica.ffmpegkit.Ffmpeg / FFprobeKit 等 drop-in 替换，代码无需修改，仅依赖变更。
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-min-gpl:8.1.7")
    // 关键补丁：社区版 ffmpeg-kit-full:8.1.7 的 AAR 打包缺陷——classes.jar 缺
    // com.arthenica.smartexception.java.Exceptions，而 FFmpegKitConfig.<clinit> 引用它，
    // 导致 FFmpegKit.execute() 首次调用即 NoClassDefFoundError（MuMu 真机验证发现）。
    // 该胶水类由官方 arthenica 单独发布的 smart-exception-java artifact 提供，必须显式引入。
    // （注意：坐标不是 com.arthenica.smartexception:smartexception —— 该坐标在 Maven Central 不存在；
    //   正确坐标为 com.arthenica:smart-exception-java:0.2.1，其 POM 自动传递依赖 smart-exception-common:0.2.1。
    //   已实测验证 jar 内含 com/arthenica/smartexception/java/Exceptions.class。）
    implementation("com.arthenica:smart-exception-java:0.2.1")

    // ViewModel JVM单测（不依赖Robolectric，纯逻辑测试）
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // 第六轮：AgnesProvider 参数组装单测用 Ktor MockEngine
    testImplementation("io.ktor:ktor-client-mock:2.3.12")

    // 设备端插桩测试（MuMu 模拟器真机验证 ffmpeg 原生库加载/执行，关闭 P1-6 运行时风险）
    // 仅用 androidx.test.ext:junit（含 AndroidJUnitRunner）；不引 espresso-core，
    // 否则 espresso 会往测试 APK 注入 <uses-library android.test.mock required=true>，
    // MuMu 模拟器不暴露该共享库，导致 INSTALL_FAILED_MISSING_SHARED_LIBRARY。
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// v1.7.13：导出 Room schema 历史到 app/schemas/。
// 此前没开导出，没人能核对「手写迁移 SQL」与「Room 期望 schema」是否一致
// （v4→v5 的 finished_films 就对不上，于是被迫整库破坏性重建、用户数据全丢）。
// 开启后每次升版本都会落一份 <version>.json，后续迁移有权威基准可比对。
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
