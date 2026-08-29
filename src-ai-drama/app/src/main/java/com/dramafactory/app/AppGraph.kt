package com.dramafactory.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.withContext
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
import com.dramafactory.core.provider.CheckpointStore
import com.dramafactory.core.provider.KeyVault
import com.dramafactory.core.orchestrate.DefaultAiOrchestrator
import com.dramafactory.core.orchestrate.PipelineStage5
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AppGraph {

    const val CONFIG_VIDEO = "agnes-video"
    const val CONFIG_TEXT = "agnes-text"
    const val CONFIG_IMAGE = "agnes-image"

    lateinit var keyVault: KeyVault; private set
    lateinit var checkpointStore: CheckpointStore; private set
    lateinit var agnes: AgnesProvider; internal set
    val video get() = agnes
    val text get() = agnes
    val image get() = agnes
    lateinit var budgetGuard: DefaultBudgetGuard; private set
    lateinit var dao: com.dramafactory.app.data.DramaDao; internal set
    lateinit var movieLibraryDao: MovieLibraryDao; internal set

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

    /**
     * ★F3 修复：当前 AI 管线的时代红线 key（由 createEpisode 按剧本自动推断，
     * 不再写死西汉）。generateImage 生成链路一律用 presetFor(currentEraKey) 组装约束。
     * 单活跃 run 假设下用 @Volatile 保证可见性；并发多 run 由上层串行保证。
     */
    @Volatile private var currentEraKey: String = "han"

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

    // ---- 图像下载 / 降采样工具（F2 审计用，与 ViewModels.auditGeneratedAsset 同策略）----

    /** 下载图像为字节数组（data:image 直接 base64 解码；http(s) 走 java.net）。 */
    private fun fetchImageBytes(url: String): ByteArray? = runCatching {
        if (url.startsWith("data:image")) {
            val b64 = url.substringAfter(",")
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } else {
            java.net.URL(url).openStream().use { it.readBytes() }
        }
    }.getOrNull()

    /** 降采样到 512px 内 JPEG 并返回 data URI（配合 G2 多模态审计，防 base64 爆上下文）。 */
    private fun downscaleToDataUri(bytes: ByteArray): String? = runCatching {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        val scale = 512.0 / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
        val w = (bmp.width * scale).toInt().coerceIn(1, 512)
        val h = (bmp.height * scale).toInt().coerceIn(1, 512)
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
        val bos = java.io.ByteArrayOutputStream()
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrNull()


    // ---- AI 助手可调用的内部能力（自然语言 agent 的"手"）----
    // 把 init 里的流水线 lambda 抽成独立方法，供 AiAssistantViewModel 分步/整体驱动

    /** 是否任一文本模型候选 configId 有非空 key（用于 AI 助手前置提示） */
    internal suspend fun hasAnyTextKey(): Boolean {
        // DeepSeek 优先（用户明确：APP 文字模型用 DeepSeek），其次 Agnes。
        val candidates = listOf("text-deepseek", "deepseek", "deepseek-chat", "text-agnes", "agnes", "agnes-text")
        // v1.6.6 修：AndroidKeyVault.load 找不到时抛 NoSuchElementException，必须 runCatching 兜。
        return candidates.any { c -> runCatching { keyVault.load(c) }.getOrNull()?.isNotBlank() == true }
    }

    /**
     * 解析当前激活的文本模型 Provider，key 多候选兜底。
     * 修「no key for config text-agnes」：AndroidKeyVault 存文本 key 用 text- 前缀，
     * 而 router 内部按裸 agnes/deepseek 读 → 不匹配。这里按候选顺序取第一个非空 key，
     * 无论用户在设置页把 key 存到 text-agnes / agnes / deepseek / text-deepseek / agnes-text 哪个 id 都能读到。
     */
    internal suspend fun textProviderFor(): com.dramafactory.core.provider.TextProvider {
        val active = textModelRouter.activeTextModelId()
        // DeepSeek 优先（用户明确：APP 文字模型用 DeepSeek），其次 Agnes。
        // 候选 configId 同时覆盖 keyVault 可能的 text- 前缀与裸 id。
        val candidates = listOf(
            "text-deepseek" to "deepseek",
            "deepseek" to "deepseek",
            "deepseek-chat" to "deepseek",
            "text-agnes" to "agnes",
            "agnes" to "agnes",
            "agnes-text" to "agnes",
        )
        // 按候选顺序取第一个非空 key，并记住命中的 providerId，避免受 active 默认值误导。
        var hitProvider: String? = null
        val key = candidates.firstNotNullOfOrNull { (cfgId, prov) ->
            val k = runCatching { keyVault.load(cfgId) }.getOrNull()?.takeIf { it.isNotBlank() }
            if (k != null) { hitProvider = prov; k } else null
        }.orEmpty()
        if (key.isBlank()) {
            // 都没 key：用激活模型（或默认 deepseek）的 provider 空跑，让上层显示"调用失败"引导去设置。
            hitProvider = if (active.startsWith("deepseek")) "deepseek" else "agnes"
        }
        return when (hitProvider) {
            "deepseek" -> com.dramafactory.core.provider.DeepSeekProvider(apiKeyProvider = { key })
            else -> com.dramafactory.core.provider.AgnesProvider(apiKeyProvider = { key })
        }
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
        val r = com.dramafactory.core.quality.AiStoryboardDirector.generate(script) { req -> tp.chat(req) }
        return r.shots.map { s -> DefaultAiOrchestrator.AiShot(s.shotNo, s.action ?: "", s.dialogue) }
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
                val prompt = if (a.kind == "character") preset.withCharacterStudioConstraints(a.prompt)
                             else preset.withEraConstraints(a.prompt)
                val neg = if (a.kind == "character") preset.studioNegativePromptFor() else preset.negativePromptFor()
                val url = runCatching { agnes.generateImage(
                    com.dramafactory.core.model.ImageGenRequest(prompt = prompt, negativePrompt = neg)) }.getOrNull() ?: continue
                runCatching { dao.setAssetRemoteUrl(a.asset_id, url, System.currentTimeMillis()) }
            }
        }
        val metas = shots.map {
            com.dramafactory.core.model.ShotMeta(shotId = "${episodeId}_shot${it.shotNo}", episodeId = episodeId, prompt = it.action)
        }
        val queue = com.dramafactory.app.ui.RenderRuntime.queueFor(episodeId)
        runCatching { runBlocking { queue.enqueueEpisode(episodeId, metas) } }.map { metas.size }.getOrElse { 0 }
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
            // 图像/视频统一走 Agnes：key 可能在设置页被存到 agnes / agnes-video / agnes-image 任一 configId，
            // 这里按候选顺序取第一个非空，避免"设了key但读不到"导致图像/视频生成401失败
            agnes = AgnesProvider(apiKeyProvider = {
                listOf(CONFIG_VIDEO, CONFIG_IMAGE, CONFIG_TEXT, "agnes")
                    .firstNotNullOfOrNull { keyVault.load(it).takeIf { k -> k.isNotBlank() } }
                    .orEmpty()
            })
            budgetGuard = DefaultBudgetGuard()

            textModelStore = runCatching { com.dramafactory.core.provider.InMemoryTextModelStore(keyVault = keyVault) }
                .getOrElse { com.dramafactory.core.provider.InMemoryTextModelStore(keyVault = com.dramafactory.core.storage.InMemoryKeyVault()) }
            textModelRouter = com.dramafactory.core.provider.DefaultTextModelRouter
            com.dramafactory.core.provider.DefaultTextModelRouter.store = textModelStore

            try {
                movieAssembler = MovieAssemblerImpl(executor = androidFfmpegKitExecutor())
            } catch (t: Throwable) {
                Log.e("AppGraph", "movieAssembler init failed", t)
            }

            // T014：AI 全托管编排器 —— 内部用 ioScope 承载 suspend 调用
            aiOrchestrator = DefaultAiOrchestrator(
                activeTextModelIdProvider = { textModelRouter.activeTextModelId() },
                createProject = { name ->
                    val id = "p_" + System.currentTimeMillis()
                    dao.upsertProject(com.dramafactory.app.data.ProjectEntity(
                        project_id = id, name = name,
                        created_at = System.currentTimeMillis(),
                    ))
                    id
                },
                createEpisode = { projectId, scriptText ->
                    val epId = "${projectId}_ep1"
                    // ★F3 修复：按剧本自动推断时代红线（LLM 优先，规则兜底），替换原写死 "han"。
                    // 第十三轮 EraDetector 与人工模式（ViewModels:370-374）同策略。
                    val llmReady = runCatching {
                        !AppGraph.keyVault.load(AppGraph.CONFIG_VIDEO).isNullOrBlank()
                    }.getOrDefault(false)
                    currentEraKey = runCatching {
                        com.dramafactory.core.quality.EraDetector.detect(scriptText, llmReady) { req ->
                            agnes.chat(req)
                        }
                    }.getOrElse { com.dramafactory.core.quality.EraDetector.Detection("han", "", false) }.eraKey
                    val flags = DramaDatabase.Companion.AiStageFlags
                    val stageFlags =
                        flags.put(flags.putBool("", flags.AI_MANAGED, true),
                            flags.PROJECT_ID, projectId)
                    dao.upsertEpisode(com.dramafactory.app.data.EpisodeEntity(
                        episode_id = epId, project_id = projectId, ep_no = 1,
                        script_json = scriptText,
                        stage_flags = stageFlags,
                    ))
                    epId
                },
                checkModel = { modelId ->
                    if (modelId.isBlank()) {
                        dao.verifiedConfig("text")?.let { Result.success(Unit) }
                            ?: Result.failure(
                                com.dramafactory.core.model.ProviderError.AuthError("未验证文本模型"))
                    } else {
                        runCatching {
                            runBlocking { textModelRouter.validate(modelId).getOrThrow() }
                        }
                    }
                },
                extractAssets = { text, _ ->
                    runCatching {
                        // 文字模型走用户自选(DeepSeek等)，key 多候选兜底（修 text-agnes 读不到）
                        val tp = textProviderFor()
                        val r = com.dramafactory.core.quality.LlmAssetExtractor.extract(text) { req ->
                            tp.chat(req)
                        }
                        r.assets.map { a ->
                            DefaultAiOrchestrator.AiAsset(
                                assetId = "a_${System.nanoTime()}",
                                kind = a.kind, name = a.name, prompt = a.desc,
                            )
                        }
                    }
                },
                generateImage = { asset ->
                    runCatching {
                        runBlocking {
                            // ★F3 修复：用按剧本自动推断的 currentEraKey 取预设，不再写死 "han"
                            val preset = com.dramafactory.core.quality.EraDetector.presetFor(currentEraKey)
                            val prompt = if (asset.kind == "character") {
                                preset.withCharacterStudioConstraints(asset.prompt)
                            } else {
                                preset.withEraConstraints(asset.prompt)
                            }
                            val neg = if (asset.kind == "character") {
                                preset.studioNegativePromptFor()
                            } else {
                                preset.negativePromptFor()
                            }
                            val url = agnes.generateImage(
                                com.dramafactory.core.model.ImageGenRequest(
                                    prompt = prompt, negativePrompt = neg))
                            // 落盘：生成成功回填资产图的 remote_url
                            runCatching { dao.setAssetRemoteUrl(asset.assetId, url, System.currentTimeMillis()) }
                            url
                        }
                    }
                },
                auditAsset = { asset ->
                    // ★F2 修复：真实质量审计——调用 AssetAuditor.audit（G1 文件级硬校验 + G2 多模态打分），
                    // 替换原直接返回 passed=true 的「假通过」（原实现关闭了 PRD F03 两层闸门）。
                    // 未生成图像/未配置 Key 时不阻断流水线，但明确标注未审计（audit_skipped_*）。
                    // 注意：λ 返回类型必须是 Result<AuditResult>，故整体包在 runCatching 内；
                    // 异常会变为 Result.failure，由编排器 AUDIT 阶段按「未过」处理（WARN 标红放行）。
                    runCatching {
                        val remoteUrl = runCatching { dao.assetRemoteUrl(asset.assetId) }.getOrNull()
                        val llmReady = runCatching {
                            !AppGraph.keyVault.load(AppGraph.CONFIG_VIDEO).isNullOrBlank()
                        }.getOrDefault(false)
                        if (remoteUrl.isNullOrBlank() || !llmReady) {
                            return@runCatching DefaultAiOrchestrator.AuditResult(passed = true, reason = "audit_skipped_no_image_or_key")
                        }
                        val bytes = fetchImageBytes(remoteUrl)
                            ?: return@runCatching DefaultAiOrchestrator.AuditResult(passed = true, reason = "audit_image_fetch_failed")
                        val dataUri = downscaleToDataUri(bytes)
                            ?: return@runCatching DefaultAiOrchestrator.AuditResult(passed = true, reason = "audit_image_decode_failed")
                        val describer = com.dramafactory.core.quality.AssetAuditor.agnesDescriber(agnes, "")
                        val engine = com.dramafactory.app.ui.QualityEngine()
                        val outcome = engine.auditAsset(
                            imageBytes = bytes, imageDataUri = dataUri,
                            description = asset.prompt, assetType = asset.kind,
                            describer = describer,
                        )
                        DefaultAiOrchestrator.AuditResult(
                            passed = outcome.auditState == com.dramafactory.core.model.AuditState.APPROVED,
                            score = outcome.qualityScore,
                            reason = outcome.rejectReason,
                        )
                    }
                },
                generateShots = { script, _ ->
                    runCatching {
                        // 文字模型走用户自选(DeepSeek等)，key 多候选兜底
                        val tp = textProviderFor()
                        val r = com.dramafactory.core.quality.AiStoryboardDirector.generate(script) { req ->
                            tp.chat(req)
                        }
                        r.shots.map { s ->
                            DefaultAiOrchestrator.AiShot(s.shotNo, s.action ?: "", s.dialogue)
                        }
                    }
                },
                enqueueRender = { episodeId, shots ->
                    val metas = shots.map {
                        com.dramafactory.core.model.ShotMeta(
                            shotId = "${episodeId}_shot${it.shotNo}",
                            episodeId = episodeId,
                            prompt = it.action,
                        )
                    }
                    val queue = com.dramafactory.app.ui.RenderRuntime.queueFor(episodeId)
                    runCatching { runBlocking { queue.enqueueEpisode(episodeId, metas) } }
                        .map { metas.size }
                },
                persistAssets = { episodeId, assets ->
                    val projectId = episodeId.substringBeforeLast("_ep")
                    for (a in assets) {
                        runCatching {
                            dao.upsertAsset(com.dramafactory.app.data.AssetEntity(
                                asset_id = a.assetId,
                                project_id = projectId,
                                kind = a.kind,
                                prompt = a.name + "：" + a.prompt,
                                updated_at = System.currentTimeMillis(),
                            ))
                        }
                    }
                },
                persistShots = { episodeId, shots ->
                    for (s in shots) {
                        runCatching {
                            dao.upsertShot(com.dramafactory.app.data.ShotEntity(
                                shot_id = "${episodeId}_shot${s.shotNo}",
                                episode_id = episodeId,
                                project_id = episodeId.substringBeforeLast("_ep"),
                                shot_no = s.shotNo,
                                action = s.action,
                                dialogue = s.dialogue,
                            ))
                        }
                    }
                },
                writeCheckpoint = { episodeId, stage, assetCount, shotCount, renderEnqueued, failed ->
                    val flags = DramaDatabase.Companion.AiStageFlags
                    var f = dao.episode(episodeId)?.stage_flags ?: "{}"
                    f = flags.put(f, flags.LAST_SUCCESS_STAGE, stage.name)
                    f = flags.putInt(f, flags.ASSET_COUNT, assetCount)
                    f = flags.putInt(f, flags.SHOT_COUNT, shotCount)
                    f = flags.putBool(f, flags.RENDER_ENQUEUED, renderEnqueued)
                    failed?.let { f = flags.put(f, flags.FAILED_STAGE, it.name) }
                    dao.upsertEpisode(dao.episode(episodeId)?.copy(stage_flags = f)
                        ?: com.dramafactory.app.data.EpisodeEntity(
                            episode_id = episodeId, project_id = "unknown",
                            ep_no = 1, stage_flags = f,
                        ))
                },
                readCheckpoint = { episodeId ->
                    val flags = DramaDatabase.Companion.AiStageFlags
                    val stageName = flags.getString(
                        dao.episode(episodeId)?.stage_flags,
                        flags.LAST_SUCCESS_STAGE)
                    if (stageName != null) runCatching { PipelineStage5.valueOf(stageName) }.getOrNull()
                    else null
                },
                // ★F4 修复：断点续跑时读回真实剧本（episodes.script_json），替换 DefaultAiOrchestrator 内的 "RETRY_STUB" 占位
                readScript = { episodeId ->
                    runCatching { dao.episode(episodeId)?.script_json }.getOrNull().orEmpty()
                },
            )

            initialized = true
        }
    }

    /** T014：Room 初始化失败时的空 MovieAssembler 兜底。 */
    private object EmptyMovieAssembler : MovieAssembler {
        override val progress: StateFlow<MovieAssembler.MovieAssembleProgress> =
            MutableStateFlow(MovieAssembler.MovieAssembleProgress(
                MovieAssembler.AssembleStage.DONE, 0, 0, "empty", 0))
        override suspend fun assemble(
            clips: List<File>, output: File,
            grade: MovieAssembler.ColorGradePreset,
        ): MovieAssembler.AssembleResult =
            MovieAssembler.AssembleResult.Failure(
                MovieAssembler.Strategy.CONCAT_COPY, "ffmpeg-kit 未初始化，请使用云端合成")
    }

    var roomInitError: String? = null; private set

    private class BrokenDramaDao : com.dramafactory.app.data.DramaDao {
        override suspend fun upsertProject(p: com.dramafactory.app.data.ProjectEntity) {}
        override suspend fun listProjects(): List<com.dramafactory.app.data.ProjectEntity> = emptyList()
        override suspend fun deleteProject(id: String) {}
        override suspend fun upsertAsset(a: com.dramafactory.app.data.AssetEntity) {}
        override suspend fun assetsOf(projectId: String, kind: String): List<com.dramafactory.app.data.AssetEntity> = emptyList()
        override suspend fun assetsAllOf(projectId: String): List<com.dramafactory.app.data.AssetEntity> = emptyList()
        override suspend fun updateAssetLocal(assetId: String, source: String, imageUri: String?, videoUri: String?, referenceImageUri: String?, prompt: String, updatedAt: Long) {}
        override suspend fun setAssetReferenceImage(assetId: String, referenceImageUri: String?, updatedAt: Long) {}
        override suspend fun setAssetQuality(assetId: String, qualityScore: Double?, auditState: String, defectsJson: String?, rejectReason: String?, g1ErrorCode: String?, faceRatio: Double?, poseRole: String?, updatedAt: Long) {}
        override suspend fun updateAssetPrompt(assetId: String, prompt: String, updatedAt: Long) {}
        override suspend fun setAssetRemoteUrl(assetId: String, remoteUrl: String, updatedAt: Long) {}
        override suspend fun assetRemoteUrl(assetId: String): String? = null
        override suspend fun deleteAsset(assetId: String) {}
        override suspend fun assetQuality(assetId: String): com.dramafactory.app.data.AssetQualityRow? = null
        override suspend fun assetQualities(projectId: String): List<com.dramafactory.app.data.AssetQualityRow> = emptyList()
        override suspend fun setEpisodeAllowedCrossEra(episodeId: String, allowed: String) {}
        override suspend fun episodeAllowedCrossEra(episodeId: String): String? = null
        override suspend fun setReviewState(assetId: String, state: String) {}
        override suspend fun upsertShot(s: com.dramafactory.app.data.ShotEntity) {}
        override suspend fun shotsOf(episodeId: String): List<com.dramafactory.app.data.ShotEntity> = emptyList()
        override suspend fun deleteShotsOf(episodeId: String) {}
        override suspend fun deleteShot(shotId: String) {}
        override suspend fun renderStatesOf(episodeId: String): List<com.dramafactory.app.data.RenderStateRow> = emptyList()
        override suspend fun setShotKeyframes(shotId: String, first: String?, last: String?) {}
        override suspend fun setShotReferenceVideo(shotId: String, uri: String?) {}
        override suspend fun shotKeyframes(shotId: String): com.dramafactory.app.data.ShotEntity? = null
        override suspend fun shotReferenceVideo(shotId: String): String? = null
        override suspend fun upsertRenderTask(t: com.dramafactory.app.data.RenderTaskEntity) {}
        override suspend fun renderTasksOf(ep: String): List<com.dramafactory.app.data.RenderTaskEntity> = emptyList()
        override suspend fun renderTask(shotId: String): com.dramafactory.app.data.RenderTaskEntity? = null
        override suspend fun renderTasksOfShot(shotId: String): List<com.dramafactory.app.data.RenderTaskEntity> = emptyList()
        override suspend fun allEpisodeIds(): List<String> = emptyList()
        override suspend fun renderTasksOfEpOrdered(ep: String): List<com.dramafactory.app.data.RenderTaskEntity> = emptyList()
        override suspend fun pendingRepoll(ep: String): List<com.dramafactory.app.data.RenderTaskEntity> = emptyList()
        override suspend fun upsertProviderConfig(c: com.dramafactory.app.data.ProviderConfigEntity) {}
        override suspend fun verifiedConfig(channel: String): com.dramafactory.app.data.ProviderConfigEntity? = null
        override suspend fun upsertEpisode(e: com.dramafactory.app.data.EpisodeEntity) {}
        override suspend fun episode(id: String): com.dramafactory.app.data.EpisodeEntity? = null
        override suspend fun episodesOf(projectId: String): List<com.dramafactory.app.data.EpisodeEntity> = emptyList()
    }

    private class BrokenMovieLibraryDao : MovieLibraryDao {
        override suspend fun upsertFilmOf(film: com.dramafactory.app.data.FinishedFilmEntity): Long = 0L
        override suspend fun deleteFilmOf(episodeId: String): Int = 0
        override suspend fun deleteFilm(film: com.dramafactory.app.data.FinishedFilmEntity): Int = 0
        override suspend fun finishedFilmsOf(projectId: String): List<com.dramafactory.app.data.FinishedFilmEntity> = emptyList()
        override suspend fun finishedFilmOf(episodeId: String): com.dramafactory.app.data.FinishedFilmEntity? = null
        override suspend fun assembledEpisodeIds(projectId: String): List<String> = emptyList()
        override suspend fun allFilms(): List<com.dramafactory.app.data.FinishedFilmEntity> = emptyList()
    }

    object CrashLog {
        private fun crashFile(app: android.content.Context): File =
            File(File(app.filesDir, "crash"), "last_crash.txt")

        private fun writeCrash(app: android.content.Context, header: String, throwable: Throwable) {
            try {
                val stack = android.util.Log.getStackTraceString(throwable)
                crashFile(app).apply { parentFile?.mkdirs() }.writeText(
                    buildString {
                        appendLine("time=${System.currentTimeMillis()}")
                        appendLine(header)
                        appendLine(stack)
                    })
                // 同时写外部可读路径（/sdcard/Download/ai-drama-crash.log），便于无 root 取日志
                runCatching {
                    val ext = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "ai-drama-crash.log")
                    ext.writeText("time=${System.currentTimeMillis()}\n$header\n$stack\n\n")
                }
            } catch (_: Throwable) {}
        }

        fun installCrashLogger(context: Context) {
            val app = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                writeCrash(app, "thread=${thread.name}", throwable)
                previous?.uncaughtException(thread, throwable)
            }
        }

        fun record(context: Context, tag: String, throwable: Throwable) {
            writeCrash(context.applicationContext, "tag=$tag", throwable)
            android.util.Log.e(tag, throwable.message ?: throwable.javaClass.simpleName, throwable)
        }

        fun lastCrashLog(context: Context): String? =
            runCatching { crashFile(context.applicationContext) }
                .getOrNull()?.takeIf { it.exists() }?.readText()
    }
}