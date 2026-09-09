package com.dramafactory.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import com.dramafactory.app.data.BrokenDramaDao
import com.dramafactory.app.data.BrokenMovieLibraryDao
import com.dramafactory.app.data.DramaDatabase
import com.dramafactory.app.data.MovieLibraryDao
import com.dramafactory.app.data.RoomCheckpointStore
import com.dramafactory.app.security.AndroidKeyVault
import com.dramafactory.core.assemble.MovieAssembler
import com.dramafactory.core.assemble.MovieAssemblerImpl
import com.dramafactory.core.assemble.androidFfmpegKitExecutor
import com.dramafactory.core.model.ChatRequest
import com.dramafactory.core.pipeline.DefaultBudgetGuard
import com.dramafactory.core.provider.AgnesProvider
import com.dramafactory.core.provider.AgnesRegion
import com.dramafactory.core.provider.CheckpointStore
import com.dramafactory.core.provider.KeyVault
import com.dramafactory.core.provider.VideoProviderRouter
import com.dramafactory.core.provider.agnesScopedConfigId
import com.dramafactory.core.orchestrate.DefaultAiOrchestrator
import com.dramafactory.core.orchestrate.PipelineStage5
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import android.net.Uri
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

object AppGraph {

    const val CONFIG_VIDEO = "agnes-video"
    const val CONFIG_TEXT = "agnes-text"
    const val CONFIG_IMAGE = "agnes-image"
    /** v1.9.24：Agnes 视频模型偏好（v2.0/2.5/2.5-flash），独立存 KeyVault，避免污染 provider_configs 表的 key 字段 */
    const val CONFIG_VIDEO_MODEL = "agnes-video-model-pref"
    const val AUTO_VIDEO_MODEL = "auto"

    lateinit var keyVault: KeyVault; private set
    lateinit var checkpointStore: CheckpointStore; private set
    lateinit var agnes: AgnesProvider; internal set
    /** v1.9.0：视频通道按激活供应商动态路由（Kling/即梦/Runway/Luma/Pika/agnes/custom） */
    val video get() = VideoProviderRouter.resolve()
    val text get() = agnes
    /** v1.9.1：图像通道按激活视频供应商路由（Agnes 原生 / 其他家退化为 image2video 首帧） */
    val image get() = com.dramafactory.core.provider.ImageProviderRouter.resolve()

    lateinit var budgetGuard: DefaultBudgetGuard; private set

    // v1.8.8：自定义模型 override（由 refreshConfiguredProviders 写入），rebuildAgnes() 复用，
    // 保证运行时切换 Agnes 站点时自定义模型配置不丢。
    private var agnesBaseUrlOverride: String? = null
    private var agnesVideoModelOverride: String? = null
    private var agnesImageModelOverride: String? = null

    /** v1.8.8：按当前 region + 已配置 override 重建 video/image Agnes provider。
     * v1.8.9：Key 按 region 分池（中国站读 agnes-cn-* 候选），并补回 custom-* 候选
     * （v1.8.8 合并构建逻辑时曾丢失，custom 模式 Key 兜底受影响）。 */
    private fun rebuildAgnes() {
        val region = com.dramafactory.core.provider.DefaultTextModelRouter.agnesRegion
        agnes = com.dramafactory.core.provider.AgnesProvider(
            apiKeyProvider = {
                // v1.9.9：KeyVault.load 在 key 不存在时抛 NoSuchElementException，必须吞掉并继续
                // fallback，否则 custom 模式未配 key 时永远到不了后面的 Agnes 候选。
                listOf("custom-video", "custom-image", CONFIG_VIDEO, CONFIG_IMAGE, CONFIG_TEXT, "agnes")
                    .firstNotNullOfOrNull { c ->
                        runCatching {
                            keyVault.load(com.dramafactory.core.provider.agnesScopedConfigId(c, region))
                        }.getOrNull()?.takeIf { k -> k.isNotBlank() }
                    }
                    .orEmpty()
            },
            baseUrlOverride = agnesBaseUrlOverride,
            videoModelOverride = agnesVideoModelOverride?.takeUnless { it == AUTO_VIDEO_MODEL },
            imageModelOverride = agnesImageModelOverride,
            autoSelectVideoModel = agnesVideoModelOverride == null || agnesVideoModelOverride == AUTO_VIDEO_MODEL,
            region = region,
        )
    }

    /** v1.8.9：当前 region 池下是否已配置任一 Agnes Key（LLM 检测/审计闸门的 llmReady 判据） */
    suspend fun agnesKeyReady(): Boolean {
        if (!::keyVault.isInitialized) return false
        val region = com.dramafactory.core.provider.DefaultTextModelRouter.agnesRegion
        return listOf(CONFIG_VIDEO, CONFIG_IMAGE, CONFIG_TEXT, "agnes").any { c ->
            runCatching {
                keyVault.load(com.dramafactory.core.provider.agnesScopedConfigId(c, region))
            }.getOrNull()?.isNotBlank() == true
        }
    }

    /** v1.9.9：当前激活视频供应商是否已配置有效 Key（与 AgnesProvider 运行时 apiKeyProvider 同源） */
    suspend fun hasVideoKey(): Boolean {
        if (!::keyVault.isInitialized) return false
        val region = com.dramafactory.core.provider.DefaultTextModelRouter.agnesRegion
        val active = VideoProviderRouter.activeVideoProviderId()
        val candidates = if (active == "agnes" || active == "custom") {
            // 与 rebuildAgnes 的 apiKeyProvider 完全一致：custom-* → agnes-*
            listOf(
                "custom-video",
                "custom-image",
                agnesScopedConfigId(CONFIG_VIDEO, region),
                agnesScopedConfigId(CONFIG_IMAGE, region),
                agnesScopedConfigId(CONFIG_TEXT, region),
                agnesScopedConfigId("agnes", region),
            )
        } else {
            listOf(VideoProviderRouter.configIdFor(active, region))
        }
        return candidates.any { cfgId ->
            runCatching { keyVault.load(cfgId) }.getOrNull()?.isNotBlank() == true
        }
    }

    /** v1.9.9：图像通道是否具备可用 Key（与 AgnesProvider 运行时 apiKeyProvider 同源） */
    suspend fun hasImageKey(): Boolean {
        if (!::keyVault.isInitialized) return false
        val region = com.dramafactory.core.provider.DefaultTextModelRouter.agnesRegion
        val active = VideoProviderRouter.activeVideoProviderId()
        val candidates = if (active == "agnes" || active == "custom") {
            // 与 rebuildAgnes 的 apiKeyProvider 一致，但图像通道优先 custom-image/agnes-image
            listOf(
                "custom-image",
                "custom-video",
                agnesScopedConfigId(CONFIG_IMAGE, region),
                agnesScopedConfigId(CONFIG_VIDEO, region),
                agnesScopedConfigId(CONFIG_TEXT, region),
                agnesScopedConfigId("agnes", region),
            )
        } else {
            listOf(VideoProviderRouter.configIdFor(active, region))
        }
        return candidates.any { cfgId ->
            runCatching { keyVault.load(cfgId) }.getOrNull()?.isNotBlank() == true
        }
    }

    /** v1.9.0：视频供应商 configId（按 region 分池，供设置页保存/读取 Key 用） */
    fun videoConfigIdFor(providerId: String): String =
        VideoProviderRouter.configIdFor(providerId, com.dramafactory.core.provider.DefaultTextModelRouter.agnesRegion)

    /** v1.9.0：按指定供应商 id 解析 VideoProvider（设置页测试连通/保存候选 Key 用） */
    fun resolveVideoProviderFor(providerId: String, overrideKey: String? = null): com.dramafactory.core.provider.VideoProvider =
        VideoProviderRouter.resolveFor(providerId, overrideKey)

    /** v1.9.0：设置激活的视频供应商（保存 Key 或显式切换时调用，持久化） */
    fun setActiveVideoProvider(id: String) = VideoProviderRouter.setActive(id)

    /** v1.8.8：设置页切换 Agnes 站点后调用，热重建 video/image provider（保留自定义模型 override）。 */
    fun applyAgnesRegion() = rebuildAgnes()
    lateinit var dao: com.dramafactory.app.data.DramaDao; internal set
    lateinit var movieLibraryDao: MovieLibraryDao; internal set

    /**
     * v1.9.2：App 生命周期级的后台协程作用域（SupervisorJob + IO）。
     * 资产生成 / AI 助手调用等"模型处理"任务挂在这里，切换标签或离开页面时页面 VM 被回收
     * 也不打断生图——任务仍会跑完并落库（Room），回来重读即可看到结果。
     * 区别于 viewModelScope：后者随页面 VM 销毁而取消，此前切走就丢在途生成。
     */
    val backgroundScope: kotlinx.coroutines.CoroutineScope by lazy {
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO +
                kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                    android.util.Log.e("AppGraph", "background task failed", e)
                })
    }

    /** T014：端上成片合成器（MovieAssembler，app 模块初始化，注入 ffmpeg-kit-full 8.1.7 community 维护版） */
    var movieAssembler: MovieAssembler = EmptyMovieAssembler; internal set

    /** T014：AI 全托管编排器（接 AppGraph 真实依赖） */
    lateinit var aiOrchestrator: DefaultAiOrchestrator; internal set

    /** T014：文本模型路由（Agnes/DeepSeek 双模型并存） */
    lateinit var textModelRouter: com.dramafactory.core.provider.TextModelRouter; internal set
    lateinit var textModelStore: com.dramafactory.core.provider.TextModelStore; internal set

    @Volatile private var initialized = false
    @Volatile private var appContextRef: Context? = null
    fun appContext(): Context? = appContextRef

    // ---- v1.7.18：视频参数（设置页可调，渲染按镜透传）----
    @Volatile
    var videoParams: com.dramafactory.core.model.VideoParams = com.dramafactory.core.model.VideoParams()
        internal set

    /**
     * v1.7.18：按 provider_configs 的 verified 记录重建 provider + 刷新视频参数。
     *
     * 「自定义模型添加后能用」的最后一环：设置页保存自定义视频/图像模型后只写了
     * provider_configs 表与 KeyVault，运行时从未读取 —— 用户加了自定义模型，提交还是
     * 打到 Agnes 官方地址。这里统一读取：
     * - channel=video 的 verified 记录若 provider_id=custom → 用其 base_url/model 重建 agnes；
     * - channel=image 的 custom 记录覆盖图像模型；
     * - video 的 extra_params.video_params → AppGraph.videoParams。
     */
    suspend fun refreshConfiguredProviders() {
        // 注意：此函数在 init 中、initialized 置位前就会调用，不能依赖 initialized 标志；
        // dao 在调用点之前已就绪（或 fallback BrokenDramaDao），且内部全部 runCatching 兜底。
        val vcfg = runCatching { dao.verifiedConfig(CONFIG_VIDEO) }.getOrNull()
        val icfg = runCatching { dao.verifiedConfig(CONFIG_IMAGE) }.getOrNull()
        val videoParams = com.dramafactory.core.model.VideoParams.fromExtra(vcfg?.extra_params)
        if (videoParams.width != null || videoParams.height != null ||
            videoParams.numFrames != null || videoParams.frameRate != null) {
            this.videoParams = videoParams
        }
        // v1.9.24：custom 与 Agnes 官方模型偏好分开恢复，不再因 custom 缺失而 early return 跳过 agnes 偏好
        val customV = vcfg?.takeIf { it.provider_id == "custom" }
        val customI = icfg?.takeIf { it.provider_id == "custom" }
        val baseUrl = customV?.let { parseExtraString(it.extra_params, "base_url") }
        val customVModel = customV?.model?.takeIf { it.isNotBlank() }
        val customIModel = customI?.model?.takeIf { it.isNotBlank() }
        agnesBaseUrlOverride = baseUrl
        agnesImageModelOverride = customIModel
        // Agnes 官方视频模型偏好（KeyVault 持久化）：custom 优先，否则 Agnes 偏好
        val agncsPref = runCatching { keyVault.load(CONFIG_VIDEO_MODEL) }.getOrNull()?.takeIf { it.isNotBlank() }
        agnesVideoModelOverride = customVModel ?: agncsPref
        rebuildAgnes()
        Log.i("AppGraph", "已重建 provider: base=${baseUrl ?: "默认"} video=${agnesVideoModelOverride ?: "默认"} image=${customIModel ?: "默认"}")
    }

    /** v1.9.24：当前生效的 Agnes 视频模型 id（UI 回填用；null=默认 agnes-video-v2.0） */
    val currentAgnesVideoModel: String? get() = agnesVideoModelOverride

    /**
     * v1.9.24：设置页切换 Agnes 视频模型（v2.0/2.5/2.5-flash）。
     * 持久化到独立 KeyVault 键，热重建 provider；默认模型等价 null（不持久化，省一次存储）。
     */
    suspend fun setAgnesVideoModel(model: String?) {
        val m = model?.takeIf { it.isNotBlank() && it != com.dramafactory.core.provider.AgnesProvider.MODEL_VIDEO }
        agnesVideoModelOverride = m
        runCatching {
            if (m == null) keyVault.delete(CONFIG_VIDEO_MODEL) else keyVault.save(CONFIG_VIDEO_MODEL, "agnes", m)
        }
        rebuildAgnes()
        Log.i("AppGraph", "Agnes 视频模型切换为: ${m ?: "默认(agnes-video-v2.0)"}")
    }

    private fun parseExtraString(extra: String?, key: String): String? = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(extra ?: "{}").jsonObject[key]
            ?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /**
     * ★F3 修复：当前 AI 管线的时代红线 key（由 createEpisode 按剧本自动推断，
     * 不再写死西汉）。generateImage 生成链路一律用 presetFor(currentEraKey) 组装约束。
     * 单活跃 run 假设下用 @Volatile 保证可见性；并发多 run 由上层串行保证。
     */
    @Volatile internal var currentEraKey: String = "han"

    /** 当前时代红线预设（AI 助手/资产生成共用；由 createEpisode 按剧本推断更新） */
    fun currentPreset(): com.dramafactory.core.quality.StylePreset =
        com.dramafactory.core.quality.EraDetector.presetFor(currentEraKey)

    /**
     * v1.9.10：用已接好的文本 LLM 把资产裸名词扩写成聚焦主体、符合时代红线的视觉描述，
     * 供各生图入口统一复用。失败（未配 Key / 401 / 超时）安全回退裸词，不阻断生图。
     */
    internal suspend fun enrichAssetPrompt(kind: String, baseName: String): String {
        val preset = currentPreset()
        return runCatching {
            com.dramafactory.core.quality.AssetPromptEnricher.enrich(
                chat = { msg ->
                    // chat(): ChatResponse（非 Result）——runCatching 兜住 401/超时等异常
                    runCatching {
                        text.chat(ChatRequest(messages = listOf(
                            com.dramafactory.core.model.ChatMessage("user", msg))))
                    }.getOrNull()?.content ?: ""
                },
                kind = kind, name = baseName,
                eraLabel = preset.era.label, forbidden = preset.forbiddenEraTerms)
        }.getOrDefault(baseName)
    }

    /**
     * 自动合成成片：查询该集 COMPLETED 且已落盘的视频镜，ffmpeg 拼装为 mp4。
     * 返回成片 File；无可用镜头/合成失败返回 null。
     * 复用成片库(S7)逻辑，供 AI 模式跑完自动产出成品展示。
     */
    suspend fun composeFilmIfReady(episodeId: String, ctx: Context): java.io.File? {
        val composer = movieAssembler
        if (composer is EmptyMovieAssembler) return null
        // v1.6.9 修：assemble 内部 executor 用 CountDownLatch.await 阻塞等 ffmpeg，
        // 必须在 IO 线程跑，否则在 Dispatchers.Main 上调用会 ANR/闪退。
        val res = runCatching {
            val tasks = dao.renderTasksOf(episodeId)
                .filter { it.state == "COMPLETED" && !it.local_file_uri.isNullOrBlank() }
                .sortedBy { it.shot_id }
            val clips = tasks.mapNotNull { java.io.File(it.local_file_uri ?: "") }
                .filter { it.exists() && it.length() > 0L }
            if (clips.isEmpty()) return@runCatching null
            val outDir = java.io.File(ctx.cacheDir, "movies")
            if (!outDir.exists()) outDir.mkdirs()
            val out = java.io.File(outDir, "$episodeId.mp4")
            withContext(kotlinx.coroutines.Dispatchers.IO) { composer.assemble(clips, out) } to out
        }.getOrNull() ?: return null
        val out = res.second
        return runCatching {
            when (val r = res.first) {
                is com.dramafactory.core.assemble.MovieAssembler.AssembleResult.Success -> {
                    if (r.output.exists()) {
                        runCatching {
                            movieLibraryDao.upsertFilmOf(
                                com.dramafactory.app.data.FinishedFilmEntity(
                                    film_id = episodeId, episode_id = episodeId,
                                    project_id = episodeId.substringBefore("_ep"),
                                    filePath = out.absolutePath, fileSize = out.length(),
                                    durationMs = (r.durationSeconds * 1000).toLong(),
                                    createdAt = System.currentTimeMillis(),
                                ))
                        }
                        out
                    } else null
                }
                else -> null
            }
        }.getOrNull()
    }
    val isInitialized: Boolean get() = initialized

    // ---- AI 助手可调用的内部能力（自然语言 agent 的"手"）----
    // 把 init 里的流水线 lambda 抽成独立方法，供 AiAssistantViewModel 分步/整体驱动

    /** 是否任一文本模型候选 configId 有非空 key（用于 AI 助手前置提示） */
    internal suspend fun hasAnyTextKey(): Boolean {
        // v1.9.6：改走 TextModelRouter 的 hasAnyKey，与设置页 saveKey/loadKey 用同一套 region 化 cfgId，
        // 修复中国站 Agnes 保存的 Key 在 text-agnes-cn，而死列表只查 text-agnes 导致误判未配置的 bug。
        return runCatching { textModelRouter.hasAnyKey() }.getOrDefault(false)
    }

    /**
     * 解析当前激活的文本模型 Provider，key 多候选兜底。
     *
     * 一致性契约（v1.8.6 理清，修复此前「激活模型形同摆设」）：
     *   - [textModelRouter.activeTextModelId] 是用户在设置页 / AI 对话页选定的激活模型，
     *     已落盘（v1.8.4）、默认 agnes，是选路的**唯一依据**。
     *   - 此前这里写死 DeepSeek 优先候选表，只要任意 DeepSeek key 存在就走 DeepSeek，
     *     与 v1.8.2（默认 agnes）、v1.8.4（激活持久化）的语义冲突。现改为以激活模型为准。
     *   - 优先用激活 provider 的 key（多前缀兜底：text-<id> / <id> / <id>-text / <id>-chat）；
     *     激活 provider 无 key 时，若另一 provider 有 key 则临时优雅回退，保证链路不中断；
     *     两者都无 key：空跑激活 provider，由上层提示去设置页配置。
     */
    internal suspend fun textProviderFor(): com.dramafactory.core.provider.TextProvider {
        val active = textModelRouter.activeTextModelId()
        return resolveTextProviderFor(active, { cfgId ->
            runCatching { keyVault.load(cfgId) }.getOrNull()?.takeIf { it.isNotBlank() }
        }, region = com.dramafactory.core.provider.DefaultTextModelRouter.agnesRegion)
    }

    /** 从剧本文本提取资产（文字模型走用户自选 DeepSeek 等，key 多候选兜底） */
    internal suspend fun extractAssetsFor(text: String): List<DefaultAiOrchestrator.AiAsset> {
        val tp = textProviderFor()
        val r = com.dramafactory.core.quality.LlmAssetExtractor.extract(text) { req -> tp.chat(req) }
        return r.assets.map { a ->
            DefaultAiOrchestrator.AiAsset(assetId = "a_${System.nanoTime()}", kind = a.kind, name = a.name, prompt = a.desc)
        }
    }

    /** 生成分镜（文字模型） */
    internal suspend fun genShotsFor(script: String): List<DefaultAiOrchestrator.AiShot> {
        val tp = textProviderFor()
        val r = com.dramafactory.core.quality.AiStoryboardDirector.generate(
            script, chat = { req -> tp.chat(req) })
        return r.shots.map { s -> DefaultAiOrchestrator.AiShot(s.shotNo, s.action ?: "", s.dialogue, s.assetIds) }
    }

    /** 入渲染队（按分镜生成视频任务） */
    internal suspend fun enqueueRenderFor(episodeId: String, shots: List<DefaultAiOrchestrator.AiShot>): Int {
        val projectId = episodeId.substringBeforeLast("_ep").ifBlank { episodeId }
        // v1.7.2：问题1b——渲染前确保角色/场景资产已生图，否则各镜失去参考图→长相漂移。
        // 缺失 remote_url 的资产在此补齐生成（走 Agnes 生图），保证一致性锁脸有图可注入。
        runCatching {
            val missings = dao.assetsAllOf(projectId)
                .filter { (it.kind == "character" || it.kind == "scene") && it.remote_url.isNullOrBlank() }
            for (a in missings) {
                val preset = com.dramafactory.core.quality.EraDetector.presetFor(currentEraKey)
                // v1.7.17：走统一生成器。原实现给图像接口传了 negativePrompt，
                // Agnes 图像端不支持该字段（400），被 runCatching 吞掉后 continue，
                // 导致「渲染前补齐缺失资产图」这条链路从来没成功过。
                val url = runCatching {
                    com.dramafactory.app.ui.AssetImageGenerator.generate(
                        provider = agnes, kind = a.kind,
                        basePrompt = enrichAssetPrompt(a.kind, a.prompt), preset = preset)
                }.getOrNull() ?: continue
                runCatching { dao.setAssetRemoteUrl(a.asset_id, url, System.currentTimeMillis()) }
            }
        }
        val metas = shots.map {
            com.dramafactory.core.model.ShotMeta(shotId = "${episodeId}_shot${it.shotNo}", episodeId = episodeId, prompt = it.action)
        }
        val queue = com.dramafactory.app.ui.RenderRuntime.queueFor(episodeId)
        runCatching { withContext(kotlinx.coroutines.Dispatchers.IO) { queue.enqueueEpisode(episodeId, metas) } }.map { metas.size }.getOrElse { 0 }
        return metas.size
    }

    /** 合成成片（渲染任务齐全后） */
    internal suspend fun composeFilmFor(episodeId: String, ctx: Context): java.io.File? =
        composeFilmIfReady(episodeId, ctx)

    /** 完整流水线：用户说"开工/生成整部短剧"时调用（自动建项目+集、跑提取→图→分镜→渲染）
     * @param onEvent 五阶段实时进度回调（每条 ProgressEvent.message 推给 UI，实现"主动汇报进度"） */
    internal suspend fun runFullPipeline(
        scriptText: String,
        onEvent: (String) -> Unit = {},
    ): Result<com.dramafactory.core.orchestrate.AiOrchestrator.PipelineRun> {
        val orc = aiOrchestrator
        // v1.7.3：边跑边把进度事件推给 UI（非阻塞收集，run 是 suspend 阻塞，并发 drain events）
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            var lastN = 0
            orc.events.collect { list ->
                for (i in lastN until list.size) onEvent(list[i].message)
                lastN = list.size
            }
        }
        try {
            return orc.run(scriptText, "", { pid, epId -> })
        } finally {
            job.cancel()
        }
    }

    private val ioScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /**
     * v1.9.28: 真实 ImgBB 图床上传实现（供 RenderQueue 使用）。
     * 将本地 content:///file:///data: 媒体上传到 ImgBB 换取公网 URL，满足 Agnes 2.5 要求。
     */
    suspend fun uploadImageToCloud(uri: String): String {
        if (!com.dramafactory.core.provider.MediaUrlResolver.needsUpload(uri)) return uri
        
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                if (uri.startsWith("data:image")) {
                    val b64 = uri.substringAfter(",")
                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                } else {
                    appContext()?.contentResolver?.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
                        ?: throw IllegalStateException("openInputStream returned null")
                }
            }.getOrElse { throw IllegalStateException("读取本地媒体失败($uri): ${it.message}") }
        }
        
        return withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = keyVault.readSync("imgbb_api_key").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("ImgBB API Key 未配置（请在设置页「ImgBB 图床」填写）")
                
                val response = com.dramafactory.core.provider.SharedHttp.client.post("https://api.imgbb.com/1/upload?key=\$apiKey") {
                    setBody(MultiPartFormDataContent(
                        formData {
                            append("image", bytes, Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"upload.jpg\"")
                            })
                        }
                    ))
                }.bodyAsText()
                
                val root = Json.parseToJsonElement(response).jsonObject
                if (root["success"]?.jsonPrimitive?.contentOrNull == "true") {
                    root["data"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalStateException("ImgBB 返回成功但无 URL")
                } else {
                    throw IllegalStateException("ImgBB 上传失败: ${root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull}")
                }
            }.getOrElse { 
                Log.e("AppGraph", "ImgBB upload failed", it)
                throw IllegalStateException("上传图床失败: ${it.message}") 
            }
        }
    }

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            appContextRef = app
            // 先建一个安全默认编排器，确保即使后续步骤失败，aiOrchestrator 也已赋值（防 lateinit 崩）
            if (!::aiOrchestrator.isInitialized) {
                aiOrchestrator = DefaultAiOrchestrator()
            }
            keyVault = runCatching { AndroidKeyVault.create(app) as KeyVault }
                .getOrElse { e ->
                    android.util.Log.e("AppGraph", "keyvault init failed", e)
                    com.dramafactory.core.storage.InMemoryKeyVault()
                }
            try {
                val db = DramaDatabase.get(app)
                dao = db.dao()
                movieLibraryDao = db.movieLibraryDao()
                checkpointStore = RoomCheckpointStore(dao)
            } catch (t: Throwable) {
                Log.e("AppGraph", "room init failed, fallback in-memory", t)
                dao = BrokenDramaDao()
                movieLibraryDao = BrokenMovieLibraryDao()
                checkpointStore = com.dramafactory.core.storage.InMemoryCheckpointStore()
                roomInitError = t.message ?: t.javaClass.name
            }
            // v1.8.8：预热 Agnes 服务站点（中国站/国际站），再按当前 region 构建 video/image provider
            com.dramafactory.core.provider.DefaultTextModelRouter.agnesRegion = kotlinx.coroutines.runBlocking {
                runCatching { keyVault.load(com.dramafactory.core.provider.PREF_AGNES_REGION) }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { com.dramafactory.core.provider.AgnesRegion.valueOf(it) }.getOrNull() }
                    ?: com.dramafactory.core.provider.AgnesRegion.INTERNATIONAL
            }
            // 图像/视频统一走 Agnes：key 可能在设置页被存到 agnes / agnes-video / agnes-image 任一 configId，
            // 这里按候选顺序取第一个非空，避免"设了key但读不到"导致图像/视频生成401失败
            rebuildAgnes()
            // v1.9.0：视频通道供应商路由——按激活供应商动态选适配器（Kling/即梦/Runway/Luma/Pika/agnes/custom）
            VideoProviderRouter.init(
                keyVault = keyVault,
                regionProvider = { com.dramafactory.core.provider.DefaultTextModelRouter.agnesRegion },
                agnesProviderProvider = { agnes },
            )
            // v1.9.1：图像通道路由——激活 Agnes 走原生 image 端点，激活其他家退化为 image2video 首帧
            com.dramafactory.core.provider.ImageProviderRouter.init(
                videoRouter = VideoProviderRouter,
                agnesProvider = { agnes },
                agnesKeyReady = { agnesKeyReady() },
                frameExtractor = { url -> MediaHelpers.extractFirstFrameAsDataUri(url) },
            )
            // v1.7.18：按 provider_configs 里已保存的自定义模型重建 provider（"添加后能用"）。
            // 设置页保存自定义模型只落库 + KeyVault，此前运行时从不读表 → 自定义配置形同虚设。
            // 必须在 initialized=true 之后跑（refreshConfiguredProviders 内部早退保护），
            // 故挂 ioScope 异步执行；init 同步跑到 548 行置位后才轮到它调度。
            ioScope.launch { refreshConfiguredProviders() }
            budgetGuard = DefaultBudgetGuard()

            textModelStore = runCatching { com.dramafactory.core.provider.InMemoryTextModelStore(keyVault = keyVault) }
                .getOrElse { com.dramafactory.core.provider.InMemoryTextModelStore(keyVault = com.dramafactory.core.storage.InMemoryKeyVault()) }
            textModelRouter = com.dramafactory.core.provider.DefaultTextModelRouter
            com.dramafactory.core.provider.DefaultTextModelRouter.store = textModelStore
            // v1.8.4：激活文本模型已落盘（saveActiveModel 写 KeyVault），这里在 init 同步预热进内存，
            // 保证 initialized=true 之前内存已是持久值，避免任何 UI 首帧读到默认 agnes 再跳变。
            kotlinx.coroutines.runBlocking {
                runCatching { textModelStore.hydrateActive() }
                // v1.9.5 修复：Agnes 站点此前只预热了 DefaultTextModelRouter.agnesRegion，
                // store 内部 region 缓存恒为构造默认值 INTERNATIONAL。冷启动后若持久值为中国站，
                // 走 router.resolve() 的路径会出现「provider 打中国站 URL，Key 却从国际站池
                // text-agnes 读取」的错配 → 鉴权失败。这里将已预热的 router 值同步进 store，
                // 保证两处副本一致（设置页手动切换时本就会同时更新两者，仅冷启动路径缺失）。
                runCatching {
                    textModelStore.saveAgnesRegion(
                        com.dramafactory.core.provider.DefaultTextModelRouter.agnesRegion)
                }
            }

            try {
                movieAssembler = MovieAssemblerImpl(executor = androidFfmpegKitExecutor())
            } catch (t: Throwable) {
                Log.e("AppGraph", "movieAssembler init failed", t)
            }

            // T014：AI 全托管编排器 —— 内部用 ioScope 承载 suspend 调用
            aiOrchestrator = buildAiOrchestrator()
            initialized = true
        }
    }

    /** T014：Room 初始化失败时的空 MovieAssembler 兜底。 */
    var roomInitError: String? = null; private set

}

// ---- 文本 Provider 解析（文件级纯函数，便于 JVM 单测，不触发 object AppGraph 的 init）----

/** 某 provider 在 keyVault 中可能落库的 configId 候选（多前缀兜底，历史兼容；Agnes 按 region 分池） */
private fun textKeyConfigIds(providerId: String, region: AgnesRegion): List<String> {
    val base = when (providerId) {
        "deepseek" -> listOf("text-deepseek", "deepseek", "deepseek-chat")
        else -> listOf("text-agnes", "agnes", "agnes-text")
    }
    return base.map { agnesScopedConfigId(it, region) }
}

/** 按 providerId 构造带 key 的 TextProvider（空 key 即上层应引导去设置页的空跑态） */
private fun buildTextProvider(
    providerId: String,
    key: String,
    region: AgnesRegion,
): com.dramafactory.core.provider.TextProvider =
    when (providerId) {
        "deepseek" -> com.dramafactory.core.provider.DeepSeekProvider(apiKeyProvider = { key })
        else -> com.dramafactory.core.provider.AgnesProvider(apiKeyProvider = { key }, region = region)
    }

/**
 * 以激活文本模型为准解析实际 Provider（一致性契约见 [textProviderFor]）。
 *
 * 选路：激活 provider 优先 → 无 key 则另一 provider 优雅回退 → 都无 key 空跑激活 provider。
 * [keyLoader] 接收 configId，返回非空 key 或 null（缺失/空白）。
 * [region] 为 Agnes 服务站点（中国站/国际站），仅影响 Agnes 通道。
 */
internal suspend fun resolveTextProviderFor(
    active: String,
    keyLoader: suspend (String) -> String?,
    region: AgnesRegion = com.dramafactory.core.provider.DefaultTextModelRouter.agnesRegion,
): com.dramafactory.core.provider.TextProvider {
    val isDeepseek = active.startsWith("deepseek", ignoreCase = true)
    val primary = if (isDeepseek) "deepseek" else "agnes"
    val fallback = if (isDeepseek) "agnes" else "deepseek"

    // 1) 优先激活 provider 的 key
    val primaryKey = textKeyConfigIds(primary, region).firstNotNullOfOrNull { keyLoader(it) }
    if (primaryKey != null) return buildTextProvider(primary, primaryKey, region)

    // 2) 激活 provider 无 key → 另一 provider 有 key 则临时优雅回退
    val fbKey = textKeyConfigIds(fallback, region).firstNotNullOfOrNull { keyLoader(it) }
    if (fbKey != null) return buildTextProvider(fallback, fbKey, region)

    // 3) 都无 key → 空跑激活 provider，由上层提示去设置页配置
    return buildTextProvider(primary, "", region)
}