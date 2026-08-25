# 「AI短剧工厂」core-engine 代码评审报告 R1

- 评审人：ai_review（只读）
- 范围：`src-ai-drama/core-engine/src/main/**`（11 文件，~990 行）+ `src/test/**`（4 文件）
- 基准：docs/ai-drama-factory-architecture.md、ai-drama-factory-decisions.md、pavo-drama/scripts/agnes_client.py

---

## P0（阻塞，必须修复）

### P0-1 防重复付费生死线存在真实窗口，且核心层无任何持久化
- 位置：`pipeline/DefaultRenderQueue.kt:111-121`；`storage/InMemoryCheckpointStore.kt`（全文件）
- 问题：
  1. `submitVideo()` 返回 2xx/video_id 之后才调 `markSubmitted(shotId, taskId)`。若恰在此两行之间进程被杀（或 HTTP 响应已计费但响应体解析抛 `ValidationError`，见 AgnesProvider.kt:243-244——此时**远端任务已创建并扣费，本地却无 video_id**），恢复后该镜仍为 PENDING，必然重新提交 → 重复付费。
  2. 核心层唯一 CheckpointStore 实现是内存 Map。测试里「markSubmitted 后恢复」通过的前提是进程没死——真被杀时内存态全部丢失。架构§4.3 的零重复付费承诺在 core-engine 现状下不成立（依赖尚未交付的 Room 实现）。
- 建议：
  - 提交前先落一条 `SUBMITTING(shotId, submittedAt)` 意图记录；恢复时对 SUBMITTING 超时镜先调服务端任务列表/幂等键对账再决定是否重提。
  - `missing video_id` 分支不应归为 ValidationError 就完事：响应非 2xx 已计费的场景需标记「待对账」而非静默。
  - 在 CheckpointStore 接口注释中明确「实现必须同步落盘（Room + 事务）」，并把「kill -9 后恢复」纳入集成测试。

### P0-2 enqueueEpisode 直接 cancel 旧 worker 不等待，可产生并发双 worker
- 位置：`pipeline/DefaultRenderQueue.kt:59-60`
- 问题：`worker?.cancel()` 是异步取消，旧协程若正卡在 `submitVideo` 的限速门 sleep 或 HTTP 调用中，新 worker 立即启动后两者短暂并行：同一集的 PENDING 镜可能被两个 worker 同时选中重复提交（checkpoint 无 per-shot 锁），且绕过单消费者假设。
- 建议：改为记录 Job 并 `old.cancel(); old.join()`（enqueueEpisode 本就是 suspend，可直接 join），或在循环取镜处用 Mutex+状态二次校验。

---

## P1（重要缺陷）

### P1-1 validateKey 通过替换共享 var 换 Key，存在并发竞态
- 位置：`provider/AgnesProvider.kt:50`（`var apiKeyProvider`）、`177-192`
- 问题：验证期间全局替换 Key 来源 lambda，期间任何并发 chat/poll 都会用临时 Key 发请求；验证结束恢复也可能交错。多协程共享可变 var 无同步。
- 建议：Key 改为每次请求显式传参（header(...) 内调用方注入），或用 `Mutex` 包住 validateKey 并禁止并发提交。

### P1-2 DefaultBudgetGuard 扣减非原子
- 位置：`pipeline/DefaultBudgetGuard.kt:16,32-35`
- 问题：`usedMap` 为普通 HashMap，`consumeSubmitted` 是读-改-写三步无锁。当前队列单消费者掩盖了问题，但接口允许任意调用方并发触发，超限时可双写丢失一次扣减 → 超预算提交。`limits` 也是外部可变 Map。
- 建议：`@Volatile`+`AtomicInteger` 或 Mutex；构造入参改 `Map<String,Int>` 只读快照。

### P1-3 processShot 的 `catch (e: Exception)` 吞掉 CancellationException
- 位置：`pipeline/DefaultRenderQueue.kt:125-127`
- 问题：worker 被 cancel 时 submitVideo 内部 delay/HTTP 抛的 CancellationException 被捕获并 `markFailed`——把「用户取消」写成业务失败，破坏结构化并发语义，且污染 checkpoint。
- 建议：catch 前先 `catch (e: CancellationException) { throw e }`。

### P1-4 已付费镜头在轮询/下载异常后被标 FAILED，脱离 re-pool 通道
- 位置：`pipeline/DefaultRenderQueue.kt:131-145`（repoll 无 try）、`122-127`
- 问题：repoll 中 `pollResult` 抛瞬断、`downloader` 失败，都会冒泡到 processShot 的通用 catch → `markFailed(shotId)`。该镜 video_id 已付费，但 `pendingRepoll` 只捞 SUBMITTED 态，FAILED 镜永不再轮询也不重提——钱花了、片没了，且用户看不出差别。
- 建议：下载失败保留 SUBMITTED 仅重试下载（区分「生成失败」与「取回失败」）；pollResult 瞬断应在 repoll 内退避重试而非终止。

### P1-5 enqueueEpisode 无条件清除暂停态，绕过预算确认门
- 位置：`pipeline/DefaultRenderQueue.kt:58`
- 问题：`paused=false` 对 `budget_exceeded` 也生效。US6 要求超限必须用户确认才能放行，但分镜层只要再次 enqueue 即自动续跑烧钱。
- 建议：仅当 pausedReason 非 `budget_exceeded` 时清位。

### P1-6 recoverOnBoot 是空壳
- 位置：`pipeline/DefaultPipelineOrchestrator.kt:31-36`
- 问题：接口注释承诺「扫描各集 checkpoint 并触发续跑」，实现体为空注释。架构§恢复路径未落实。
- 建议：至少实现遍历 checkpointStore 全部 episodeId 并逐个 enqueueEpisode；无法在本层完成则显式抛 TODO 异常并在 DELIVERY 注明。

---

## P2（改进项）

1. **FfmpegAssembler.allSameSpec 桩失真**（`assemble/FfmpegAssembler.kt:79`）：只要文件非空即判同规格 → 混合分辨率走 concat -c copy 产出花屏/时长错乱且返回 Success。建议一级路径前接 ffprobe 探测，或桩内默认返回 false。
2. **ffmpeg 执行无超时**（FfmpegAssembler.kt:83-89）：`waitFor()` 可永久挂起；三级降级的 10 分钟判定（:61）是事后检查，无法中断进行中的归一化。建议 `waitFor(timeout)` + destroyForcibly。
3. **concat 清单路径未转义单引号**（FfmpegAssembler.kt:42,68）：含 `'` 的路径会炸 ffmpeg 语法。
4. **TransientError 可重试性靠消息字符串 `(retryable)` 判定**（AgnesProvider.kt:252）：脆弱，建议 ProviderError.TransientError 增加 `retryable: Boolean` 字段。
5. **InMemoryCheckpointStore.getEpisode 返回内部可变结构且不加锁**（InMemoryCheckpointStore.kt:80-82）：渲染队列在锁外遍历 shots，与 loadOrMerge 写入存在可见性竞态（内存版侥幸，Room 版无此问题）。另 `diskFiles` 公开可变，建议收口。
6. **BudgetGuard 默认上限硬编码 50**（DefaultBudgetGuard.kt:30,38）：未 setLimit 的项目静默获得 50 条配额，应显式要求配置或视为不可提交。
7. **VideoSubmitRequest / ChatRequest 未标 @Serializable**（model/Models.kt:22,41）：与文件头「严格按架构§3」及序列化需求不一致，跨层传递（Bundle/Room 嵌入）时会踩坑。
8. **ChineseAudioInjector.inject**（ChineseAudioInjector.kt:23）：prompt 以 `。！？」` 结尾时追加成 `」。全程…` 双标点；小瑕疵。
9. **限速门语义核对结论（正面）**：DefaultRateGate 与 pavo `wait_video_submit_slot` 逐条一致——Mutex 持锁期间 sleep（正确地串行化后续协程）、首发免等、占坑即记时、非法 interval 兜底 120s。429 计数器为局部变量，无跨任务残留 ✓。401 零重试 ✓（有测试）。num_frames 8n+1 / 尺寸 64 倍数归一 ✓（有测试）。Key 不进日志/异常消息，掩码前3后3 ✓。
10. **测试覆盖良好但缺并发场景**：无限速门多协程并发提交测试、无 enqueueEpisode 重入测试、无取消传播测试。

## 决议对照摘要
- Q2 条数型预算 ✓（但有 P1-2 并发缺陷）；Q5 三级拼装策略 结构✓/桩失真(P2-1)；Q6 三通道独立接口 ✓；Q9 中文配音注入 ✓（有测试）；US5 零重复付费 ✗（P0-1）；六张表由 app 层 Room 承担，本层未涉及。

---

## 结论：打回

理由：P0 两项均触及本项目最高优先级不变量——防重复付费（P0-1 含真实丢 video_id 的计费窗口 + 核心层无可持久化实现）与单消费者队列前提（P0-2）。P1 六项中三项（P1-3/4/5）同样会在生产路径造成资金或数据损失。请开发 Agent 修复全部 P0 与 P1-2/3/4/5 后重新提审；P1-1/6 与全部 P2 可随下一迭代处理。
