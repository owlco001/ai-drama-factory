# T014 接口级架构文档（AI 全托管 + 成片合成）

> 版本：v1.0 ｜ 作者：架构 Agent ｜ 对应 PRD / 拍板决议
> - PRD：`docs/T014-prd.md`
> - 拍板：`docs/T014-decisions.md`（Q1 a、Q2 b、Q3 a、Q4 b、Q5 a、Q6 a、Q7 a）
>
> 本文只定义**接口与数据契约**，不落地代码。开发 Agent 按本文签名直接实现；
> 任何字段名/类型/返回值若与本文冲突，以本文为准并回改本文档。

---

## 一、整体方案图（ASCII）

```
┌────────────────────────────────────────────────────────────────────┐
│                         DramaApp.kt 首页                            │
│                                                                    │
│  ┌──────────────────┐   记忆(全局, EncryptedPrefs)                  │
│  │ 🤖 AI 全托管入口  │◄──────────── 上次模式=AI    │
│  └──────┬───────────┘                                   │
│         │ 粘文本(≥100字)  ┌──────────────────┐            │
│         ├─────────────────►✍️ 人工模式入口  │            │
│         │ 直达现有流程    └───────▲───────────┘            │
│         │                          │ 上次模式=人工/无记忆  │
│         ▼                          ▼                       │
│  ┌────────────────────────────────────────────────────┐         │
│  │  AiOrchestrator   (core-engine 新增)                 │         │
│  │                                                      │         │
│  │  ① extractAssets  ──► 自动建项目「AI草稿-MMdd-HHmm」│         │
│  │  ② generateImages ──► 复用 ImageProvider / G1+G2     │         │
│  │  ③ audit          ──► 重试 2 次仍差 → 标红放行        │         │
│  │  ④ generateStoryboard ──► 复用 AiStoryboardDirector   │         │
│  │  ⑤ enqueueRender  ──► 复用 DefaultRenderQueue          │         │
│  │                                                      │         │
│  │  全程 StateFlow<List<ProgressEvent>> 日志流回传       │         │
│  └──────┬───────────────────────────────────────────────┘         │
│         │                                                         │
│         │ 文本模型路由（Q4：App 内所有注册推理模型均可）           │
│         ▼                                                         │
│  ┌──────────────────────────────────────────────────────┐         │
│  │  TextModelRouter  ──► ProviderRegistry                │         │
│  │   deepseek:  OpenAI兼容  base=/v1  model=deepseek-chat│         │
│  │   agnes:     复用 AgnesProvider 自动选模              │         │
│  └──────┬───────────────────────────────────────────────┘         │
│         │                                                         │
│         │ ⑤完成后渲染队列产出每镜 mp4                                │
│         ▼                                                         │
│  ┌──────────────────────────────────────────────────────┐         │
│  │  LibraryPage 成片库                                    │         │
│  │   每集卡片：全部 COMPLETED? → 「合成本集成片」按钮     │         │
│  │                                                        │         │
│  │   MovieAssembler  (端上 ffmpeg-kit-full 4.5.x)        │         │
│  │   ┌────────────────────────────────────────────┐      │         │
│  │   │ 三级策略(映射 FfmpegAssembler):             │      │         │
│  │   │  1) concat -c copy  (同规格, 秒级)           │      │         │
│  │   │  2) scale+pad 448x832 + h264_mediacodec     │      │         │
│  │   │  3) 分段导出(每8镜一段)  预留云端组装        │      │         │
│  │   └────────────────────────────────────────────┘      │         │
│  │   产物 → files/movies/{epId}.mp4   + 写入成片库表     │         │
│  │   播放器 ExoPlayer 预览  +  ACTION_SEND 分享          │         │
│  └──────────────────────────────────────────────────────┘         │
│         │                                                         │
│         ▼                                                         │
│  ┌──────────────────────────────────────────────────────┐         │
│  │  Room: 成片库表 finished_films (v5)                    │         │
│  │  字段见 §四 schema                                      │         │
│  └──────────────────────────────────────────────────────┘         │
└────────────────────────────────────────────────────────────────────┘
```

**关键约束（跨模块）：**
- AI 全托管与人工模式**共享 ProviderRegistry / BudgetGuard / CheckpointStore**，不新增重复引擎。
- 文本通道路由**不绑定 Agnes**：Q4 决议允许 DeepSeek / 任意 OpenAI 兼容模型作为"大脑"，但图像/视频仍走现有通道。
- 合成**仅手动触发**（Q5）；P2 再加"整集完成自动合成"设置开关。
- 产物落 `Context.getFilesDir()/movies/`，免权限、卸载即清（Q6）；分享走 FileProvider。
- 模式记忆**全局一个开关**（Q7），落 `EncryptedSharedPreferences`，可设置页重置。

---

## 二、新增模块接口签名（Kotlin 风格，精确到参数/返回值）

> 所有接口统一在 `core-engine`（业务纯 Kotlin，JVM 可单测）；UI/数据层在 `:app`。
> 返回值类型、异常类型、Room 表字段以本文为准。

### 2.1 `ProgressEvent` —— 进度日志流元素

```kotlin
/** 流式进度事件（P0-2 验收第 2 条） */
data class ProgressEvent(
    /** 五阶段枚举，对应 AI 全托管流水线顺序 */
    val stage: PipelineStage5,
    /** 阶段内子步骤序号（如 extractAssets 阶段可拆"分镜文本→角色→场景→道具"） */
    val subStep: Int,
    /** 用户可读中文消息，日志流直接展示 */
    val message: String,
    /** 毫秒时间戳（相对流水线起点） */
    val elapsedMs: Long,
    /** 阶段耗时毫秒（阶段首次到当前事件） */
    val stageElapsedMs: Long,
    /** 事件等级：INFO / WARN / ERROR */
    val level: Level = Level.INFO,
    /** 失败原因（仅 ERROR 时非空） */
    val error: String? = null,
) {
    enum class Level { INFO, WARN, ERROR }

    /** 该事件是否表示整条流水线结束（含失败） */
    val isTerminal: Boolean
        get() = stage == PipelineStage5.ENQUEUE_RENDER && level == Level.ERROR ||
                 stage == PipelineStage5.ENQUEUE_RENDER_DONE
}

/** 五阶段枚举：与 PRD P0-2 验收第 1 条顺序一致 */
enum class PipelineStage5(val label: String) {
    EXTRACT_ASSETS      ("①提取资产"),
    GENERATE_IMAGES     ("②生成图像"),
    AUDIT               ("③质量审计"),
    GENERATE_STORYBOARD ("④生成分镜"),
    ENQUEUE_RENDER      ("⑤入队渲染"),
    ENQUEUE_RENDER_DONE ("✓ 完成"),
}
```

**约束：**
- UI 用 `StateFlow<List<ProgressEvent>>` 展示，追加式（不覆盖历史）；单条 >500 条按 PRD P2-1 裁剪早期（实现时默认上限 500，保留末 200）。
- `elapsedMs` / `stageElapsedMs` 由 orchestrator 注入，事件构造方不填。

### 2.2 `AiOrchestrator` —— AI 全托管编排器

```kotlin
/** AI 全托管五阶段编排器（core-engine 新增，JVM 可测） */
interface AiOrchestrator {

    /** 五阶段流水线实时进度 */
    val events: StateFlow<List<ProgressEvent>>

    /** 当前编排运行中的 episodeId（自动建项目后填入）；未启动为 null */
    val currentEpisodeId: StateFlow<String?>

    /**
     * 一键成片主入口。
     * @param scriptText 用户粘贴的剧本文本，长度 ≥100 字符
     * @param textModelId 文本通道模型 id（如 "deepseek-chat" / "agnes-2.5-flash"）；
     *                    空串则使用 AppGraph 当前 text 通道默认
     * @param onAutoCreatedProject 自动建项目后回调（UI 用其切到剧集/分镜/渲染页）
     * @return 结束时 `currentEpisodeId` 非空（即使失败，已建项目仍可人工恢复）
     *
     * 异常：
     *   - 文本 <100 字符 → 抛 AiOrchestrator.InputTooShort("请粘贴≥100字剧本")
     *   - textModelId 指向未验证/Key 空的模型 → 抛 AiOrchestrator.ModelBlocked(…)
     */
    suspend fun run(
        scriptText: String,
        textModelId: String = "",
        onAutoCreatedProject: (projectId: String, episodeId: String) -> Unit = { _, _ -> },
    )

    /**
     * 重试某一阶段（P0-2 验收第 3 条）：已完成阶段结果**不重复调用**，
     * 仅从 `fromStage` 起重新跑。已落库的资产/分镜/入队记录保留。
     */
    suspend fun retryFrom(fromStage: PipelineStage5)

    /** 五阶段进度（checkpoint）：用于断点续跑。已落 Room，读回即可恢复 */
    suspend fun recoveryState(episodeId: String): AiRecoveryState

    sealed class AiError(val msg: String) : Exception(msg) {
        class InputTooShort(msg: String) : AiError(msg)
        class ModelBlocked(
            override val msg: String,
            val modelId: String,
        ) : AiError(msg)
        class StageFailed(
            override val msg: String,
            val stage: PipelineStage5,
            val cause: String,
        ) : AiError(msg)
    }

    /** 恢复状态：哪一阶段已成功、哪一阶段待重试 */
    data class AiRecoveryState(
        val projectId: String,
        val episodeId: String,
        val lastSuccessStage: PipelineStage5,  // 最后一阶段成功
        val failedStage: PipelineStage5?,       // 失败阶段（可为空表示全部成功或从未启动）
        val assetCount: Int,
        val shotCount: Int,
        val renderEnqueued: Boolean,
    )
}
```

**内部契约（开发 Agent 必须满足，供单测断言）：**
1. `run` 内部严格按 `PipelineStage5` 顺序执行，**任一阶段抛异常 → 立即追加 ERROR 事件 → 停止 → 已建项目/集/资产/分镜保留**。
2. 自动建项目命名规则：`"AI草稿-" + SimpleDateFormat("MMdd-HHmm")`（本地时区），`stage_flags` 写入 `{"ai_managed":true}` 以便 UI 识别。
3. `extractAssets` 复用 `LlmAssetExtractor.extract`；`generateImages` 复用 `ImageProvider.generateImage` + G1/G2 审计；`audit` 复用 `QualityEngine`，**重试上限=2**，仍差则标红事件 + 继续；`generateStoryboard` 复用 `AiStoryboardDirector`；`enqueueRender` 复用 `RenderQueue.enqueueEpisode`。
4. **文本通道路由**：`textModelId` 非空时，用 `TextModelRouter.resolve(textModelId)` 拿到注入密钥的 `TextProvider`；空时走 `AppGraph.text` 默认。

### 2.3 `TextModelRouter` —— 文本模型选择策略（Q4：多模型并存）

```kotlin
/**
 * 文本通道模型路由：App 内所有注册推理模型(OpenAI 兼容)均可作为"大脑"。
 * AgnesProvider.pickTextModel 自动选模仅当 textModelId 为空或未指定模型时生效。
 */
interface TextModelRouter {

    /** 已注册且可用作"文本大脑"的模型列表（设置页单选数据来源） */
    fun registeredTextModels(): List<TextModelEntry>

    /** 当前生效的文本模型 id */
    fun activeTextModelId(): String

    /** 切换当前生效模型（Key 各自保存，随时互切，Q4） */
    suspend fun setActiveTextModel(modelId: String): Result<Unit>

    /** 测试连通（对应设置页「测试连通」按钮） */
    suspend fun validate(modelId: String): Result<ConnectionInfo>

    /**
     * 解析为一次可注入密钥的 TextProvider：
     * - "agnes" 前缀 → 走 AgnesProvider（内部再 pickTextModel 自动选模）
     * - 其他 → 走 OpenAI 兼容适配器（base_url 从 provider_configs 读）
     */
    suspend fun resolve(modelId: String): TextProvider

    data class TextModelEntry(
        val modelId: String,          // "deepseek-chat" / "agnes-2.5-flash" / ...
        val label: String,            // UI 展示：DeepSeek / Agnes 文本 2.5 Flash
        val providerId: String,       // "deepseek" / "agnes" / "openai_compat"
        val baseUrl: String,          // 如 "https://api.deepseek.com/v1"
        val keyMasked: String?,       // 已存 Key 掩码；null=未配置
        val isVerified: Boolean,      // 是否通过 validate
    )
}
```

**与现有 `SettingsPage` / `SettingsLogic` 接线约定：**
- 设置页新增「文本模型」区块（`SettingsPage.kt` 新增 Card）：单选 `registeredTextModels()`，`setActiveTextModel` 走 KeyVault 各自加密保存。
- DeepSeek 默认坐标（写入 `provider_configs.extra_params`）：
  - `provider_id = "deepseek"`
  - `base_url = "https://api.deepseek.com/v1"`
  - `model = "deepseek-chat"`
  - `enable_thinking = false`（对齐 Agnes 约定，避免 reasoning 吞 content）
- **Key 为空或连通失败**时，`AiOrchestrator.run` 抛 `ModelBlocked`，UI 阻断并提示而非静默失败（P1-1 验收第 4 条）。

### 2.4 `MovieAssembler` —— 端上成片合成器（ffmpeg-kit-full 4.5.x）

```kotlin
/**
 * 端上成片合成器：把整集已完成的单镜 mp4 合成单一 mp4。
 * 三级策略直接映射 FfmpegAssembler 的 concat-copy / 归一化 / 分段导出；
 * 执行器由 JVM 命令行 ffmpeg 换成 FFmpegKit (arthenica)。
 */
interface MovieAssembler {

    /** 合成进度回调（UI 可见） */
    val progress: StateFlow<MovieAssembleProgress>

    /**
     * 合成整集。
     * @param clips 已按 shot_no 升序的本地单镜 mp4 文件列表（必须全部存在且 >0 字节）
     * @param output 目标 mp4 文件，位于 Context.getFilesDir()/movies/
     * @param grade 统一色彩分级配方
     * @return AssembleResult（与 FfmpegAssembler 同构，但 output 指向最终成片或分段）
     *
     * 三级策略自动选级（对齐 FfmpegAssembler.assemble）：
     *   1. concat -c copy（同规格，秒级）
     *   2. scale+pad 到 448x832 + h264_mediacodec（端侧硬编）
     *   3. 分段导出（每 8 镜一段），返回 Segmented，落 multi_parts_uris
     */
    suspend fun assemble(
        clips: List<File>,
        output: File,
        grade: ColorGradePreset = ColorGradePreset.CINEMA,
    ): AssembleResult

    sealed class AssembleResult {
        data class Success(
            val output: File,
            val strategy: Strategy,
            val elapsedMs: Long,
            val durationSeconds: Double,   // ffprobe 读取成片时长
        ) : AssembleResult()
        data class Segmented(
            val parts: List<File>,
            val elapsedMs: Long,
        ) : AssembleResult()
        data class Failure(val strategy: Strategy, val message: String) : AssembleResult()
    }

    enum class Strategy(val label: String) {
        CONCAT_COPY("concat-copy"),
        NORMALIZE("mediacodec归一化"),
        SEGMENTED("分段导出"),
    }

    enum class ColorGradePreset(val filter: String) {
        CINEMA("eq=contrast=1.08:brightness=-0.02:saturation=1.06,colortemperature=warm=0.06,format=yuv420p"),
        COOL("eq=contrast=1.06:saturation=1.04,colortemperature=warm=-0.08,format=yuv420p"),
        WARM("eq=contrast=1.06:saturation=1.08,colortemperature=warm=0.12,format=yuv420p"),
        NEUTRAL("format=yuv420p"),
    }

    data class MovieAssembleProgress(
        val stage: AssembleStage,
        val step: Int,
        val total: Int,
        val message: String,
        val elapsedMs: Long,
    )
    enum class AssembleStage { GRADE, CONCAT, NORMALIZE, PROBE, DONE }
}
```

**与 `FfmpegAssembler` 关系：**
- 保留 `FfmpegAssembler`（JVM 测试桩 + 未来服务端预留），**端侧**新建 `MovieAssembler` 实现，二者共享 `AssembleResult` / `Strategy` / `ColorGradePreset` 契约。
- 实现层用 `com.arthenica:ffmpeg-kit-full:4.5.LTS` 的 `FFmpeg.executeFFmpeg(...)` + `FFmpegSession`（异步回调进度）；`FFprobe` 用于取成片时长。
- 单测：JVM 用命令行 ffmpeg 桩跑通三级策略回退（已有 `QueueAssemblerTest` 风格可复用）。

### 2.5 `FinishedFilmEntity` / `MovieLibraryDao` —— 成片库 Room 表

**Entity（新增 `finished_films` 表）：**

```kotlin
@Entity(tableName = "finished_films")
data class FinishedFilmEntity(
    @PrimaryKey val film_id: String,          // 规则："{episodeId}"
    val episode_id: String,
    val project_id: String,
    val file_uri: String,                     // files/movies/{episodeId}.mp4 绝对路径
    val file_size: Long,                      // 字节，>0
    val duration_seconds: Double,             // ffprobe 读取的成片时长
    val strategy: String,                     // CONCAT_COPY / NORMALIZE / SEGMENTED
    val parts_uris_json: String = "[]",       // 分段导出时多段文件URI(JSON 数组)
    val color_grade: String = "CINEMA",
    val assembled_at: Long,                   // 合成完成时间戳
    val updated_at: Long,
)
```

**DAO 新增方法（挂到现有 `DramaDao`）：**

```kotlin
// ---- 成片库（T014 新增，Room 表 v5）----

@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun upsertFinishedFilm(f: FinishedFilmEntity)

@Query("SELECT * FROM finished_films WHERE episode_id=:episodeId")
suspend fun filmOf(episodeId: String): FinishedFilmEntity?

@Query("SELECT * FROM finished_films WHERE project_id=:projectId ORDER BY assembled_at DESC")
suspend fun filmsOfProject(projectId: String): List<FinishedFilmEntity>

@Query("SELECT episode_id FROM finished_films WHERE project_id=:projectId")
suspend fun assembledEpisodeIds(projectId: String): List<String>

@Query("DELETE FROM finished_films WHERE film_id=:filmId")
suspend fun deleteFilm(filmId: String)
```

**`LibraryPage` 接线约定：**
- 每集卡片新增「合成本集成片」按钮：**仅当** `render_tasks` 该集所有镜 `state='COMPLETED'` 时启用（UI 判定，不依赖新表）。
- 点击后调用 `MovieAssembler.assemble` → 成功则 `upsertFinishedFilm` + 卡片变「可播放 ✓」+ 启用 ExoPlayer 预览与 `ACTION_SEND` 分享；失败则显示错误、按钮恢复可重试。
- 删除（P2-2）：二次确认弹窗 → `deleteFilm` + 删除 `files/movies/` 下对应文件。

---

## 三、端到端数据流

```
用户粘贴文本(≥100字)
        │
        ▼
┌─────────────────────────────┐
│ DramaApp 首页「AI 全托管」入口 │
│  · 粘文本框                  │
│  · 「一键成片」按钮           │
└──────────────┬──────────────┘
               │ AiOrchestrator.run(scriptText, textModelId)
               ▼
┌────────────────────────────────────────────────────┐
│ ① extractAssets                                     │
│   · 自动建项目「AI草稿-MMdd-HHmm」→ upsertProject    │
│   · 写第 1 集(episodeId=p_ep1) → upsertEpisode       │
│   · script_json 存原文；stage_flags={"ai_managed":true}│
│   · LlmAssetExtractor → 逐卡 upsertAsset             │
│   ─ ProgressEvent(stage=EXTRACT_ASSETS, …)          │
└──────────────────────┬─────────────────────────────┘
                       ▼
┌────────────────────────────────────────────────────┐
│ ② generateImages                                    │
│   · 复用 ImageProvider.generateImage + 时代红线      │
│   · 每张卡：setAssetRemoteUrl(落库)                  │
│   · ProgressEvent 逐卡追加（subStep 递增）           │
└──────────────────────┬─────────────────────────────┘
                       ▼
┌────────────────────────────────────────────────────┐
│ ③ audit                                             │
│   · 复用 QualityEngine(G1+G2)                       │
│   · 重试 ≤2 次（Q3）；仍差 → ERROR 事件(标红)+ 放行 │
└──────────────────────┬─────────────────────────────┘
                       ▼
┌────────────────────────────────────────────────────┐
│ ④ generateStoryboard                                │
│   · 复用 AiStoryboardDirector                       │
│   · upsertShot 批量落库                              │
└──────────────────────┬─────────────────────────────┘
                       ▼
┌────────────────────────────────────────────────────┐
│ ⑤ enqueueRender                                     │
│   · 复用 RenderQueue.enqueueEpisode + BudgetGuard   │
│   · 预算熔断/429/401 按现有语义                     │
│   · ProgressEvent(stage=ENQUEUE_RENDER_DONE)        │
└──────────────────────┬─────────────────────────────┘
                       ▼
                UI 跳转渲染页
                       │
          (渲染完成后, 用户回到成片库)
                       ▼
┌────────────────────────────────────────────────────┐
│ LibraryPage：每集卡片                                │
│   if 全部 COMPLETED → 「合成本集成片」按钮可用        │
│   click → MovieAssembler.assemble(clips, output)    │
│     → 三级策略：concat-copy / 归一化 / 分段         │
│     → 产物 files/movies/{epId}.mp4                   │
│     → upsertFinishedFilm(film_id=epId, …)          │
│     → 卡片变「可播放 ✓」+ ExoPlayer 预览             │
│     → ACTION_SEND + FileProvider 分享              │
└────────────────────────────────────────────────────┘
```

**断点续跑契约：**
- 每阶段结束时写 `AiRecoveryState` checkpoint（复用 `render_tasks` + 新表，或扩展 `episodes.stage_flags` JSON 记录 lastSuccessStage）。
- 杀进程后 `retryFrom(lastSuccessStage)` 从最近成功阶段续，已完成结果**不重复调用**（P0-2 验收第 5 条单测覆盖）。

---

## 四、数据库变更

### 4.1 新增表 schema（SQLite）

```sql
-- finished_films：成片库（T014 新增，app 私有目录 files/movies/ 的元数据）
CREATE TABLE IF NOT EXISTS finished_films (
    film_id         TEXT PRIMARY KEY,
    episode_id      TEXT NOT NULL,
    project_id      TEXT NOT NULL,
    file_uri        TEXT NOT NULL,
    file_size       INTEGER NOT NULL DEFAULT 0,
    duration_seconds REAL NOT NULL DEFAULT 0.0,
    strategy        TEXT NOT NULL DEFAULT 'CONCAT_COPY',
    parts_uris_json TEXT NOT NULL DEFAULT '[]',
    color_grade     TEXT NOT NULL DEFAULT 'CINEMA',
    assembled_at    INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);

-- 索引：按项目/按集号查找
CREATE INDEX IF NOT EXISTS idx_ff_episode ON finished_films(episode_id);
CREATE INDEX IF NOT EXISTS idx_ff_project ON finished_films(project_id);
```

### 4.2 Room 迁移 SQL（v4 → v5）

```kotlin
// RoomCheckpointStore.kt @Database(version = 5) + 新增 MIGRATION_4_5
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 新增成片库表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS finished_films (
                film_id TEXT PRIMARY KEY,
                episode_id TEXT NOT NULL,
                project_id TEXT NOT NULL,
                file_uri TEXT NOT NULL,
                file_size INTEGER NOT NULL DEFAULT 0,
                duration_seconds REAL NOT NULL DEFAULT 0.0,
                strategy TEXT NOT NULL DEFAULT 'CONCAT_COPY',
                parts_uris_json TEXT NOT NULL DEFAULT '[]',
                color_grade TEXT NOT NULL DEFAULT 'CINEMA',
                assembled_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ff_episode ON finished_films(episode_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ff_project ON finished_films(project_id)")

        // 兼容字段：provider_configs 文本通道独立化（Q4：文本/图像/视频 Key 各自保存）
        // 现有 v4 表已有 channel=text 行；新增 default_text_model 标记（可选，非必填，
        // 用现有 extra_params JSON 承载即可，此处不建新列，避免二次迁移）
    }
}
```

**注意：**
- `@Database(entities = [..., FinishedFilmEntity::class], version = 5, ...)` 追加新实体。
- `provider_configs` 表**不改结构**：Q4「各自保存 Key」通过 `channel='text'` 行 + `provider_id` 区分（`deepseek` / `agnes` / `openai_compat`），`base_url` 走 `extra_params` JSON。
- `episodes.stage_flags` 复用：AI 全托管写入 `{"ai_managed":true,"last_success_stage":"ENQUEUE_RENDER"}` 记录恢复位点，**不新增字段**。

---

## 五、依赖变更

### 5.1 `ffmpeg-kit-full`（Q1 a：端上离线合成）

`core-engine/build.gradle.kts` 或 `app/build.gradle.kts`（端侧接入应放 app）新增：

```kotlin
// 端上成片合成：ffmpeg-kit-full 4.5.x LTS
implementation("com.arthenica:ffmpeg-kit-full:4.5.LTS")

// 备选（jitpack 镜像，上游 maven 不稳定时切换）：
// implementation("com.github.arthenica:ffmpeg-kit-full:4.5.LTS")
// + 在 build.gradle.kts 的 repositories 追加：
// maven { url = uri("https://jitpack.io") }
```

**版本锁定说明：**
- `ffmpeg-kit` 上游仓库已归档，**锁 4.5.x LTS**，绝不使用动态 `+`。
- maven 仓库优先：`https://jitpack.io` 已归档历史版本也可用；若 maven 拉不到再切换 jitpack。
- 包体增大约 30MB（full 变体），可接受（PRD 决策）。

### 5.2 其他依赖

- **不新增** navigation / 进度流等新库（PRD 非功能要求）。
- ExoPlayer 若 App 已引入则复用；否则用 `androidx.media3:media3-exoplayer`（可选，仅播放预览需要）。
- FileProvider 复用现有（分享通路已有）。
- `kotlinx.coroutines` / `kotlinx.serialization` / Room 均已有，不再新增。

---

## 六、风险点与缓解

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | **ffmpeg-kit 上游归档**，maven 拉取失败 | P0-3 合成不可用 | 锁 4.5.LTS；备选 jitpack 镜像；更彻底备选：退回到方案 (b) 服务端合成（PRD Q1 已预留 `Assembler.cloud()` 接口） |
| R2 | **ffmpeg-kit-full 包体+30MB** | 安装量敏感场景 | 若需瘦身可改 `ffmpeg-kit-min-gpl`（丢 filter_complex 高级滤镜，降级归一化路径受限）；默认保留 full |
| R3 | **DeepSeek 免费额度限流 / 429** | AI 全托管联调阻塞 | 联调期用 mock `TextProvider` 跑通编排；生产走退避 + KeyVault 各自存 Key 支持多 Key 轮转 |
| R4 | **DeepSeek enable_thinking=true 吞 content** | 静默空响应 | 强制 `enable_thinking=false`（对齐 Agnes 约定）；validate 时做 content 非空断言 |
| R5 | **五阶段长时间执行被系统杀** | 中途丢失进度 | 每阶段末落 checkpoint（`episodes.stage_flags` + `render_tasks`）；`retryFrom` 从最近成功阶段续，已完成结果不重调 |
| R6 | **国产 ROM 杀进程导致合成被中断** | 合成失败 | 合成走前台服务/WorkManager；失败写 `AssembleResult.Failure` 保留按钮可重试；产物文件缺失则不 `upsertFinishedFilm` |
| R7 | **整集镜片段规格不一**（分辨率/帧率混杂） | concat-copy 失败 | 三级策略自动降级到 scale+pad 448x832 + h264_mediacodec；再失败走分段导出 |
| R8 | **AI 全托管 Key 空/未验证** | 静默失败扣费或空跑 | `AiOrchestrator.run` 前置校验 `TextModelRouter.validate`，Key 空抛 `ModelBlocked` 阻断并回设置页 |
| R9 | **进度流 StateFlow 内存暴涨**（>500 条） | OOM | 按 PRD P2-1 默认上限 500 条、保留末 200 条 |
| R10 | **`episodes.stage_flags` JSON 承载恢复位点** 与后续演进耦合 | 字段膨胀 | 仅存 `last_success_stage` + `ai_managed` 两个键，字段少可维护；未来升级独立表再迁移 |

---

## 附录 A：开发 Agent 实现任务拆分建议

1. **D1-D2**：`TextModelRouter` 接口 + `SettingsPage` 文本模型区块 + DeepSeek 默认坐标 + KeyVault 多 configId 接线。
2. **D3-D5**：`AiOrchestrator` + `ProgressEvent` + `PipelineStage5` + `retryFrom` + 断点续跑 + JVM 单测（mock TextProvider / ImageProvider 跑通五阶段与失败重试）。
3. **D5-D7**：`MovieAssembler` + `ffmpeg-kit-full:4.5.LTS` 接入 + `LibraryPage` 合成/播放/分享 + Room v5 迁移 + `FinishedFilmEntity` + DAO。
4. **D7**：首页「AI 全托管」入口（`DramaApp.kt` 新增 Page.AI_HOME 或复用 PROJECTS 前置卡片）+ 模式记忆（EncryptedSharedPreferences）+ P2 收尾（versionCode+1 / versionName 1.4.0）+ 回归（`:app:assembleDebug`、JVM 单测、git 提交推送）。
