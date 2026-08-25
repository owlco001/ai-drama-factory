# 代码复审报告：core-engine 第二轮修复（review-20260825-round2）

- 评审人：ai_review
- 范围：上轮 P0×2、P1-1~7、P2-1/2 修复核验 + 全部改动文件新缺陷扫描
- 对照：reports/review-20260825.md、reports/dev-report.md「第二轮修复记录」
- 结论：**有条件通过**（P0×0、P1×0；遗留3项P2条件项随下一迭代完成即可）

---

## 一、上轮问题逐条核验

### P0-1 L2记忆ID与context_refs不匹配 —— ✅ 已修复（附残留风险）
- 位置：`memory/MemoryStore.kt:14,61-66`、`agent/BaseSubAgent.kt:49-55,59-68`
- 核验：`putTaskMemory()` 现返回真实 memoryId（`mem_task_${seq}_$nodeId`）；BaseSubAgent 用返回值填 `detail_ref`。跨节点传递经集成测试验证可用（Round2FixTest 2条）。
- ⚠️ 残留（P2-R1）：Orchestrator 构造 refs 时仍用合成格式 `"mem_${taskId}_$nodeId"`（`orchestrator/Orchestrator.kt:197`），并非真实 memId——链路能通完全依赖 `endsWith("_$ref")` 后缀兜底匹配。若未来出现 nodeId 互为后缀（如 `a1` 与 `xa1`）或同 task 内 seq 混淆，会错配/漏配。建议：调度器在收到 TASK_RESULT 时记录 nodeId→detail_ref 映射，构造 refs 用真实 memId；至少在代码注释中固化「nodeId不得互为后缀」约束。

### P0-2 file_read 兄弟目录前缀绕过 —— ✅ 已修复
- 位置：`tools/impl/BuiltinTools.kt:136-159`
- 核验：canonicalFile 归一化 + `isUnder()` 逐级父链比较，边界情况逐一确认：
  - 根路径 `/`：parentFile 为 null，循环正常退出拒绝 ✔
  - base 自身：`canon.path == base.path` 显式放行 ✔
  - 深层嵌套：父链必经 base ✔
  - `../`、绝对路径出界、symlink 出界、兄弟前缀目录（knowledge_x / `${base}_secrets`）：均拒绝，有对应测试4条 ✔
- 备注：读文件用 `f.readText()` 而 canonical 校验用 `f.canonicalFile`，存在理论性 TOCTOU 窗口（校验后被换 symlink），MVP 可接受，不另立条目。

### P1-1 失败短路 —— ✅ 已修复
- 位置：`orchestrator/Orchestrator.kt:83,100-103,113-116`
- 核验：`succeeded` 集合 + `dependsOn.any { it !in succeeded }` 判定，失败节点后继全部 SKIPPED 且不执行；测试断言 ran.isEmpty()。
- 小瑕疵（P2-R2）：`markSkippedFrom()`（224-230行）已修正语义但仍无调用点，属死代码，建议删除或接入。

### P1-2 六层保险补全 —— ✅ 已修复
- ① deadline：`Orchestrator.kt:93` 循环顶部检查，超期剩余节点 SKIPPED ✔
- ② MAX_REPLY_DEPTH：`:97` 以 `++replyDepth > 16` 落地。注意它实际按分派节点数计数而非消息reply链深，与架构§6.2字面语义有偏差，但作为防失控保险有效（记入P2观察项）。
- ③ validateDag：`:88-90,173-193` Kahn 验环 + 依赖引用校验，非法计划直接失败 ✔
- ⚠️ 条件项（P2-R3）：含环 DAG 的拒绝路径无法从外部构造（planTask 模板天然无环），现有测试只验证合法DAG放行（Round2FixTest 注释自认）。建议将 validateDag 提为 internal 或注入计划接口以便补一条真实环拒绝测试。

### P1-3 取消与超时 —— ✅ 基本修复（一处需收敛）
- 核验：BaseSubAgent.kt:73-74 与 Orchestrator.kt:158 对非超时 CancellationException 重抛；超时路径 `engine?.stop()` 两处（155、161）落地并有断言测试 ✔ 正常超时转 FAILED 语义由 `withTimeoutOrNull ?: run` 分支（160-163）保证。
- ⚠️ 条件项（P2-R4）：`Orchestrator.kt:154-157` 在 withTimeoutOrNull 块内部捕获 TimeoutCancellationException 并返回值——若该异常来自外层超时（协程已被取消），吞掉后在块内继续执行与 kotlinx.coroutines 的取消契约相悖，行为依赖实现细节，可能破坏「超时→FAILED」的确定性。由于 160-163 的 `?:run` 分支已完整覆盖同一逻辑并调 stop()，建议删除内层 catch（仅保留通用 Exception 兜底）让超时统一走 null 分支。

### P1-4 总线锁不一致/丢消息 —— ✅ 已修复
- 位置：`bus/MessageBus.kt:132,137-166`
- 统一单一 `lock` 保护 log/handlers；tryEmit 失败计数+stderr告警（droppedCount 的 `@Volatile ++` 非原子，仅告警用途可接受）；handler 由 send 经 handlerScope 派发。
- 小瑕疵：handlerScope 若无 SupervisorJob，单handler异常会波及外部传入scope——建议文档注明或包 runCatching。

### P1-5 日志环形缓冲 —— ✅ 已修复（ConcurrentLinkedDeque 上限1000，141行）
### P1-7 taskId 碰撞 —— ✅ 已修复（`newUuidV7()`，Orchestrator.kt:70）
### P2-1 结构化取node_id —— ✅ 已修复（216行）
### P2-2 计算器一元负号/除零 —— ✅ 已修复（112、109行；`-(...)` 形式验证可解析）
### 未修项维持登记：P1-6剪贴板脱敏、P2-3~P2-8 —— 维持上轮登记，不阻塞本轮结论

## 二、其余改动扫描

未发现新引入的 P0/P1 缺陷。上述 P2-R1~R4 及两处小瑕疵均为本轮新增观察项。

---

## 三、结论：有条件通过

| 级别 | 数量 | 明细 |
|---|---|---|
| P0 | 0 | — |
| P1 | 0 | — |
| P2（条件项） | 4 | R1 refs改用真实memId或固化后缀匹配约束；R2 删除死代码markSkippedFrom；R3 补真实含环DAG拒绝测试；R4 收敛内层TCE捕获 |
| P2（观察/沿用登记） | 10 | 上轮P1-6、P2-3~P2-8 + replyDepth语义偏差、handlerScope隔离 |

条件：P2-R1、R3、R4 在下一迭代内完成（均为小改动+各补1条测试）；R2 顺手清理。完成即视为全绿，无需再开第三轮专项复审。
