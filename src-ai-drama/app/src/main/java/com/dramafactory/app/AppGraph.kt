package com.dramafactory.app

import android.content.Context
import android.util.Log
import com.dramafactory.app.data.DramaDatabase
import com.dramafactory.app.data.RoomCheckpointStore
import com.dramafactory.app.security.AndroidKeyVault
import com.dramafactory.core.pipeline.DefaultBudgetGuard
import com.dramafactory.core.provider.AgnesProvider
import com.dramafactory.core.provider.BudgetGuard
import com.dramafactory.core.provider.CheckpointStore
import com.dramafactory.core.provider.KeyVault
import com.dramafactory.core.provider.TextProvider
import com.dramafactory.core.provider.ImageProvider
import java.io.File

/**
 * App级依赖图 —— UI/ViewModel/Service共享的引擎单例。
 *
 * 第四轮真机加固：各init步骤独立try-catch容错，任一步失败不阻断启动；
 * KeyVault走降级链；极端情况下以内存实现兜底，保证App永不因初始化闪退。
 */
object AppGraph {

    const val CONFIG_VIDEO = "agnes-video"    // configId：视频通道Key
    const val CONFIG_TEXT = "agnes-text"
    const val CONFIG_IMAGE = "agnes-image"

    lateinit var keyVault: KeyVault; private set
    lateinit var checkpointStore: CheckpointStore; private set
    lateinit var agnes: AgnesProvider; internal set
    val video get() = agnes            // VideoProvider
    val text: TextProvider get() = agnes
    val image: ImageProvider get() = agnes
    lateinit var budgetGuard: DefaultBudgetGuard; private set
    lateinit var dao: com.dramafactory.app.data.DramaDao; internal set

    @Volatile private var initialized = false

    @Volatile private var appContextRef: Context? = null
    /** UI层安全取Application Context（未init返回null） */
    fun appContext(): Context? = appContextRef
    /** 引擎是否已完成初始化（第十轮：LLM提取等网络功能的前置判断用） */
    val isInitialized: Boolean get() = initialized

    /** Application.onCreate调用一次；重复调用幂等（ContentProvider/测试环境容错） */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            appContextRef = app   // 第十一轮：UI层取Context（参考图拷贝等）
            // ① KeyVault：降级链（StrongBox→Keystore→明文prefs→内存），不抛异常
            keyVault = runCatching { AndroidKeyVault.create(app) as KeyVault }
                .getOrElse { e ->
                    android.util.Log.e("AppGraph", "keyvault init failed", e)
                    com.dramafactory.core.storage.InMemoryKeyVault()
                }
            // ② Room数据库：独立容错（磁盘满/损坏时重建）
            try {
                val db = DramaDatabase.get(app)
                dao = db.dao()
                checkpointStore = RoomCheckpointStore(dao)
            } catch (t: Throwable) {
                Log.e("AppGraph", "room init failed, fallback in-memory", t)
                dao = BrokenDramaDao()
                checkpointStore = com.dramafactory.core.storage.InMemoryCheckpointStore()
                // 第九轮修复：记录根因到 crash 文件 + 内存标记，供 UI 横幅展示
                runCatching {
                    File(File(app.filesDir, "crash"), "last_crash.txt").apply { parentFile?.mkdirs() }
                        .writeText("time=${System.currentTimeMillis()}\ntag=room-init-failed\n" +
                            android.util.Log.getStackTraceString(t))
                }
                roomInitError = t.message ?: t.javaClass.name
            }
            agnes = AgnesProvider(apiKeyProvider = { keyVault.load(CONFIG_VIDEO) })
            budgetGuard = DefaultBudgetGuard()
            initialized = true
        }
    }

    /** true=Room初始化失败，当前为空操作DAO（数据功能不可用）。UI据此显示诊断横幅。 */
    var roomInitError: String? = null; private set

    /** 初始化失败兜底DAO：全部空操作，保证UI可打开、设置页可配置（数据功能提示不可用） */
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

        /** 全局未捕获异常写本地日志文件（下次启动可读，便于真机排查） */
        fun installCrashLogger(context: Context) {
            val app = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                writeCrash(app, "thread=${thread.name}", throwable)
                previous?.uncaughtException(thread, throwable)
            }
        }

        /** 非致命异常记录到同一 crash 日志文件（不终止进程）。第八轮：拍摄闪退等UI层异常排查用 */
        fun record(context: Context, tag: String, throwable: Throwable) {
            writeCrash(context.applicationContext, "tag=$tag", throwable)
            android.util.Log.e(tag, throwable.message ?: throwable.javaClass.simpleName, throwable)
        }

        /** 读取上次崩溃日志（诊断页/排查用），null=无记录 */
        fun lastCrashLog(context: Context): String? =
            runCatching { crashFile(context.applicationContext) }
                .getOrNull()?.takeIf { it.exists() }?.readText()
    }
}
