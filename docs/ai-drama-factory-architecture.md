# 「AI短剧工厂」安卓App 系统架构设计文档

| 项 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 日期 | 2026-08-25 |
| 作者 | 架构Agent |
| 状态 | 待评审 |
| 上游文档 | ai-drama-factory-prd.md（v1.0）、ai-drama-factory-decisions.md（Q1–Q9 决议 + S1/S2 冒烟结果） |
| 复用参考 | pavo-drama v0.9.8（agnes_client.py / orchestrator checkpoint 状态机）、「Agent团队」架构基线 |

---

## 0. 决议对齐（本设计的输入约束）

| 决议/冒烟 | 对架构的影响 |
|---|---|
| Q1+S1：移动端直连 Agnes ✅ | **无代理层**，App 直连 `apihub.agnes-ai.com`；网络层需支持弱网重试与长轮询 |
| Q5+S2：FFmpegKit 端侧拼接 ⚠️有条件通过 | 默认端侧 FFmpegKit（h264_mediacodec 硬编）；保留**分段导出降级路径**，真机基准列入开发验收项 |
| Q2 | MVP 预算闸门只做**条数型**上限；金额仅按牌价展示估算 |
| Q3/Q6 | **三通道独立适配器**（Video/Text/Image Provider）；第三方供应商 P2，MVP 只保证接口抽象 |
| Q9 | 提交视频默认注入中文配音指令 prompt（产品级默认行为，写进 AgnesProvider） |
| pavo 实战继承 | 120s 提交限速门、429 长退避不快重试、`submitted` 态记 video_id 防重复付费、load-or-merge 恢复语义、G1/G2 两层资产闸门、六铁律结构闸门失败即阻断 |

---

## 1. 技术选型及理由

### 1.1 总体技术栈

| 层 | 选型 | 版本基线 | 理由 |
|---|---|---|---|
| 语言/UI | Kotlin + Jetpack Compose + Material3 | Kotlin 2.0 / AGP 8.x | 与「Agent团队」项目同基线可复用脚手架；Compose 适合七阶段导航/队列卡片流等动态UI；协程天然契合异步提交+轮询建模 |
| 架构模式 | MVVM + 单Activity多Screen + Hilt | — | PipelineOrchestrator/RenderQueue 作为单例注入；Foreground Service 与 UI 通过共享单例+Flow 解耦 |
| 网络 | **Ktor Client 2.x** | — | 见 1.2 选型对比 |
| 视频拼接 | **FFmpegKit**（`ffmpeg-kit-full-gpl`，含 mediacodec） | 6.x | 见 1.3 |
| 本地存储 | Room 2.6（SQLite WAL） | — | 项目/资产/分镜/checkpoint 结构化持久化，编译期 SQL 校验 |
| 异步 | Coroutines + Flow（StateFlow/SharedFlow） | — | 渲染状态机事件流直通通知栏与UI |
| JSON | kotlinx.serialization | — | 编译期生成，无反射 |
| DI/后台 | Hilt + WorkManager（辅助）+ Foreground Service（渲染主承载） | — | 渲染用 FGS 保活（PRD F09）；非关键清理类任务走 WorkManager |
| minSdk | API 29（Android 10），arm64-v8a | — | PRD 兼容性要求 |

### 1.2 网络层：Ktor vs OkHttp —— 结论：**Ktor Client**

| 维度 | Ktor Client | OkHttp |
|---|---|---|
| 协程原生 | suspend 原生 API，取消即传播到传输层——渲染队列 cancel/暂停时轮询请求能真正中断 | 回调式，需封装转协程，取消语义易漏 |
| 多引擎 | Android 主引擎 OkHttp 底座 + 可替换（未来桌面调试用 CIO） | 单一 |
| 内容协商 | kotlinx.serialization 官方插件，与全 App 序列化统一 | 需自配 converter |
| 轮询友好 | HttpTimeout 可按请求配置长超时；插件管道统一挂鉴权/日志脱敏 | 同等能力但样板更多 |
| 成熟度 | Android 生产可用；底层仍复用 OkHttp 传输栈，稳定性不打折 | 最成熟 |

**理由总结**：本项目网络模式是"低频长请求 + 自适应轮询 + 强取消传播"，Ktor 的协程原生性与 kotlinx.serialization 统一性收益最大；且底层引擎仍可选 OkHttp，性能无损失。OkHttp 仅作为 Ktor 的底层 engine 引入。

### 1.3 FFmpegKit 集成方案（Q5）

- 依赖：`com.arthenica:ffmpeg-kit-full-gpl:6.0-2.LTS`（含 libx264 与 mediacodec 硬编支持；LTS 版兼容 minSdk 24+，规避 16KB page size 问题可后续评估迁移）。
- 拼接策略分级：
  1. **快路径**：所有片段编码参数一致 → `-f concat -c copy`，秒级完成（S2 实测 24 镜 2.5s）；
  2. **归一化路径**：混合分辨率/帧率 → scale+pad 到 448x832 + `-c:v h264_mediacodec` 硬编（S2 服务器 CPU 11.1s，手机 SoC 预估 2–5 分钟，真机基准待验）;
  3. **降级路径**：真机耗时 > 10 分钟或 OOM → 分段导出（每 8 镜一段）+ 提示用户云端组装（预留接口 `Assembler.local()/cloud()`）。
- 执行约束：拼接跑在独立进程级 `Dispatchers.Default.limitedParallelism(1)`；导出前检查剩余空间（成片 200–500MB，PRD §6.1）；会话被杀时临时文件可重建（镜片段均已在磁盘）。

---

## 2. 模块划分图

```
┌────────────────────────────────────────────────────────────────────┐
│                        UI 层 (Compose, MVVM)                        │
│  ①项目列表 ②小说导入 ③资产库 ④评审画廊 ⑤分镜编辑 ⑥渲染队列 ⑦成片预览 │
│  + 设置(供应商/Key/预算) + 阶段进度条导航(Gate拦截直达)              │
│   ViewModel: ProjectVM AssetVM ReviewVM StoryboardVM RenderVM ...   │
├────────────────────────────────────────────────────────────────────┤
│                     管线引擎层 (Pipeline Engine)                     │
│  ┌──────────────────┐   ┌─────────────────────────────────────┐   │
│  │PipelineOrchestrator│  │ RenderQueue(单消费者协程,限速门)     │   │
│  │ 七阶段状态机/Gate  │  │ pending→submitted→completed/failed  │   │
│  │ 判定与阶段推进      │► │ →blocked                            │   │
│  └──────┬───────────┘   └──────────────┬──────────────────────┘   │
│         │                              │                          │
│  ┌──────▼───────┐  ┌───────────────┐ ┌─▼──────────────┐           │
│  │AssetGate(G1/G2)│ │StoryboardGate │ │ BudgetGuard    │           │
│  │ 六铁律校验     │  │ (error即阻断) │ │ 条数上限/确认   │           │
│  └──────────────┘  └───────────────┘ └────────────────┘           │
├────────────────────────────────────────────────────────────────────┤
│                    供应商适配层 (Provider Layer)                      │
│  VideoProvider ── AgnesVideoProvider (120s限速门/keyframes/中文配音) │
│  TextProvider  ── AgnesTextProvider (writer/审计/多模态评分)          │
│  ImageProvider ── AgnesImageProvider (6pose包/i2i合成)               │
│  （P2: KlingProvider / JimengProvider / ViduProvider 仅增实现类）     │
├────────────────────────────────────────────────────────────────────┤
│  后台服务层: RenderForegroundService(常驻通知+进度/ETA+事件通知)      │
├────────────────────────────────────────────────────────────────────┤
│                        存储层                                        │
│  Room(SQLite WAL): projects/assets/shots/render_tasks(checkpoint)/  │
│  provider_configs(Key密文) + 文件存储 app专属目录(run_dir镜像)       │
│  KeyVault: Android Keystore + EncryptedSharedPreferences            │
│  Assembler: FFmpegKit 拼接(快路径→归一化→分段降级)                   │
└────────────────────────────────────────────────────────────────────┘
```

模块职责与依赖规则：

| 模块 | 职责 | PRD 功能 |
|---|---|---|
| UI层 | 七阶段导航、Gate 拦截展示、评审交互、队列卡片、播放导出 | F01–F05/F10 |
| PipelineOrchestrator | 阶段推进状态机；Gate 条件判定（§4.1 的七阶段进入条件）；恢复入口 | 全局 |
| RenderQueue | 逐镜异步提交+轮询；限速门；自适应轮询间隔；checkpoint 写入 | F07/F08 |
| 三闸门 | AssetGate(G1硬校验/G2多模态)、StoryboardGate(六铁律)、BudgetGuard | F03/F05/F11 |
| Provider层 | 三通道云API适配；错误分类（Auth/Quota/Validation/Transient） | F06 |
| ForegroundService | 渲染守护、通知更新、Doze 下持续轮询 | F09 |
| 存储层 | Room 持久化 + run_dir 文件镜像（资产图/镜片段/成片） | F08/F10 |
| KeyVault | Key 加密存取、脱敏展示 | F06/安全 |

依赖方向：UI→引擎→适配→存储，禁止反向；UI 不直接触碰 Provider。

---

## 3. 核心接口定义（Kotlin 签名级）

```kotlin
// ============ 供应商三通道 ============
interface VideoProvider {
    val id: String                       // "agnes" / 未来 "kling"...
    /** 测试连通（最小成本请求）。Key 由 KeyVault 按 configId 取得 */
    suspend fun validateKey(key: String): Result<ConnectionInfo>
    fun listModels(): List<ModelSpec>
    /**
     * 提交一镜视频任务。实现内部必须：
     * ① 先过 120s 提交限速门（RateGate）
     * ② keyframes 双帧模式 + generate_audio=true + 中文配音指令注入（决议Q9）
     * 返回 providerTaskId(video_id)。调用方拿到后【立即】落库 submitted 态。
     */
    suspend fun submitVideo(req: VideoSubmitRequest): String
    /** 轮询任务。返回终态(completed带url / failed带reason)或进行中(progress) */
    suspend fun pollResult(providerTaskId: String): PollResult
}

data class VideoSubmitRequest(
    val shotId: String,
    val prompt: String,                  // 已含中文配音主导开头+显式中文指令
    val negativePrompt: String?,
    val firstImageUri: String?,          // keyframes 模式首帧(data URI)
    val lastImageUri: String?,           // 尾帧；两者齐备才发 mode=keyframes
    val width: Int = 448, val height: Int = 832,
    val numFrames: Int = 121, val frameRate: Float = 24f,
    val generateAudio: Boolean = true,
)

sealed interface PollResult {
    data class InProgress(val progress: Int?) : PollResult
    data class Completed(val videoUrl: String) : PollResult
    data class Failed(val reason: String) : PollResult
}

interface TextProvider {   // writer / spec提取 / G2审计 / 忠实性比对
    suspend fun chat(req: ChatRequest): ChatResponse      // enable_thinking=false 约定
}
interface ImageProvider {  // 6pose包 / 场景 / 道具母图 / i2i合成
    suspend fun generateImage(req: ImageGenRequest): String  // url or data uri
}

// ============ 提交限速门（继承 pavo wait_video_submit_slot 语义）============
interface RateGate {
    /** 视频提交前阻塞至距上次提交 ≥ intervalMs（默认120_000，可配）。进程内互斥+全局时间戳 */
    suspend fun awaitSlot(channel: ChannelKind = ChannelKind.VIDEO)
}

// ============ 渲染队列 ============
interface RenderQueue {
    val state: StateFlow<QueueSnapshot>        // 总进度/ETA/各镜状态（通知栏与UI共用）
    suspend fun enqueueEpisode(episodeId: String)          // 入队前过 StoryboardGate+BudgeGuard+Key有效
    suspend fun pause()                                    // 弱网/预算超限/401 自动触发
    suspend fun resume(confirmedByUser: Boolean = false)   // 预算超限需 confirm
    fun cancelShot(shotId: String)
}

// ============ 断点续传 Checkpoint（继承 pavo load-or-merge 语义）============
interface CheckpointStore {
    /** load-or-merge：复用既有 checkpoint，保留 submitted/failed/blocked 权威态，
     *  仅补缺失镜；“completed 但文件缺失/0字节”重置为 pending */
    suspend fun loadOrMerge(episodeId: String, shots: List<ShotMeta>): EpisodeCheckpoint
    /** submit 成功后立即同步落盘 video_id（防重复付费的生死线） */
    suspend fun markSubmitted(shotId: String, providerTaskId: String)
    suspend fun markCompleted(shotId: String, localFileUri: String)  // 校验 size>0
    suspend fun markFailed(shotId: String, reason: String)
    /** 恢复判定：submitted 态优先 re-poll 已知 video_id，绝不重新 submit */
    suspend fun pendingRepoll(episodeId: String): List<CheckpointEntry>
}

enum class ShotState { PENDING, SUBMITTED, COMPLETED, FAILED, BLOCKED }

// ============ 预算闸门 ============
interface BudgetGuard {
    /** MVP 条数型（决议Q2）：返回 false 表示将超上限，队列暂停等待用户确认 */
    fun canSubmit(projectId: String): Boolean
    fun consumeSubmitted(projectId: String)
    val usage: StateFlow<BudgetUsage>          // 已用/上限/牌价金额估算
}

// ============ 编排器 ============
interface PipelineOrchestrator {
    val stage: StateFlow<PipelineStage>        // S1..S7 + 各Gate通过位图
    /** 判定当前阶段 Gate 是否放行（评审全过/校验通过/Key有效/预算未超） */
    suspend fun evaluateGates(projectId: String): GateReport
    suspend fun advanceTo(stage: PipelineStage)
    /** 进程重启后的恢复总入口：读checkpoint → 先repoll已提交镜 → 续跑队列 */
    suspend fun recoverOnBoot()
}

// ============ Key 安全存储 ============
interface KeyVault {
    suspend fun save(configId: String, providerId: String, plainKey: String)
    suspend fun load(configId: String): String            // 仅Provider层可见，永不回显UI
    fun masked(configId: String): String                  // sk-***abc 展示
    suspend fun delete(configId: String)
}
```

---

## 4. Agnes 适配器实现要点（继承 pavo-drama v0.9.4+ 实战）

1. **120s 提交限速门**：`AgnesVideoProvider.submitVideo()` 第一行 `rateGate.awaitSlot(VIDEO)`。实现为 Mutex 保护的单例时间戳（对应 pavo `wait_video_submit_slot`），间隔默认 120s、可由设置页覆盖（非法值兜底 120）。先等门再干活，杜绝并发烧配额。
2. **429 长退避，禁快重试**：429 从通用可重试集合中剔除，立即抛 `QuotaError`；视频提交外层专用退避循环 base=30s、cap=180s、最多 3 次。GET 轮询遇 429 直接上抛暂停队列。5xx 走指数退避（2s×2^n，≤3 次）。
3. **submitted 态记 video_id 防重复付费**：HTTP 2xx 一返回（拿到 video_id）就同步 `checkpointStore.markSubmitted()`（Room 写入 + 事务），之后才允许任何后续动作；恢复时 `pendingRepoll()` 的镜一律先 re-poll 已知 video_id 至终态，绝不重新 submit。这是 US5「零重复付费」的机制保障。
4. **参数前置校验**（省 4xx 远程成本）：num_frames 归一到 8n+1 且 ≤441；宽高取 64 的倍数；frame_rate ∈ [1,60]。
5. **keyframes 模式约定**：双帧必须同时传 `image=[first,last]` + `mode="keyframes"`（缺 mode 会 400）；尾帧缺失时由分镜层合成默认尾帧提示，不复用首帧。
6. **中文配音强制注入（决议 Q9）**：prompt 以中文台词/旁白主导开头 + 显式追加「全程使用中文普通话配音」指令；`generate_audio=true` 且永不做静音+重配（丢环境音是硬伤）。
7. **推理模型调用约定**：文本通道 `enable_thinking=false`、大 max_tokens（剧本 32768），避免 agnes-2.5-flash reasoning 吃空 content 的静默空响应；JSON 输出 3 次指数退避解析重试。
8. **401 全局语义**：抛 `AuthError` → 队列自动 pause + 全局横幅引导回设置页，不烧重试。
9. **日志脱敏**：Key 掩码（前3后3）、prompt 截断记录。

---

## 5. SQLite 表设计（Room, WAL）

数据库 `drama_factory.db`：

```sql
-- 项目（对应一个 run_dir 镜像目录）
CREATE TABLE projects (
  project_id   TEXT PRIMARY KEY,            -- uuid
  name         TEXT NOT NULL,
  style_preset TEXT NOT NULL DEFAULT 'cinema',   -- era红线块引用
  episode_plan INTEGER NOT NULL DEFAULT 1,
  budget_shots INTEGER NOT NULL DEFAULT 50, -- 条数型预算(Q2)
  created_at   INTEGER NOT NULL
);

-- 资产（角色6pose/场景/道具，一张pose一行）
CREATE TABLE assets (
  asset_id     TEXT PRIMARY KEY,
  project_id   TEXT NOT NULL REFERENCES projects ON DELETE CASCADE,
  kind         TEXT NOT NULL,               -- character/scene/prop
  parent_id    TEXT,                        -- pose行指向角色主卡
  pose_role    TEXT,                        -- front_anchor/side_45/...
  prompt       TEXT NOT NULL,
  file_uri     TEXT,
  remote_url   TEXT,
  g1_state     TEXT NOT NULL DEFAULT 'none',-- none/pass/rejected
  g2_score     REAL, g2_defects TEXT,       -- defects 非空直接拒(pavo实战)
  review_state TEXT NOT NULL DEFAULT 'none',-- none/keep/regen（人工评审F04）
  reject_reason TEXT, seed INTEGER,
  updated_at   INTEGER NOT NULL
);
CREATE INDEX idx_assets_proj ON assets(project_id, kind);

-- 分镜（每集每镜）
CREATE TABLE shots (
  shot_id      TEXT PRIMARY KEY,
  episode_id   TEXT NOT NULL,
  project_id   TEXT NOT NULL,
  shot_no      INTEGER NOT NULL,
  dialogue TEXT, narration TEXT, action TEXT,
  beat_ref TEXT, carry_over TEXT,
  first_asset_ids TEXT NOT NULL DEFAULT '[]',  -- JSON数组(绑定资产/道具母图)
  last_asset_ids  TEXT NOT NULL DEFAULT '[]',
  sb_check     TEXT NOT NULL DEFAULT 'pending', -- 六铁律: pass/error(JSON详情)
  UNIQUE(episode_id, shot_no)
);

-- 渲染任务 checkpoint（断点续传核心表）
CREATE TABLE render_tasks (
  shot_id        TEXT PRIMARY KEY REFERENCES shots(shot_id),
  episode_id     TEXT NOT NULL,
  state          TEXT NOT NULL DEFAULT 'PENDING', -- PENDING/SUBMITTED/COMPLETED/FAILED/BLOCKED
  provider_task_id TEXT,                     -- ★video_id，SUBMITTED 即刻写入（防重复付费生死线）
  attempt        INTEGER NOT NULL DEFAULT 0,
  blocked_reason TEXT, fail_reason TEXT,
  local_file_uri TEXT, file_size INTEGER NOT NULL DEFAULT 0,  -- size>0才算completed
  submitted_at   INTEGER, completed_at INTEGER
);
CREATE INDEX idx_rt_ep ON render_tasks(episode_id, state);

-- 供应商配置（Key 密文存这里，明文只在 Keystore 解密瞬间存在）
CREATE TABLE provider_configs (
  config_id    TEXT PRIMARY KEY,
  channel      TEXT NOT NULL,               -- video/text/image（三通道独立,Q6）
  provider_id  TEXT NOT NULL,               -- agnes / openai_compat / ...
  model        TEXT NOT NULL,
  key_cipher   BLOB NOT NULL,               -- EncryptedSharedPreferences主入口见§6
  key_masked   TEXT NOT NULL,               -- sk-***abc，UI展示用
  extra_params TEXT NOT NULL DEFAULT '{}',  -- generate_audio开关/分辨率/时长/限速间隔
  is_verified  INTEGER NOT NULL DEFAULT 0,
  updated_at   INTEGER NOT NULL
);

-- 集级元数据（阶段推进/评审放行标记）
CREATE TABLE episodes (
  episode_id   TEXT PRIMARY KEY,
  project_id   TEXT NOT NULL,
  ep_no        INTEGER NOT NULL,
  script_json  TEXT, storyboard_report TEXT,  -- 六铁律报告原文
  review_passed INTEGER NOT NULL DEFAULT 0,    -- 资产评审全过才置1(F04硬门槛)
  stage_flags  TEXT NOT NULL DEFAULT '{}',    -- 各Gate通过位图JSON
  UNIQUE(project_id, ep_no)
);
```

文件存储（应用专属目录，卸载即清）：`files/projects/<project_id>/` 镜像 run_dir —— `novel.txt`、`spec.json`、`assets/`、`episodes/<n>/clips/*.mp4`、`output/final.mp4`。提供清理入口（PRD 存储 500MB 级占用）。

---

## 6. API Key 安全存储方案

```
明文Key ──输入──► EncryptedSharedPreferences (MasterKey AES256-GCM,
                   密钥托管于 Android Keystore, StrongBox可用则用)
                        │
                        ├─ provider_configs.key_cipher 存AES密文（Room可备份不含明文）
                        └─ 进程内 KeyVault 单例缓存（内存中解密一次）
AgnesProvider 发请求时经 KeyVault.load() 取明文 → 仅进 Authorization header
```

要点：
- **Keystore 不可导出**：MasterKey 由系统硬件背书生成，App 数据目录被拷走也无法解密（Android 10+ 未 root 前提）。
- **UI 永不回显明文**：设置页只见 `key_masked`；「测试连通」在内存中完成。
- **日志脱敏**：Ktor 日志插件对 Authorization header 与 body 中 key 字段打码（pavo `_mask_key` 语义移植）。
- **失效处理**：401 时不清除 Key（可能临时风控），横幅提示去设置页人工验证。
- 备份排除：`android:allowBackup` 关闭或 backup rules 排除 prefs/DB，防止云备份泄露密文+跨设备恢复风险。

---

## 7. 渲染 Foreground Service 与进程被杀恢复的状态机

### 7.1 服务模型

- 点「渲染第X集」→ 启动 `RenderForegroundService`（`foregroundServiceType=dataSync`），常驻通知显示「第X集 · 12/24镜 · ETA约35分钟」（≥5分钟刷新一次，PRD 6.1）。
- Service 持有 RenderQueue 单消费者协程作用域；Activity 销毁不影响；进程被杀 → 下次启动 `PipelineOrchestrator.recoverOnBoot()` 续传。
- Doze/电池优化：FGS 豁免持续轮询；轮询间隔自适应（submitted 初期 30s，10 分钟后降 60s）；「仅Wi-Fi渲染」开关监听网络回调，断网 → 队列标「等待网络」暂停而非失败，恢复自动续跑。
- 国产 ROM 兜底（Q7）：设置页提供 MIUI/HarmonyOS 等自启动+后台白名单指引页。

### 7.2 单镜状态机

```
                 ┌────────── 进程重启 recoverOnBoot ──────────┐
                 ▼                                            │
 PENDING ──submit(过限速门+BudgetGuard)──► SUBMITTED(记video_id)│
    │                                          │ poll 30s→60s  │
    │Gate拦截(六铁律/预算/评审)                  ├─progress──────┤(继续poll)
    ▼                                          │                │
 BLOCKED(权威态,不翻转)                         ├─completed→下载→COMPLETED(size>0校验)
                                               ├─failed──► FAILED(可手动重试)
                                               └─401/429耗尽/断网──► PAUSED_PROJECT级
```

### 7.3 恢复语义（继承 pavo load-or-merge）

1. 启动扫描 render_tasks：`SUBMITTED` 有 video_id 的镜**全部先进 re-poll 队列头**，不重新 submit（零重复付费，US5）。
2. `COMPLETED` 但本地文件缺失或 size=0 → 重置 PENDING 重做。
3. `FAILED/BLOCKED` 保持权威态，不因恢复翻转。
4. 项目级 PAUSED（预算/Key/网络）恢复条件各不同：预算需用户确认弹窗、Key 需连通测试、网络自动。

---

## 8. 数据流示例：从导入小说到导出成片

以《莽途》ch001、24 镜为例：

```
T0 [②导入] 用户粘贴20万字TXT + 勾版权确认框
    TextProvider.chat ×2轮 → 角色/场景/道具spec草案 → 用户微调确认入库(spec.json)

T1 [③资产] AssetPlanner展开: 角色×N个6pose包(1024²) + 场景卡 + 道具母图
    ImageProvider逐张生成 → G1硬校验(格式/尺寸/正方形) → G2多模态评分(defects非空直接拒)
    rejected自动重试并记reject_reason → 全部产出 catalog

T2 [④评审] 评审画廊逐卡 keep/regen；用户regen两张杂斑卡(仅重生该卡,旧+新按id合并)
    全部keep → episodes.review_passed=1（花钱前人工闸门放行）

T3 [⑤分镜] TextProvider.writer吃原著铁律逐集出剧本 → storyboard编译
    StoryboardGate六铁律机器校验: 第7镜台词diff不过→error阻断并给修复建议
    用户修完重新校验pass → 放行

T4 [⑥渲染] 用户点渲染第1集(BudgetGuard:50条内✓, Key已validate✓)
    启动Foreground Service → RenderQueue单消费者:
      镜1: 组装prompt(中文台词主导+全程中文普通话指令) + keyframes首尾帧
           → rateGate.awaitSlot(首发免等) → POST /videos → video_id到手
           → markSubmitted落库(★) → poll 30s… → completed → 下载clip → COMPLETED
      镜2..24: 每次提交先等≥120s门; 期间并行poll在途任务; 通知栏12/24镜·ETA35min
      夜间锁屏: FGS续跑整夜; 若第13镜submit后进程被杀 → video_id已在库

T5 [恢复] 清晨充电开机 → recoverOnBoot(): 镜1-12 COMPLETED跳过,
    镜13 SUBMITTED→re-poll原video_id至完成, 镜14起正常排队 —— 零重复付费

T6 [⑦成片] 24镜全COMPLETED → Assembler: 编码一致→concat -c copy秒级;
    否则h264_mediacodec归一化; 超10分钟→分段导出降级
    → 预览器播放 → 导出MP4到相册/分享; 提示清理缓存入口
```

---

## 9. 开发期验收锚点（承接决议冒烟）

| 项 | 验收标准 |
|---|---|
| 真机直连 Agnes 全链路（S1延伸） | 移动网络下 submit+poll 出片 1 镜 |
| FFmpegKit 真机基准（S2延伸） | 24 镜拼接 ≤10 分钟否则启用分段降级 |
| 断点续传 | 杀进程重启后重复提交数 = 0 |
| 整夜渲染 | 锁屏 ≥50 分钟完成率 ≥90% |

（本文档只描述架构，不含源码改动；实施拆解交由任务规划环节。）
