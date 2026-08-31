package com.dramafactory.core

import com.dramafactory.core.model.ConnectionInfo
import com.dramafactory.core.model.ModelSpec
import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ShotMeta
import com.dramafactory.core.model.ShotState
import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.pipeline.DefaultBudgetGuard
import com.dramafactory.core.pipeline.DefaultRenderQueue
import com.dramafactory.core.provider.VideoProvider
import com.dramafactory.core.quality.StoryboardGate
import com.dramafactory.core.storage.InMemoryCheckpointStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v1.8.0 质量三闸接线验证（对齐 pavo fidelity_gate + storyboard 六铁律）：
 * 1. 参考图套装真注入 inputImages（第2项回归）
 * 2. 提交前保真闸拦截 → 不提交 + 标 FAILED
 * 3. 整集六铁律未过 → 整集暂停、不提交
 */
class QualityGateWiringTest {

    private class CapturingVideo : VideoProvider {
        val requests = mutableListOf<VideoSubmitRequest>()
        override val id = "fake"
        override suspend fun validateKey(key: String) = Result.success(ConnectionInfo(true))
        override fun listModels() = emptyList<ModelSpec>()
        override suspend fun submitVideo(req: VideoSubmitRequest): String { requests += req; return "vid-${req.shotId}" }
        private val polls = mutableMapOf<String, Int>()
        override suspend fun pollResult(taskId: String): PollResult =
            (polls[taskId] ?: 0).let { n -> polls[taskId] = n + 1; if (n >= 1) PollResult.Completed("http://x/$taskId.mp4") else PollResult.InProgress(30) }
    }

    private fun buildQueue(
        scope: CoroutineScope,
        video: CapturingVideo = CapturingVideo(),
        configure: DefaultRenderQueue.() -> Unit = {},
    ): Pair<DefaultRenderQueue, InMemoryCheckpointStore> {
        val store = InMemoryCheckpointStore()
        val guard = DefaultBudgetGuard(mutableMapOf("p1" to 50))
        val q = DefaultRenderQueue(
            scope = scope, videoProvider = video, checkpointStore = store, budgetGuard = guard,
            downloader = { _, s -> "file://$s" to 2048 }, pollIntervalMs = { 0 },
            shotPromptResolver = { Triple("", "", "") }, projectIdOf = { "p1" },
        )
        q.configure()
        return q to store
    }

    @Test
    fun `v1_8_0_参考图套装真注入inputImages`() = runBlocking {
        withTimeout(10_000) {
            val scope = CoroutineScope(Dispatchers.Default)
            val video = CapturingVideo()
            val (q, store) = buildQueue(scope, video) { shotAssetImageResolver = { listOf("ref1", "ref2", "ref3", "ref4") } }
            q.enqueueEpisode("ep1", listOf(ShotMeta("s1", "ep1", "")))
            while (store.getEpisode("ep1")?.shots?.singleOrNull()?.state == ShotState.PENDING) delay(50)
            assertEquals(listOf("ref1", "ref2", "ref3", "ref4"), video.requests.single().inputImages,
                "v1.7.21 生成的 4 张参考图必须进入视频生成 inputImages（不是死资产）")
            scope.cancel()
        }
    }

    @Test
    fun `v1_8_0_保真闸拦截不提交并标FAILED`() = runBlocking {
        withTimeout(10_000) {
            val scope = CoroutineScope(Dispatchers.Default)
            val video = CapturingVideo()
            val (q, store) = buildQueue(scope, video) {
                fidelityGateEntryProvider = { shotId ->
                    // 时长缺失(=0) 触发 FidelityGate A.3 gate_duration_missing → blocked
                    StoryboardGate.Entry(
                        shotId = shotId, index = 1,
                        panel = StoryboardGate.Panel(duration = 0.0),
                        beatRef = "beat_01", beatIndex = 1, associateAssetIds = listOf("a1"),
                    )
                }
                catalogApprovedIdsProvider = { emptySet() }
            }
            q.enqueueEpisode("ep1", listOf(ShotMeta("s1", "ep1", "")))
            while (store.getEpisode("ep1")?.shots?.singleOrNull()?.state == ShotState.PENDING) delay(50)
            assertTrue(video.requests.none { it.shotId == "s1" }, "保真闸拦截：该镜未提交视频（不烧钱出废片）")
            val shot = store.getEpisode("ep1")!!.shots.single()
            assertEquals(ShotState.FAILED, shot.state)
            assertTrue(shot.failReason?.contains("fidelity_blocked") == true, "失败原因标明保真闸拦截: ${shot.failReason}")
            scope.cancel()
        }
    }

    @Test
    fun `v1_8_0_整集六铁律未过整集暂停不提交`() = runBlocking {
        withTimeout(5000) {
            val scope = CoroutineScope(Dispatchers.Default)
            val video = CapturingVideo()
            val (q, _) = buildQueue(scope, video) { storyboardBlockedProvider = { true } }
            q.enqueueEpisode("ep1", listOf(ShotMeta("s1", "ep1", ""), ShotMeta("s2", "ep1", "")))
            while (q.pauseReason != "storyboard_gate") delay(50)
            assertTrue(video.requests.isEmpty(), "整集六铁律未过：不提交任何镜")
            scope.cancel()
        }
    }
}
