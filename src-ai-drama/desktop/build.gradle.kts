plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
}

group = "com.dramafactory"
version = "1.4.9-desktop"

val ktorVer = "2.3.12"

dependencies {
    implementation(project(":core-engine"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    // core-engine 依赖 ktor（AgnesProvider/DeepSeekProvider 用 HttpClient）
    implementation("io.ktor:ktor-client-core:$ktorVer")
    implementation("io.ktor:ktor-client-okhttp:$ktorVer")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVer")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVer")
    // 测试
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

compose.desktop {
    application {
        mainClass = "com.dramafactory.desktop.MainKt"
        nativeDistributions {
            modules("java.sql")
            packageName = "AIDramaFactory"
            packageVersion = "1.4.9"
        }
    }
}
