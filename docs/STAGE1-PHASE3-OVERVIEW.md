# 阶段1 ③ 落地总览 —— 修静默丢弃测试 + 落地 CI

## 背景
阶段1 ①② 已落地（① ffmpeg 依赖替换、② 6 处 P0 假完成修复）。
但此前 `:core-engine` 实测 **140 个 `@Test` 声明 / 仅 139 执行**，差 1 个——
且这 1 个是被 JUnit **静默丢弃**的：构建照绿、无报错、不计 skipped。

## ③-A 修复静默丢弃的测试
- 文件：`src-ai-drama/core-engine/src/test/kotlin/com/dramafactory/core/Round2FixRegressionTest.kt`
- 根因：`P0_1 提交前意图已落库_submit挂起期间杀进程_恢复后该镜不重提`
  使用表达式体 `fun ...() = runBlocking { ... gate.complete(Unit) }`，
  末句 `gate.complete(Unit)` 返回 `Boolean` → 方法被 Kotlin 编译为 `boolean` →
  JUnit Jupiter 静默丢弃非 void 的 `@Test`。
- 修复：在 `runBlocking` 块末行补 `Unit`，使方法返回 `Unit`（JVM `void`）。
- **验证**：重跑 `:core-engine:test` → **140 声明 == 140 执行**（修复前 139）。
  XML 报告已含该 testcase。这是唯一守护"崩溃恢复后不重复扣费"的用例。

## ③-B 落地 CI（本项目专属止血带）
- 文件：`.github/workflows/ci.yml`（仓库根 `ai-drama-factory/.github/workflows/`）
- 两个 job：`core-engine-tests`（JDK17，纯 JVM，无需 Android SDK）、`android-app`（含 Android SDK）。
- 关键断言（防 silent-drop 复发）：
  `静态 @Test 声明数 == XML <testcase> 执行数` 且 `执行数 > 0`，不符即 `exit 1`。
  另含 `:app:assembleDebug` 构建成功门。
- 通用教训已写入文档：`fun xxx() = runBlocking { ... }` 末句有返回值会静默失效。

## 验证结果
- `:core-engine:test` BUILD SUCCESSFUL，140/140。
- 闭环：声明 == 执行，静默丢失已消除，且被 CI 永久钉死。

## 未变诚实边界
本机无安卓设备/模拟器，APK 未安装真机运行。6 处 P0 的运行时正确性、
ffmpeg 真机 mpeg4 编码/色彩分级产出，均为「代码 + 单测」层面验证，非真机端到端验证。
需在真机/模拟器跑一次 `:app:assembleDebug` 安装验证出片链路。

## 待办（可选）
阶段1 ①②③ 全部落地，变更尚未 commit。可一次性提交：
9 个 modified 文件 + `P0FixRegressionTest.kt` + `P0-FIX.md` + `BUILD-STATUS.md`/
`APP-BUILD-STATUS.md`/`HANDOVER.md` 更新 + 新增 `.github/workflows/ci.yml` +
`Round2FixRegressionTest.kt` 修复。是否提交待用户确认。
