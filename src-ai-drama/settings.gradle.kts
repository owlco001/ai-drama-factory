// 「AI短剧工厂」模块注册：core-engine（纯Kotlin引擎，JVM可测）+ app（Android壳）
// 参照「Agent团队」工程结构；无Android SDK环境下仅core-engine参与本地构建测试
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "ai-drama-factory"
include(":core-engine")
include(":app")
