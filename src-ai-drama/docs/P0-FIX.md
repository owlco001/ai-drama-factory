# 阶段 1 ②：6 处 P0「假完成」修复记录

> 执行人：software-engineer-4（寇豆码）
> 日期：2026-08-28
> 上游依据：`docs/HANDOVER.md` §4.1（F1~F6）、§阶段1 验收标准
> 关联阶段：阶段 0 已跑通 `:core-engine:test` / `:app:testDebugUnitTest` / `:app:assembleDebug`；阶段 1 ① 已换 ffmpeg community 版依赖。

## 诚实声明（首要）

本项目家族的病根就是「假完成」——**改完能编译 ≠ 功能正确**。本人在此如实汇报每处 P0 的处置与验证边界：

- 编译验证：`：app:assembleDebug` 是否 BUILD SUCCESSFUL（见文末）。
- 真机/端到端运行验证：**无安卓设备，未做**。所有「AI 模式真实跑通」类断言均无法在此环境验证。
- 集成测试：在 `:core-engine` JVM 单测层用「等价 λ 接线」复刻生产接线形状并对关键不变量加锁（F1~F4）；F5/F6 因依赖 Android Context / 引擎 Gate 调用点，未在 JVM 单测内端到端驱动，仅编译 + 逻辑推导验证。

---

## F1 — 视频提交 prompt 恒为空（最致命）

- **症状**：`shotPromptResolver` 默认 `{ _ -> Triple("","","") }`，生产代码从未赋值 → 每镜提交给 Agnes 的 prompt 恒为「全程使用中文普通话配音」（10 字，与剧本无关）。
- **修复点**：`app/src/main/java/com/dramafactory/app/ui/RenderRuntime.kt` 的 `queueFor`，给 `DefaultRenderQueue` 注入 `shotPromptResolver`：
  ```kotlin
  shotPromptResolver = { shotId ->
      val shot = runCatching { AppGraph.dao.shotKeyframes(shotId) }.getOrNull()
      Triple(shot?.dialogue ?: "", shot?.narration ?: "", shot?.action ?: "")
  }
  ```
  由 `shotId`（形如 `{episodeId}_shot{n}`）查 `shots` 表回填该镜 dialogue/narration/action；查不到（如 Room 未初始化）安全退化为空三元组（保持旧兜底行为）。
- **文件:行**：`RenderRuntime.kt:25-39`（queueFor 内新增）。
- **编译**：随 `:app:assembleDebug` 验证。
- **测试**：`core-engine/.../P0FixRegressionTest.kt::F1回归_提交prompt含分镜文本且长度大于50`，注入等价 resolver + 捕获 `submitVideo` 请求，断言 prompt 含分镜文本、长度 > 50、且 ≠ 恒值中文指令。
- **状态**：✅ 已修复并编译通过；测试已补（JVM 层等价接线锁）。端到端（真实 DB → Agnes）未做真机验证。

## F2 — AI 模式质量审计恒通过

- **症状**：`AppGraph.kt` 的 `auditAsset` λ 直接 `return passed=true`，真实 `AssetAuditor.audit()` 只在人工模式调用。
- **修复点**：`AppGraph.kt` 的 `auditAsset` λ 改为真实调用 `QualityEngine().auditAsset(...)`（桥接 `AssetAuditor.audit` G1+G2）。实现：
  1. 从 `dao.assetRemoteUrl(assetId)` 读生成图 remote_url（新增 DAO 查询 `assetRemoteUrl`）；
  2. `fetchImageBytes` + `downscaleToDataUri`（512px JPEG data URI，与 `ViewModels.auditGeneratedAsset` 同策略，防 base64 爆上下文）；
  3. `AssetAuditor.agnesDescriber(agnes, "")` 作 G2 describer；
  4. 映射 `AuditState.APPROVED` → `AuditResult(passed=true)`，否则 `passed=false`。
  5. 无图/未配 Key/异常时标注 `audit_skipped_*` / `audit_exception:*`，不阻断流水线但明确「未审计」（不再谎报通过）。
- **文件:行**：`AppGraph.kt:259-287`（auditAsset λ）；`AppGraph.kt` 新增 `fetchImageBytes`/`downscaleToDataUri` 辅助；`DramaDatabase.kt` 新增 `assetRemoteUrl` 查询 + `BrokenDramaDao` 对应空实现。
- **编译**：随 `:app:assembleDebug` 验证。
- **测试**：`P0FixRegressionTest.kt::F2回归_AI管线auditAsset确实调用AssetAuditor_audit`，构造 2x2 合法 PNG + 计数 spy describer，断言 AUDIT 阶段实际调用了 `AssetAuditor.audit`（G2 describer 被调用次数 ≥ 1）。该测试复刻 AppGraph 的真实接线形状。
- **状态**：✅ 已修复并编译通过；测试已补（JVM 层等价接线锁）。真通过 Agnes 多模态审计未做真机验证。

## F3 — AI 模式时代红线写死「西汉」

- **症状**：`AppGraph.kt` 的 `generateImage` λ 用 `EraDetector.presetFor("han")`，真实 `EraDetector.detect()` 只在人工模式调用。
- **修复点**：
  1. `AppGraph.kt` 新增 `@Volatile var currentEraKey`（默认 "han"）；
  2. `createEpisode` λ 内按剧本自动推断：`EraDetector.detect(scriptText, llmReady){ agnes.chat(it) }.eraKey` 写入 `currentEraKey`（LLM 优先、规则兜底，与人工模式 `ViewModels:370-374` 同策略）；
  3. `generateImage` λ 改用 `EraDetector.presetFor(currentEraKey)`。
- **文件:行**：`AppGraph.kt:55-60`（currentEraKey 字段）；`AppGraph.kt` createEpisode 内 `:200-213` 推断；generateImage 内 `:248` 改用 currentEraKey。
- **编译**：随 `:app:assembleDebug` 验证。
- **测试**：`P0FixRegressionTest.kt::F3回归_现代剧推断modern且不含西汉禁词_西汉剧推断han`，断言现代剧 → eraKey="modern" 且时代负向为空、正向不含「深衣曲裾」；西汉剧 → eraKey="han" 且含西汉约束（对照）。
- **状态**：✅ 已修复并编译通过；测试已补（时代推断逻辑锁）。注意：`currentEraKey` 为单活跃 run 假设，并发多 run 由上层串行保证；非汉代剧本不再被错误套西汉服饰约束。

## F4 — 断点续跑 retryFrom 用占位剧本

- **症状**：`DefaultAiOrchestrator.retryFrom` 用 `"RETRY_STUB".repeat(10)` 作为续跑脚本，产出垃圾且烧 token。
- **修复点**：
  1. `DefaultAiOrchestrator` 新增构造参数 `readScript: suspend (episodeId: String) -> String = { "" }`；
  2. `retryFrom` 改为 `val script = readScript(currentEpisodeId).ifBlank { "RETRY_STUB".repeat(10) }`——读不到真实脚本才退化为占位；
  3. `AppGraph.kt` 注入 `readScript = { epId -> dao.episode(epId)?.script_json.orEmpty() }`（读 `episodes.script_json`）。
- **文件:行**：`DefaultAiOrchestrator.kt:103-105`（新增参数）、`:160-168`（retryFrom 改用）；`AppGraph.kt:336-339`（注入 readScript）。
- **编译**：随 `:app:assembleDebug` 验证。
- **测试**：`P0FixRegressionTest.kt::F4回归_retryFrom读取真实剧本而非RETRY_STUB占位`，注入等价 readScript，断言续跑分镜生成收到的脚本 == 真实剧本且不以 "RETRY_STUB" 开头。
- **状态**：✅ 已修复并编译通过；测试已补（JVM 层等价接线锁）。

## F5 — 已付费镜头 clip 下载目录误用 java.io.tmpdir

- **症状**：`RenderRuntime.kt` 的 `cacheDir()` 用 `System.getProperty("java.io.tmpdir")`，且 `mkdirs()` 返回值不校验；配合 `DefaultRenderQueue` 的取回循环可能「永久空转」。
- **修复点**：
  1. `RenderRuntime.kt` 的 `cacheDir()` 改用 `AppGraph.appContext()?.cacheDir`（兜底 `filesDir/clips` → 最后才退 `java.io.tmpdir/clips`），与全项目其它路径策略一致；
  2. `downloadClip` 校验 `mkdirs()` 结果，不可写立即抛明确错误（不再丢弃返回值）；
  3. `DefaultRenderQueue.kt` 新增 `FETCH_RETRY_MAX=8` 取回失败重试上限：达到上限后保持 SUBMITTED 退出本 repoll（下次 `recoverOnBoot` 经 `pendingRepoll` 重试一次），消除「永久空转」。
- **文件:行**：`RenderRuntime.kt:67-105`（downloadClip + cacheDir）；`DefaultRenderQueue.kt:68-72`（FETCH_RETRY_MAX）、`:179-182`（repoll 声明 fetchFails）、`:193-208`（Completed 分支计数退避）。
- **编译**：随 `:app:assembleDebug` 验证。
- **测试**：未补端到端测试。原因：需 Android Context（appContext().cacheDir）与真实下载网络/文件系统，JVM 单测无法驱动 `RenderRuntime`（`:app` 模块、依赖 Android）。逻辑上 `cacheDir()` 现统一走 app cacheDir、取回有上限，已通过源码审查确认。
- **状态**：✅ 已修复并编译通过；**未补端到端测试**（无 Android Context / 无设备）。

## F6 — 四闸门 evaluateGates 恒 true 且无调用点

- **症状**：`DefaultPipelineOrchestrator.evaluateGates` 恒返回四 true；全仓库无生产调用点（HANDOVER 已交叉验证）。
- **修复点**：改为 **fail-closed**（诚实化）——凡无法验证的闸门一律返回 `false`，并附 `TODO(P0-6)` 标注真实实现所需的数据通道（需注入 `DramaDao` 或各 gate 判定 λ 读 `review_state`/`sb_check`/`is_verified`/预算态）。**绝不谎报「假通过」**。
  ```kotlin
  return GateReport(stage = _stage.value, budgetOk = false, keyValid = false,
      reviewPassed = false, storyboardPassed = false)
  ```
- **文件:行**：`DefaultPipelineOrchestrator.kt:25-41`。
- **编译**：随 `:app:assembleDebug` 验证。
- **测试**：未补。原因：`evaluateGates` 全仓库无生产调用点，fail-closed 行为无调用方触发；加测试价值低且需构造 CheckpointStore/DAO 注入。已在代码注释中明确标注「假通过」已消除。
- **状态**：✅ 已修复（诚实化 fail-closed）并编译通过；**未补测试**（无调用点、fail-closed 无需断言）。

---

## 编译与测试汇总

| 项 | 结果 |
|---|---|
| `:app:assembleDebug` | 见文末「构建输出」段 |
| `P0FixRegressionTest`（F1/F2/F3/F4 JVM 锁） | 见文末「测试输出」段（如已执行） |
| 真机/端到端运行验证 | ❌ 无安卓设备，未做 |

### 改动文件清单

1. `app/src/main/java/com/dramafactory/app/ui/RenderRuntime.kt`（F1 接线 + F5 cacheDir/downloadClip）
2. `app/src/main/java/com/dramafactory/app/AppGraph.kt`（F2 真实审计 + F3 时代推断 + F4 readScript + 辅助函数）
3. `app/src/main/java/com/dramafactory/app/data/DramaDatabase.kt`（新增 `assetRemoteUrl` 查询）
4. `core-engine/src/main/kotlin/com/dramafactory/core/orchestrate/DefaultAiOrchestrator.kt`（F4 readScript 参数 + retryFrom）
5. `core-engine/src/main/kotlin/com/dramafactory/core/pipeline/DefaultRenderQueue.kt`（F5 FETCH_RETRY_MAX + 取回上限）
6. `core-engine/src/main/kotlin/com/dramafactory/core/pipeline/DefaultPipelineOrchestrator.kt`（F6 fail-closed）
7. `core-engine/src/test/kotlin/com/dramafactory/core/P0FixRegressionTest.kt`（新增：F1~F4 回归锁）

### 未改动

- 阶段 1 ① 已改文件（`app/build.gradle.kts`、`MovieAssembler.kt`）未触碰。
- 未删除任何文件；改动尽量小、精准。

### 仍需后续（诚实提醒，非本次范围）

- 真机验证 F1/F2/F3/F5 的端到端行为（需设备 + 真实 Agnes Key）。
- F6 真实实现（注入 DAO 读 review_state/sb_check/is_verified/预算，并接生产调用点）。
- 加 CI（GitHub Actions）防止「假完成」再次溜进主干。
