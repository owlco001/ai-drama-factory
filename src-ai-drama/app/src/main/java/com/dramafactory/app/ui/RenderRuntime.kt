package com.dramafactory.app.ui

import com.dramafactory.app.AppGraph
import com.dramafactory.core.pipeline.DefaultPipelineOrchestrator
import com.dramafactory.core.pipeline.DefaultRenderQueue

/**
 * 渲染队列运行时 —— 按集懒建/复用DefaultRenderQueue实例 + 编排器恢复入口。
 *
 * DefaultRenderQueue是「单episode单worker」形态（构造注入VideoProvider等），
 * App层按episodeId维护实例表；recoverOnBoot经queueFor回调逐集恢复。
 */
object RenderRuntime {

    @Volatile private var orchestrator: DefaultPipelineOrchestrator? = null
    private val queues = HashMap<String, DefaultRenderQueue>()

    /**
     * 取某集的渲染队列（无则创建）。downloader把videoUrl下载到app缓存目录并校验size>0。
     * ★第五轮加固：episodeId为空防御 + AppGraph未初始化时抛可读错误由调用方降级，绝不NPE。
     */
    @Synchronized
    fun queueFor(episodeId: String): DefaultRenderQueue {
        val ep = episodeId.ifBlank { "default" }
        return queues.getOrPut(ep) {
            DefaultRenderQueue(
                scope = appScope(),
                videoProvider = AppGraph.video,
                checkpointStore = AppGraph.checkpointStore,
                budgetGuard = AppGraph.budgetGuard,
                downloader = { videoUrl, shotId -> downloadClip(videoUrl, shotId) },
                projectIdOf = { e -> e.substringBeforeLast("_ep") },
            )
        }
    }

    /** UI/Service便捷入口 */
    fun queue(): DefaultRenderQueue = queueFor("default")

    /** 编排器（含恢复入口），懒建并绑定queueFor */
    @Synchronized
    fun orchestrator(): DefaultPipelineOrchestrator =
        orchestrator ?: DefaultPipelineOrchestrator(
            checkpointStore = AppGraph.checkpointStore,
            queue = null,
            queueFor = { ep -> queueFor(ep) },
        ).also { orchestrator = it }

    /** 进程重启恢复总入口：读checkpoint → repoll已提交镜 → 续跑队列（P1-6） */
    suspend fun recoverOnBoot() = orchestrator().recoverOnBoot()

    private var scopeRef: kotlinx.coroutines.CoroutineScope? = null
    internal fun bindScope(scope: kotlinx.coroutines.CoroutineScope) { scopeRef = scope }
    private fun appScope(): kotlinx.coroutines.CoroutineScope =
        // ★第五轮加固：不再error()抛异常（QueueViewModel构造期触发会直接闪退），
        // 未接线时兜底自建SupervisorJob作用域（测试/ContentProvider早期路径）。
        scopeRef ?: kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            .also { fallbackScope = it }

    @Volatile private var fallbackScope: kotlinx.coroutines.CoroutineScope? = null

    /**
     * clip下载器：写缓存目录文件，size必须>0才算completed（架构§5约束）。
     * 失败抛异常 → 队列保持SUBMITTED仅重试取回，绝不重新提交已付费任务。
     */
    private suspend fun downloadClip(videoUrl: String, shotId: String): Pair<String, Long> {
        val dir = java.io.File(cacheDir()).apply { mkdirs() }
        val f = java.io.File(dir, "$shotId.mp4")
        // 简单HTTP流式下载（java.net，无额外依赖）；失败抛异常由队列重试取回
        val conn = java.net.URL(videoUrl).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 15_000; conn.readTimeout = 60_000
            conn.inputStream.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
        } finally {
            conn.disconnect()
        }
        val size = f.length()
        check(size > 0) { "下载clip为空文件 shot=$shotId" }
        return f.absolutePath to size
    }

    private fun cacheDir(): String = System.getProperty("java.io.tmpdir") ?: "/tmp"
}
