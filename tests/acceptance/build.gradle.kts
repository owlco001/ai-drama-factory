// AI短剧工厂 MVP验收E2E：引用src-ai-drama产物
plugins { kotlin("jvm") version "2.0.21" }
repositories { mavenCentral() }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation(files("/root/project_workspace/src-ai-drama/core-engine/build/libs/core-engine.jar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}
tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/acceptance"))
}
