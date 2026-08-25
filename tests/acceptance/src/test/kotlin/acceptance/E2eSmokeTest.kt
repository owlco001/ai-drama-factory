// MVP验收E2E冒烟：多集渲染 + 中断恢复零重复付费 + 成片拼接桩（纯JVM，FakeProvider，不改src）
package acceptance

import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ShotMeta
import com.dramafactory.core.model.ShotState
import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.assemble.FfmpegAssembler
import com.dramafactory.core.pipeline.DefaultBudgetGuard
import com.dramafactory.core.pipeline.DefaultPipelineOrchestrator
import com.dramafactory.core.pipeline.DefaultRenderQueue
import com.dramafactory.core.storage.InMemoryCheckpointStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.*

class E2eSmokeTest {

    private class FakeVideo : com.dramafactory.core.provider.VideoProvider {
        override val id = "fake"
        val submits = ConcurrentLinkedQueue<String>()
        override suspend fun validateKey(key: String) =
            Result.success(com.dramafactory.core.model.ConnectionInfo(true))
        override fun listModels() = emptyList<com.dramafactory.core.model.ModelSpec>()
        override suspend fun submitVideo(req: VideoSubmitRequest): String {
            submits += req.shotId
            return "vid-${req.shotId}"
        }
        override suspend fun pollResult(taskId: String) = PollResult.Completed("http://fake/$taskId.mp4")
    }

    private fun newQueue(store: InMemoryCheckpointStore, provider: FakeVideo, gate: CompletableDeferred<Unit>? = null):
            Pair<DefaultRenderQueue, CoroutineScope> {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val q = DefaultRenderQueue(
            scope = scope,
            videoProvider = provider,
            checkpointStore = store,
            budgetGuard = DefaultBudgetGuard(mapOf("p1" to 100)),
            // 下载b2时挂起，制造「SUBMITTED且video_id已落库」的杀进程窗口
            downloader = { _, s -> if (s == "b2") gate?.await(); "file:///clips/$s.mp4" to 1024L },
            pollIntervalMs = { 0 },
            projectIdOf = { "p1" },
        )
        return q to scope
    }

    @Test
    fun `多集渲染_中断恢复_零重复付费_成片拼接桩`() = runBlocking {
        val store = InMemoryCheckpointStore()
        val provider = FakeVideo()
        val gate = CompletableDeferred<Unit>()
        val (queueA, scopeA) = newQueue(store, provider)
        val (queueB, scopeB) = newQueue(store, provider, gate)
        val epA = listOf("a1","a2","a3").map { ShotMeta(it, "epA", "台词") }
        val epB = (1..24).map { ShotMeta("b$it", "epB", "台词") }   // PRD一集24镜

        // —— epA 正常整集渲染完成 ——
        queueA.enqueueEpisode("epA", epA)
        withTimeout(15_000) {
            while (store.getEpisode("epA")!!.completedCount < 3) delay(10)
        }

        // —— epB 渲染；b2下载挂起时模拟kill -9（此刻b2=SUBMITTED且video_id已落库）——
        queueB.enqueueEpisode("epB", epB)
        withTimeout(15_000) {
            while (store.getEpisode("epB")!!.byId("b2")!!.state != ShotState.SUBMITTED) delay(5)
        }
        val submitsAtKill = provider.submits.size
        assertEquals(5, submitsAtKill, "杀进程时恰提交了a1-a3+b1+b2共5次，实际=$submitsAtKill")
        scopeA.cancel(); scopeB.cancel()   // 进程死亡：所有worker协程终止

        // —— 新世界从checkpoint（模拟磁盘）recoverOnBoot 多集恢复 ——
        val (queueB2, _) = newQueue(store, provider)
        val orchestrator = DefaultPipelineOrchestrator(store, queue = null,
            queueFor = { id -> if (id == "epB") queueB2 else null })
        gate.complete(Unit)  // 释放下载挂起（新世界里下载直接成功）
        orchestrator.recoverOnBoot()
        withTimeout(30_000) {
            while (store.getEpisode("epB")!!.shots.any { it.state != ShotState.COMPLETED }) delay(20)
        }

        // ★ 零重复付费断言：27镜恰27次提交，无任何shotId重复
        assertEquals(27, provider.submits.size, "总提交次数应为27（含中断恢复），实际=${provider.submits.size}")
        assertEquals(27, provider.submits.toSet().size, "无任何镜被重复提交→零重复付费")

        // —— 成片拼接桩：FfmpegAssembler concat快路径（executor桩）——
        val clips = (1..24).map { File.createTempFile("clip", ".mp4").apply { writeBytes(ByteArray(64)) } }
        val out = File.createTempFile("final", ".mp4").apply { writeBytes(ByteArray(64)) } // 桩：模拟ffmpeg产出非空
        val assembler = FfmpegAssembler(executor = { _ -> 0 to "" }, isAndroid = false)
        val result = assembler.assemble(clips, out)
        assertTrue(result is FfmpegAssembler.AssembleResult.Success &&
            result.strategy == FfmpegAssembler.Strategy.CONCAT_COPY, "24镜应走concat -c copy快路径，实际=$result")
        println("[E2E] epA=3/3 COMPLETED; epB=24/24 COMPLETED; 总提交=27(唯一); 拼接策略=concat-copy OK")
    }
}
