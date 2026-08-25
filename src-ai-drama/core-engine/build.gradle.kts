// core-engine：纯Kotlin管线引擎模块（JVM可单测），不含任何Android依赖
// 网络层Ktor Client(OkHttp engine)——架构§1.2选型；零Firebase
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Ktor Client 2.x + OkHttp engine（架构§1.2：协程原生/强取消传播/底层OkHttp传输）
    val ktor = "2.3.12"
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-okhttp:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
