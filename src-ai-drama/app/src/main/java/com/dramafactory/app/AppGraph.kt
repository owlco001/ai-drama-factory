package com.dramafactory.app

import android.content.Context
import android.util.Log
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

    /** T014：端上成片合成器（MovieAssembler，app 模块初始化，注入 ffmpeg-kit 5.1） */
    var movieAssembler: MovieAssembler = EmptyMovieAssembler; internal set

    /** T014：AI 全托管编排器（接 AppGraph 真实依赖） */
    lateinit var aiOrchestrator: DefaultAiOrchestrator; internal set

    /** T014：文本模型路由（Agnes/DeepSeek 双模型并存） */
    lateinit var textModelRouter: com.dramafactory.app.ui.TextModelRouter; internal set
    lateinit var textModelStore: com.dramafactory.app.ui.TextModelStore; internal set

    @Volatile private var initialized = false
    @Volatile private var appContextRef: Context? = null
    fun appContext(): Context? = appContextRef
    val isInitialized: Boolean get() = initialized

    private val ioScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            appContextRef = app
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
            agnes = AgnesProvider(apiKeyProvider = { keyVault.load(CONFIG_VIDEO) })
            budgetGuard = DefaultBudgetGuard()

            textModelStore = com.dramafactory.app.ui.InMemoryTextModelStore(keyVault = keyVault)
            textModelRouter = com.dramafactory.app.ui.DefaultTextModelRouter
            com.dramafactory.app.ui.DefaultTextModelRouter.store = textModelStore

            try {
                movieAssembler = MovieAssemblerImpl(executor = androidFfmpegKitExecutor())
            } catch (t: Throwable) {
                Log.e("AppGraph", "movieAssembler init failed", t)
            }

            // T014：AI 全托管编排器 —— 内部用 ioScope 承载 suspend 调用
            aiOrchestrator = DefaultAiOrchestrator(
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
                        val r = com.dramafactory.core.quality.LlmAssetExtractor.extract(text) { req ->
                            agnes.chat(req)
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
                            val preset = com.dramafactory.core.quality.EraDetector.presetFor("han")
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
                            agnes.generateImage(
                                com.dramafactory.core.model.ImageGenRequest(
                                    prompt = prompt, negativePrompt = neg))
                        }
                    }
                },
                auditAsset = { _ ->
                    Result.success(DefaultAiOrchestrator.AuditResult(passed = true))
                },
                generateShots = { script, _ ->
                    runCatching {
                        val r = com.dramafactory.core.quality.AiStoryboardDirector.generate(script) { req ->
                            agnes.chat(req)
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
                crashFile(app).apply { parentFile?.mkdirs() }.writeText(
                    buildString {
                        appendLine("time=${System.currentTimeMillis()}")
                        appendLine(header)
                        appendLine(android.util.Log.getStackTraceString(throwable))
                    })
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