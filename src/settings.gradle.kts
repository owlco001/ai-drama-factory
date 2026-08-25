// 模块注册：core-engine（纯Kotlin，可单测）+ app（安卓壳）
// 架构§8目录结构；无Android SDK环境下仅core-engine参与本地构建测试
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "agent-team"
include(":core-engine")
include(":app")
