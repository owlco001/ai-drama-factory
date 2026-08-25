# 「AI短剧工厂」core-engine 代码评审报告 R2（第二轮修复复审）

- 评审人：ai_review（只读，仅写 reports/）
- 范围：`src-ai-drama/core-engine/src/main/**`（11 文件）+ `src/test/**`（5 文件，新增 Round2FixRegressionTest.kt 448 行）
- 对照：reports/ai-drama-review-r1.md、dev-report 第二轮修复记录
- 测试证据：build/test-results XML 实测 **35 tests / 0 failures / 0 errors**（RateGate 5 + AgnesBackoff 7 + CheckpointBudgetKey 6 + QueueAssembler 6 + Round2Regression 11），与开发声明一致

---

## 逐项核验

### P0-1 SUBMITTING/RECONCILE 状态机 ✅ 真实落实
- `DefaultRenderQueue.processShot:132-146`：`markSubmitting(shotId)` 在 `submitVideo` **之前同步调用**（中间仅隔本地 prompt 组装，无任何可跳过路径）；拿到 video_id 后第一动作即 `markSubmitted`（:146），之后才 consumeSubmitted/repoll。
- `InMemoryCheckpointStore.markSubmitting/markSubmitted/markReconcile` 均在 mutex 内一次写入；接口注释明确「实现必须同步落盘、kill -9 由集成测试覆盖」。
- RECONCILE 绝不盲目重提：`pendingRepoll:107-112` 只捞 `SUBMITTED && taskId非空`；主循环（RenderQueue:98-99）只选 `PENDING`；RECONCILE 镜两条路都不通，只能等人工对账 ✓。
- 崩溃窗口覆盖：loadOrMerge 将遗留 SUBMITTING 翻转为 RECONCILE（Store:44-47）✓；AgnesProvider 对「2xx 但缺 video_id」抛专用 `ProviderError.ReconcileRequired`（含 rawBody，不再归为 ValidationError）→ 队列标 RECONCILE（Queue:160-162）✓；其余未知异常（瞬断/退避耗尽）也保守落 RECONCILE 而非 FAILED（:163-166）——方向正确（宁可多对账不可重复付费）。
- 回归测试 4 条覆盖「submit 挂起中杀进程」「video_id 即刻落库先于 poll」「计费但缺 id→RECONCILE 零重提」。

### P0-2 cancel+join 时序 ✅ 落实
- `enqueueEpisode:80-81`：`worker?.cancel(); worker?.join()` 后才 launch 新 worker，单消费者不变量恢复。测试验证重入后每镜恰一次提交、旧 worker 迟到提交被取消传播拒绝。

### P1-1 validateKey 并发竞态 ✅ 干净
- 共享 `var` 替换方案废弃，改为 `ValidatingKeyContext` 协程上下文元素 + per-call `currentApiKey()`（AgnesProvider:59-66）。候选 Key 仅存在于本次验证调用链，并发请求零影响，有并发断言测试。

### P1-2 BudgetGuard 扣减原子化 ✅
- `usedMap: ConcurrentHashMap<String, AtomicInteger>` + CAS 自旋（DefaultBudgetGuard:40-47），limits 构造入参快照化。500 线程并发测试恰各计一次。

### P1-3 CancellationException 不再吞 ✅
- processShot 与 repoll 各异常点均先 `catch (CancellationException) { throw e }`（Queue:150-152,180-182,194-195），取消不写 FAILED、不污染 checkpoint，有回归测试。

### P1-4 已付费镜不脱离 re-pool ✅
- repoll 内瞬断指数退避续轮同一 video_id（cap 60s）；下载失败保持 SUBMITTED 仅重试取回（:196-199），与生成失败严格区分。测试证明 pollFail=1 + 下载失败 1 次后仍复用原 video_id 完成、零重新 submit。

### P1-5 budget_exceeded 门 ✅ 落实，附一条边界缺陷（见下）
- enqueue 仅清非 `budget_exceeded` 暂停（Queue:75-77）；`resume(budget_exceeded)` 必须用户确认才放行（:218-223）。测试验证二次 enqueue 不烧第 2 条预算。

### P1-6 recoverOnBoot ✅ 干净
- 非空壳：遍历 `allEpisodeIds()` 逐集经 `queueFor ?: fallback` enqueue；`queue==null` 仅在 `queueFor` 也为 null 时才提前 return（Orchestrator:38-50），多集并行恢复形态被正确覆盖且有双集测试（epA 已付费只 repoll、epB 正常提交）。

---

## 本轮发现的新问题

### N-1（P2）budgetConfirmed 一次性放行位存在滞留窗口
- `resume(confirmedByUser=true)` 置位后若 worker 此刻正卡在上一镜的 repoll 中，flag 会一直存活到下一个 processShot——该次提交将越过预算门，而用户确认时的语境未必是这一笔。当前单消费者+顺序执行使窗口很小，但语义上应改为「放行位仅在紧随的一次门检查中有效」（如置位后带时间戳/序号，或在 repoll 返回后未消费即复位）。不构成直接超预算漏洞（每次确认至多多放行一笔），故列 P2。

### N-2（P2）RECONCILE 无出口
- RECONCILE 镜既不重提也不进 repoll，且核心层没有任何 API 让上层列出/处置 RECONCILE 镜（UI 无法弹「对账」入口）。防错方向正确，但钱可能永久悬置。建议补 `listByState(RECONCILE)` + 人工裁决接口（对账确认后转 SUBMITTED/PENDING）。

### N-3（P2）pollResult 返回空 url 的 Completed → 下载无限退避循环
- AgnesProvider completed 但 url 缺失返回 `PollResult.Completed("")`，downloader 必败 → repoll 内无限退避重试（只有间隔封顶，无次数上限）。建议空 url 视为 Failed 或设取回次数上限后转 RECONCILE/FAILED。

### 快扫其余文件
FfmpegAssembler / ChineseAudioInjector / DefaultRateGate / Models 无回归性改动问题；P2-4（TransientError 显式 `retryable` 字段）与 P2-5（getEpisode 锁内快照拷贝）本轮顺带修复 ✓。R1 遗留 P2 其余项维持登记。

---

## 结论：有条件通过

条件（下一迭代必须完成，不阻塞本轮合入）：
1. **Room 版 CheckpointStore 适配器接通**——P0-1 的进程级防重复付费承诺目前只在内存实现上成立（R1 已登记的未完成项 #1，仍是最大风险敞口）；
2. **N-1 收紧 budgetConfirmed 一次性语义**（防确认语境漂移）；
3. **N-2 提供 RECONCILE 处置通道**（避免资金悬置无解）；
4. N-3 取回失败加上限。

与 R1 对比：P0×2 全修复且实现质量高（意图先行+对账态设计正确）；P1×6 全修复；新引入问题仅 P2×3，均为边界加固而非不变量破坏。35 测试全绿经 build 产物实证。
