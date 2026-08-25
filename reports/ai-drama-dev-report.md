# 「AI短剧工厂」开发报告 — 项目初始化与管线核心层

日期：2026-08-25 ｜ 安卓开发Agent ｜ 上游：docs/ai-drama-factory-{prd,decisions,architecture}.md ｜ 逻辑移植基准：pavo-drama agnes_client.py / task_queue.py / orchestrator.py

## 构建与测试结果（真实执行）
- `:core-engine:test` → **BUILD SUCCESSFUL，24/24 通过**（JUnit5 + kotlinx-coroutines-test + Ktor MockEngine）
- `:app:assembleDebug` → **BUILD SUCCESSFUL**，产出 app-debug.apk（约12MB；PRD安装包≤80MB达标）
- 工具链：Gradle 8.10 / Kotlin 2.0.21 / AGP 8.5.2 / Android SDK 34（minSdk 29）

## 模块清单（/root/project_workspace/src-ai-drama/）
| 路径 | 内容 |
|---|---|
| settings/build.gradle.kts | 多模块骨架：`:core-engine`（纯Kotlin引擎，JVM可测）+ `:app`（Android壳）；零Firebase |
| core-engine/.../model/Models.kt | VideoSubmitRequest/PollResult/ShotState/BudgetUsage/GateReport/QueueSnapshot等（架构§3签名一致）+ ProviderError四分类(Auth/Quota/Validation/Transient) |
| core-engine/.../provider/Providers.kt | VideoProvider/TextProvider/ImageProvider、RateGate、RenderQueue、CheckpointStore、BudgetGuard、PipelineOrchestrator、KeyVault —— 接口签名严格按架构§3 |
| core-engine/.../provider/AgnesProvider.kt | Agnes三通道适配器完整实现：120s限速门第一行调用、429立即抛QuotaError+外层长退避(base30s cap180s×3次)、5xx指数退避(2s×2^n≤3次)、401零重试、num_frames归一8n+1≤441/尺寸64倍数/frame_rate[1,60]前置校验、keyframes双帧必须带mode=keyframes、generate_audio=true+audio=true原生声轨、enable_thinking=false、Key掩码前3后3、响应体截断脱敏 |
| core-engine/.../pipeline/DefaultRateGate.kt | Mutex保护单例时间戳（对齐wait_video_submit_slot）；首发免等；非法间隔兜底回120s |
| core-engine/.../pipeline/DefaultRenderQueue.kt | 单消费者协程；提交2xx即刻markSubmitted落库→自适应轮询30s→60s→下载size>0校验COMPLETED；预算超限/401项目级PAUSED；恢复时pendingRepoll队头优先绝不重新submit；FAILED/BLOCKED权威态不翻转 |
| core-engine/.../storage/InMemoryCheckpointStore.kt | load-or-merge语义：SUBMITTED(video_id)/FAILED/BLOCKED保留、补缺失镜、COMPLETED文件缺失或0字节重置PENDING（JVM测试用，语义与app层Room版一致） |
| core-engine/.../pipeline/DefaultPipelineOrchestrator.kt | 七阶段状态机S1-S7 + evaluateGates + recoverOnBoot入口（Gate真实判定桩待UI/分镜层接线） |
| core-engine/.../assemble/FfmpegAssembler.kt | 三级策略：concat -c copy → scale+pad 448x832归一化(端侧h264_mediacodec/JVM libx264) → 分段导出(每8镜)；执行器抽象可注入，JVM测试走命令行ffmpeg桩；Assembler.cloud()接口预留 |
| core-engine/.../provider/ChineseAudioInjector.kt | 决议Q9：中文台词主导开头+「全程使用中文普通话配音」追加、幂等注入、中文主导启发式判定 |
| core-engine/.../storage/InMemoryKeyVault.kt | KeyVault内存假实现供JVM测试 |
| app/.../data/DramaDatabase.kt + assets/drama_factory_schema.sql | Room实体×6表（projects/assets/shots/render_tasks/provider_configs/episodes）+ DAO，与架构§5 SQL逐句对应；pendingRepoll查询内置防重复付费判定 |
| app/.../security/AndroidKeyVault.kt | EncryptedSharedPreferences封装（MasterKey AES256-GCM + Keystore托管 + StrongBox优先），allowBackup=false |
| app/.../service/RenderForegroundService.kt | FGS桩：dataSync类型、常驻进度通知（第X集·N/M镜·ETA）、START_STICKY配合checkpoint续跑；队列Flow接线留下一迭代 |
| app/src/main/AndroidManifest.xml / MainActivity.kt | 权限声明（INTERNET/FGS/dataSync/通知）+ UI占位Activity |

## 测试覆盖（24条全绿）
- RateGateTest(5)：首发免等、第二次阻塞至满120s、并发串行占坑不穿透、非法间隔兜底120s、TEXT通道不受限
- AgnesBackoffTest(7)：429两次后第三次成功且退避序列=[30s,60s]、三次429耗尽上抛QuotaError恰3次尝试、401零重试、参数前置校验(8n+1/64倍数/clamp)、keyframes带mode+中文指令+generate_audio、Key掩码、轮询自适应30s→60s
- CheckpointBudgetKeyTest(6)：★提交成功未落库后恢复零重复提交(re-poll原video_id)、loadOrMerge补镜+completed文件缺失重置、size>0约束、FAILED权威态不翻转、预算超限拦截、Key保存/读取/掩码/删除往返
- QueueAssemblerTest(6)：队列端到端每镜恰一次提交+落库submitted、预算超限暂停→确认加量后放行续跑、中文台词主导开头+幂等注入、中文主导启发式、concat copy快路径、三级降级到分段导出(9镜=2段)

## 未完成项 / 限制（共5项）
1. **Room版CheckpointStore适配器未接**：app层DAO就绪但运行时仍需一个装饰器把DefaultRenderQueue的CheckpointStore指向Room（当前JVM验证用InMemory实现），下一迭代补齐。
2. **Orchestrator Gate真实判定为桩**：evaluateGates目前返回全通过位图，评审(F04)/六铁律(sb_check)结果需从episodes/shots表读出后接线。
3. **FGS与RenderQueue未绑定**：Service持有通知逻辑但queue.state Flow→通知刷新的接线待UI迭代；国产ROM白名单指引页(Q7)未做。
4. **FFmpegKit真机集成缺失**：core-engine用命令行ffmpeg桩验证了三级策略逻辑；端侧com.arthenica:ffmpeg-kit-full-gpl依赖与h264_mediacodec真机基准(S2延伸验收)需真机环境。
5. **UI层为占位**：七阶段导航/评审画廊/队列卡片/播放导出（F01-F05/F10）按任务范围留待下一迭代。

## 关键决策说明
- 网络层按要求Ktor Client(OkHttp engine)+MockEngine测试；429在HTTP层立即抛QuotaError、仅视频提交外层长退避——与agnes_client.py逐行语义对齐。
- RenderQueue.enqueueEpisode签名扩展为(episodeId, shots)，shots由分镜层供给（接口其余部分保持架构§3原文）。
