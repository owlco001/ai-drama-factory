package com.dramafactory.desktop

import com.dramafactory.core.model.ChatRequest
import com.dramafactory.core.orchestrate.*
import com.dramafactory.core.orchestrate.DefaultAiOrchestrator.AiAsset
import com.dramafactory.core.orchestrate.DefaultAiOrchestrator.AiShot
import com.dramafactory.core.orchestrate.DefaultAiOrchestrator.AuditResult
import com.dramafactory.core.provider.AgnesProvider
import com.dramafactory.core.provider.KeyVault
import com.dramafactory.core.pipeline.DefaultBudgetGuard
import com.dramafactory.core.assemble.MovieAssembler
import com.dramafactory.core.assemble.MovieAssemblerImpl
import com.dramafactory.core.assemble.MovieAssemblerExecutor
import com.dramafactory.core.quality.AiStoryboardDirector
import com.dramafactory.core.quality.LlmAssetExtractor
import com.dramafactory.core.quality.EraDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 桌面端平台装配器（仿安卓 AppGraph）。
 * Phase A：内存存储 + 文件密钥库 + ffmpeg Process 执行器，跑通 AI 对话→开工→成片闭环。
 * 持久化(JDBC)放 Phase B。
 */
object DesktopAppGraph {
    lateinit var keyVault: KeyVault
        private set
    lateinit var aiOrchestrator: DefaultAiOrchestrator
        private set
    lateinit var textModelRouter: com.dramafactory.core.provider.TextModelRouter
        private set
    lateinit var movieAssembler: MovieAssembler
        private set

    // 内存存储（Phase B 换 JDBC）
    private val projects = ConcurrentHashMap<String, String>()   // id -> name
    private val episodes = ConcurrentHashMap<String, String>()    // epId -> script
    private val renderTasks = ConcurrentHashMap<String, RenderTaskRow>() // taskId -> row
    private val finishedFilms = ConcurrentHashMap<String, String>() // epId -> filePath

    data class RenderTaskRow(
        val taskId: String, val episodeId: String, val shotNo: Int,
        var state: String, var localFileUri: String?,
    )

    fun init() {
        if (::aiOrchestrator.isInitialized) return
        keyVault = FileKeyVault(homeDir())
        val agnes = AgnesProvider(apiKeyProvider = { keyVault.load("agnes-video") })
        textModelRouter = com.dramafactory.core.provider.DefaultTextModelRouter
        movieAssembler = MovieAssemblerImpl(executor = ProcessFfmpegExecutor())
        aiOrchestrator = DefaultAiOrchestrator(
            createProject = { name ->
                val id = "p_" + System.currentTimeMillis()
                projects[id] = name
                id
            },
            createEpisode = { projectId, scriptText ->
                val epId = "${projectId}_ep1"
                episodes[epId] = scriptText
                epId
            },
            checkModel = { modelId ->
                if (modelId.isBlank()) Result.success(Unit)
                else runCatching { runBlocking { textModelRouter.validate(modelId).getOrThrow() } }
            },
            extractAssets = { text, _ ->
                runCatching {
                    val tp = textModelRouter.resolve(textModelRouter.activeTextModelId())
                    val r = LlmAssetExtractor.extract(text) { req -> tp.chat(req) }
                    r.assets.map { a ->
                        AiAsset(assetId = "a_${System.nanoTime()}", kind = a.kind, name = a.name, prompt = a.desc)
                    }
                }
            },
            generateImage = { asset ->
                runCatching {
                    runBlocking {
                        val preset = EraDetector.presetFor("han")
                        val prompt = if (asset.kind == "character") {
                            preset.withCharacterStudioConstraints(asset.prompt)
                        } else preset.withEraConstraints(asset.prompt)
                        val neg = if (asset.kind == "character") {
                            preset.studioNegativePromptFor()
                        } else preset.negativePromptFor()
                        agnes.generateImage(
                            com.dramafactory.core.model.ImageGenRequest(prompt = prompt, negativePrompt = neg)
                        )
                    }
                }
            },
            auditAsset = { _ -> Result.success(AuditResult(passed = true)) },
            generateShots = { script, _ ->
                runCatching {
                    val tp = textModelRouter.resolve(textModelRouter.activeTextModelId())
                    val r = AiStoryboardDirector.generate(script) { req -> tp.chat(req) }
                    r.shots.map { s -> AiShot(s.shotNo, s.action ?: "", s.dialogue) }
                }
            },
            enqueueRender = { episodeId, shots ->
                var n = 0
                shots.forEach { shot ->
                    val taskId = "rt_${episodeId}_${shot.shotNo}"
                    renderTasks[taskId] = RenderTaskRow(taskId, episodeId, shot.shotNo, "COMPLETED",
                        mockClip(episodeId, shot.shotNo))
                    n++
                }
                // 触发异步真实渲染（agnes 视频）—— 简化：标记 COMPLETED 后由轮询合成
                Result.success(n)
            },
            writeCheckpoint = { _, _, _, _, _, _ -> },
            readCheckpoint = { null },
        )
    }

    // Phase A 模拟镜头视频文件（真实渲染需 agnes 视频端点，桌面端后续接）
    private fun mockClip(episodeId: String, shotNo: Int): String {
        val dir = File(homeDir(), "clips").apply { if (!exists()) mkdirs() }
        val f = File(dir, "${episodeId}_$shotNo.mp4")
        if (!f.exists()) f.writeBytes(ByteArray(0))
        return f.absolutePath
    }

    fun renderTasksOf(episodeId: String): List<RenderTaskRow> =
        renderTasks.values.filter { it.episodeId == episodeId }

    /** 自动合成成片（复用 MovieAssembler） */
    suspend fun composeFilmIfReady(episodeId: String): File? = withContext(Dispatchers.IO) {
        val tasks = renderTasksOf(episodeId).filter { it.state == "COMPLETED" && it.localFileUri != null }
        if (tasks.isEmpty()) return@withContext null
        val outDir = File(homeDir(), "movies").apply { if (!exists()) mkdirs() }
        val out = File(outDir, "$episodeId.mp4")
        val clips = tasks.mapNotNull { it.localFileUri }.map { File(it) }
        runCatching { movieAssembler.assemble(clips, out) }.getOrNull()?.let {
            finishedFilms[episodeId] = out.absolutePath
            out
        }
    }

    fun homeDir(): File {
        val d = File(System.getProperty("user.home"), ".ai-drama-factory")
        if (!d.exists()) d.mkdirs()
        return d
    }
}
