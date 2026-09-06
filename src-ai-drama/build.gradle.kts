// 「AI短剧工厂」根构建脚本 —— 架构§1.1技术栈基线
plugins {
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.android.application") version "8.5.2" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
    id("org.jetbrains.compose") version "1.6.10" apply false
}

// ============================================================================
// TD-2 守卫：拦截「带 @Test 注解却被编译为非 void 返回类型」的测试方法。
// 这类方法会被 JUnit Jupiter 静默丢弃——构建全绿、测试"通过"，但用例从未运行
// （历史坑：防重复扣费用例因此长期未执行）。守卫下沉到 Gradle `check`，与 CI 平台无关。
// 反向验证：把 MultiVideoProviderTest 的 reconcile 用例末行 Unit 去掉 → check 失败并点名方法。
// ============================================================================
subprojects {
    afterEvaluate {
        val compileTaskName = tasks.findByName("compileTestKotlin")?.name
            ?: tasks.findByName("compileDebugUnitTestKotlin")?.name
        val testTaskName = tasks.findByName("test")?.name
            ?: tasks.findByName("testDebugUnitTest")?.name
        if (compileTaskName != null && testTaskName != null) {
            val guard = tasks.register("checkSilentTests") {
                group = "verification"
                description = "扫描测试 class，拦截被 JUnit 静默丢弃的非 void @Test 方法（TD-2）"
                dependsOn(compileTaskName)
                doLast {
                    val py = rootProject.file("scripts/check-silent-tests.py").absolutePath
                    exec {
                        commandLine("python3", py, layout.buildDirectory.get().asFile.absolutePath)
                    }
                }
            }
            tasks.named(testTaskName).configure { finalizedBy(guard) }
            if (tasks.findByName("check") != null) {
                tasks.named("check").configure { dependsOn(guard) }
            }
        }
    }
}
