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

---

# 第三轮：UI层（v0.2）

## 构建与测试结果（真实执行）
- `:app:assembleDebug` → **BUILD SUCCESSFUL**，产出 app-debug.apk（约31MB，PRD≤80MB达标）
- `:app:testDebugUnitTest` → **9/9 通过**（UiLogicTest，纯JVM无Robolectric）
- `:core-engine:test` → UP-TO-DATE全绿（未触碰已验收引擎代码）
- 工具链：Gradle 8.10 / Kotlin 2.0.21 / AGP 8.5.2 / Compose BOM 2024.10.01（compose 1.7.x + material3 1.3.0）/ lifecycle 2.8.6

## 交付页面清单（Compose + Material3 全中文）
| 页面 | 文件 | 要点 |
|---|---|---|
| 七阶段主导航 | ui/DramaApp.kt | 底部导航6项：项目→资产→分镜→渲染→成片→设置；AppNavState跨页传项目/集上下文 |
| 项目列表（S1+S2） | ui/ProjectsPage.kt | 新建项目（名称+OpenDocument导入TXT/MD，扩展名/空文件/200万字截断校验）、进入、删除确认框 |
| 设置页（P0） | ui/SettingsPage.kt + ui/SettingsLogic.kt | Agnes供应商卡片；Key输入（仅掩码回显sk-***abc）；「测试连通」调validateKey显示延迟/错误分类；通过后才允许保存至AndroidKeyVault；ROM保活指引卡 |
| 资产库（S3/S4） | ui/AssetsPage.kt + ui/AssetsLogic.kt | 角色/场景/道具FilterChip分组卡片流；添加即生成（TextProvider细化prompt→ImageProvider出图）；评审勾选保留/重生成；全部keep才点亮「去渲染」（reviewPassed闸门语义） |
| 渲染队列（S6） | ui/QueuePage.kt + ui/QueueLogic.kt | 总进度条+预算用量条；镜状态机实时刷新（Room轮询2s：SUBMITTING/SUBMITTED/POLLING(SUBMITTED)/COMPLETED/FAILED/BLOCKED/RECONCILE中文标签）；暂停/恢复/取消；预算超限自动弹确认框→resume(confirmedByUser=true)对齐budgetConfirmed一次性放行位；RECONCILE人工处置对话框（重试置回PENDING+续跑/放弃置BLOCKED终态） |
| 成片库（S7） | ui/LibraryPage.kt | 按集完成度列表；全部COMPLETED后可导出分享（FileProvider+ACTION_SEND video/mp4） |
| 分镜编辑（S5占位增强） | ui/StoryboardPage.kt | 只读镜头列表（台词/旁白/动作/六铁律校验位） |

## 引擎接线
- **AppGraph**（app/AppGraph.kt）：KeyVault(AndroidKeyVault)/CheckpointStore(Room版)/AgnesProvider三通道/DefaultBudgetGuard 单例依赖图
- **RoomCheckpointStore**（app/data/RoomCheckpointStore.kt）：DefaultRenderQueue持久化后端（复审条件项）。语义严格对齐InMemory参考实现：load-or-merge权威态保留、SUBMITTING→RECONCILE、COMPLETED需file_size>0、markSubmitted同步落库video_id生死线；DAO补renderTasksOfShot/allEpisodeIds查询
- **RenderRuntime**（app/ui/RenderRuntime.kt）：按集懒建DefaultRenderQueue（单episode单worker）；downloader落盘clip校验size>0；orchestrator(queueFor)支持recoverOnBoot多集恢复；DramaApplication.onCreate绑定appScope并开机自动恢复续跑
- **RenderForegroundService真实接线**：订阅queue.state Flow→常驻通知实时刷新（N/M镜·ETA·暂停原因引导）；队列跑完自动撤下；START_STICKY配合checkpoint续传
- **Manifest**：注册DramaApplication；FileProvider+res/xml/file_paths.xml供成片分享

## 测试覆盖（UI层逻辑9条全绿，纯JVM）
- 设置页(3)：测试连通成功才允许保存并清空明文+掩码正确、失败显示401且拒绝保存、空Key提示
- 项目页(2)：TXT/MD扩展名与空文件校验、空名拒绝+成功创建刷新列表
- 资产页(2)：全keep评审门放行+regen触发再生成落库、生成失败不拖垮状态机可重试
- 队列页(2)：budget_exceeded弹窗→confirmBudget走confirmedByUser=true放行位（P1-5对齐）、RECONCILE重试置回PENDING/放弃置BLOCKED

## Compose预览
SettingsPage/ProjectsPage/AssetsPage(AssetCardView)/QueuePage/LibraryPage/StoryboardPage 均附@Preview(locale="zh")。

## 遗留问题 / 限制（共4项，详见reports/issues.md）
1. QueueViewModel当前绑定"default"集（分镜自动生成本体在引擎侧迭代后按集入队）；「开始渲染」按钮在无shots时禁用。
2. AssetsPage生成结果以URL文本展示，图片缩略图加载（Coil等）留待下一迭代。
3. FGS启动入口暂由后续渲染流程触发（通知权限运行时请求POST_NOTIFICATIONS需真机验证）。
4. evaluateGates仍为引擎桩：评审/六铁律结果已在UI层可判定，但GateReport真实接线待编排器迭代。

---

## 第四轮：真机反馈修复（2026-08-25，安卓开发Agent）

用户（老王）真机反馈四项，本轮全部处理完毕。

### 1. 闪退防御性加固
根因排查：`DramaApplication.onCreate → AppGraph.init → AndroidKeyVault` 的 EncryptedSharedPreferences/MasterKey 初始化在部分机型（Android Keystore/StrongBox 异常、厂商ROM）抛 ProviderException/KeyStoreException 直接闪退；recoverOnBoot 与 Room 初始化失败同样会阻断启动。

修复：
- **AndroidKeyVault 四级降级链**：L1 StrongBox请求 → L2 普通Keystore MasterKey → L3 明文 SharedPreferences（沙箱内）→ L4 内存实现。任一级失败静默降级，`lastInitError` 记录原因供UI提示；工厂方法 `create()` 保证永不抛异常。
- **AppGraph.init 分步容错**：KeyVault/Room 各自独立 try-catch；Room 失败时以 BrokenDramaDao（空操作DAO）+ InMemoryCheckpointStore 兜底，App 可打开、设置页可配置。
- **全局未捕获异常处理器**（`AppGraph.CrashLog.installCrashLogger`）：崩溃堆栈写 `files/crash/last_crash.txt`，下次启动可读便于真机排查。
- DramaApplication onCreate 全链 runCatching 双保险；MainActivity setContent 防崩溃兜底。

### 2. 多模型供应商选择
设置页新增单选列表（ProviderRegistry），共7项：Agnes(PavoAPI)【可用】、可灵Kling、即梦/豆包Seedance、Runway、Luma、Pika【后五者占位"待接入"标记，选择器可见可选但提示暂不可渲染】、自定义模型【可用】。每个供应商独立 configId 存 KeyVault。

### 3. 自定义模型（OpenAI兼容）
选中「自定义模型」展开表单：base_url + model_id + api_key + 提交方式说明。协议模板默认 POST `{base_url}/videos` 提交、GET `/videos/{id}` 轮询（与 pavo agnes_client.py 同构）。保存时：Key 入 KeyVault 加密存储（configId=custom-video），base_url/model_id/note 落 provider_configs 表 extra_params；表单明文立即清出。

### 4. 剧本导入
项目页新增「小说模式 / 剧本模式」FilterChip 切换 + 两种导入方式（文件 TXT/MD 选择器、粘贴文本）。剧本模式轻量场次解析（第X场/场景N/SCENE N/内景/外景/INT./EXT.），显示场次提示；创建项目时 episode.stage_flags 写入 `{"script_mode":true,"scene_hint":N}`，资产页据此跳过文本分析直接进分镜编辑。

### 测试结果
- 新增 `Round4FeedbackTest`（8用例）：供应商注册表完整性、待接入供应商语义、自定义模型保存/非法拒绝、剧本文件导入场次解析、粘贴导入、小说路径回归、中英文场次解析与启发式判断。
- `:app:assembleDebug` ✅ BUILD SUCCESSFUL；全量测试 ✅ 52/52 绿（app 17 + core-engine 35），0 failures。
