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
    lateinit var agnes: AgnesProvider; private set
    val video get() = agnes            // VideoProvider
    val text: TextProvider get() = agnes
    val image: ImageProvider get() = agnes
    lateinit var budgetGuard: DefaultBudgetGuard; private set
    lateinit var dao: com.dramafactory.app.data.DramaDao; private set

    @Volatile private var initialized = false

    /** Application.onCreate调用一次；重复调用幂等（ContentProvider/测试环境容错） */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
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
            }
            agnes = AgnesProvider(apiKeyProvider = { keyVault.load(CONFIG_VIDEO) })
            budgetGuard = DefaultBudgetGuard()
            initialized = true
        }
    }

    /** 初始化失败兜底DAO：全部空操作，保证UI可打开、设置页可配置（数据功能提示不可用） */
    private class BrokenDramaDao : com.dramafactory.app.data.DramaDao {
        override suspend fun upsertProject(p: com.dramafactory.app.data.ProjectEntity) {}
        override suspend fun listProjects(): List<com.dramafactory.app.data.ProjectEntity> = emptyList()
        override suspend fun deleteProject(id: String) {}
        override suspend fun upsertAsset(a: com.dramafactory.app.data.AssetEntity) {}
        override suspend fun assetsOf(projectId: String, kind: String): List<com.dramafactory.app.data.AssetEntity> = emptyList()
        override suspend fun setReviewState(assetId: String, state: String) {}
        override suspend fun upsertShot(s: com.dramafactory.app.data.ShotEntity) {}
        override suspend fun shotsOf(episodeId: String): List<com.dramafactory.app.data.ShotEntity> = emptyList()
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
    }

    object CrashLog {
        /** 全局未捕获异常写本地日志文件（下次启动可读，便于真机排查） */
        fun installCrashLogger(context: Context) {
            val app = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val dir = File(app.filesDir, "crash").apply { mkdirs() }
                    File(dir, "last_crash.txt").writeText(
                        buildString {
                            appendLine("time=${System.currentTimeMillis()}")
                            appendLine("thread=${thread.name}")
                            appendLine(android.util.Log.getStackTraceString(throwable))
                        })
                } catch (_: Throwable) {}
                previous?.uncaughtException(thread, throwable)
            }
        }

        /** 读取上次崩溃日志（诊断页/排查用），null=无记录 */
        fun lastCrashLog(context: Context): String? =
            runCatching { File(File(context.filesDir, "crash"), "last_crash.txt") }
                .getOrNull()?.takeIf { it.exists() }?.readText()
    }
}
