// 渲染队列端到端（假Provider）+ 中文配音注入 + FFmpeg三级策略测试
package com.dramafactory.core

import com.dramafactory.core.assemble.FfmpegAssembler
import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ShotMeta
import com.dramafactory.core.model.ShotState
import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.pipeline.DefaultBudgetGuard
import com.dramafactory.core.pipeline.DefaultRenderQueue
import com.dramafactory.core.provider.ChineseAudioInjector
import com.dramafactory.core.storage.InMemoryCheckpointStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.*

class QueueAssemblerTest {

    /** 假VideoProvider：记录submit调用，轮询两拍后完成 */
    private class FakeVideo : com.dramafactory.core.provider.VideoProvider {
        override val id = "fake"
        val submitted = mutableListOf<String>()
        var fail429Once = false
        override suspend fun validateKey(key: String) = Result.success(com.dramafactory.core.model.ConnectionInfo(true))
        override fun listModels() = emptyList<com.dramafactory.core.model.ModelSpec>()
        override suspend fun submitVideo(req: VideoSubmitRequest): String {
            submitted += req.shotId; return "vid-${req.shotId}"
        }
        private var polls = mutableMapOf<String, Int>()
        override suspend fun pollResult(taskId: String): PollResult =
            (polls[taskId] ?: 0).let { n -> polls[taskId] = n + 1; if (n >= 1) PollResult.Completed("http://x/$taskId.mp4") else PollResult.InProgress(30) }
    }

    @Test
    fun `队列全链路_提交即落库submitted_零重复提交`() = runBlocking {
        withTimeout(10_000) {
            val scope = CoroutineScope(Dispatchers.Default)
            val fake = FakeVideo()
            val store = InMemoryCheckpointStore()
            val guard = DefaultBudgetGuard(mutableMapOf("p1" to 50))
            var downloaded = 0L
            val queue = DefaultRenderQueue(
                scope = scope, videoProvider = fake, checkpointStore = store,
                budgetGuard = guard,
                downloader = { url, shotId -> downloaded++; "file://$shotId.mp4" to 2048 },
                pollIntervalMs = { 0 },
                shotPromptResolver = { Triple("他说：站住", "", "他冲上前") },
                projectIdOf = { "p1" },
            )
            queue.enqueueEpisode("ep1", listOf(ShotMeta("s1", "ep1", ""), ShotMeta("s2", "ep1", "")))
            // 等待队列跑完
            while (!queue.state.value.let { !it.running && it.completedShots == 2 }) kotlinx.coroutines.delay(50)
            assertEquals(listOf("s1", "s2"), fake.submitted, "每镜恰一次提交")
            val cp = store.getEpisode("ep1")!!
            assertEquals(2, cp.completedCount)
            cp.shots.forEach { assertEquals(ShotState.COMPLETED, it.state) }
            assertTrue(cp.shots.all { it.fileSize > 0 }, "size>0才算completed")
            scope.cancel()
        }
    }

    @Test
    fun `预算超限暂停队列不再提交`() = runBlocking {
        withTimeout(10_000) {
            val scope = CoroutineScope(Dispatchers.Default)
            val fake = FakeVideo()
            val store = InMemoryCheckpointStore()
            val guard = DefaultBudgetGuard(mutableMapOf("p1" to 1)) // 上限1条
            val queue = DefaultRenderQueue(
                scope = scope, videoProvider = fake, checkpointStore = store, budgetGuard = guard,
                downloader = { _, s -> "file://$s" to 100 }, pollIntervalMs = { 0 },
                projectIdOf = { "p1" },
            )
            queue.enqueueEpisode("ep1", listOf(ShotMeta("s1", "ep1", ""), ShotMeta("s2", "ep1", "")))
            while (queue.state.value.pausedReason != "budget_exceeded") kotlinx.coroutines.delay(50)
            Thread.sleep(200)
            assertEquals(listOf("s1"), fake.submitted, "第2镜被预算闸门拦下，未提交")
            queue.resume(confirmedByUser = true)   // 用户确认加量后放行
            guard.setLimit("p1", 5)                // 确认=提高上限
            while (!queue.state.value.let { !it.running && it.completedShots == 2 }) kotlinx.coroutines.delay(50)
            assertEquals(listOf("s1", "s2"), fake.submitted)
            scope.cancel()
        }
    }

    // ---------------- 中文配音注入（决议Q9） ----------------

    @Test
    fun `中文台词主导开头加显式普通话指令`() {
        val p = ChineseAudioInjector.buildShotPrompt(dialogue = "你终于来了。", narration = "", action = "他转身望向门口")
        assertTrue(p.startsWith("你终于来了。"), "中文台词开头主导")
        assertTrue(p.endsWith(ChineseAudioInjector.MANDARIN_SUFFIX), "末尾追加显式指令")
        assertEquals(p, ChineseAudioInjector.inject(p), "幂等：不重复叠加")
        assertEquals(ChineseAudioInjector.MANDARIN_SUFFIX, ChineseAudioInjector.inject(""), "空prompt兜底")
    }

    @Test
    fun `中文主导启发式判定`() {
        assertTrue(ChineseAudioInjector.chineseLeading("他抬头看天，远处传来马蹄声。"))
        assertFalse(ChineseAudioInjector.chineseLeading("He looks up at the sky"))
    }

    // ---------------- FFmpeg三级拼接 ----------------

    @Test
    fun `快路径concat copy成功`() {
        val dir = createTempDir()
        val clips = (1..3).map { File(dir, "$it.mp4").apply { writeBytes(ByteArray(16)) } }
        val out = File(dir, "final.mp4")
        val asm = FfmpegAssembler(executor = { args ->
            if (args.contains("-c") && args.contains("copy")) { out.writeBytes(ByteArray(1024)); 0 to "" } else 1 to ""
        })
        val r = asm.assemble(clips, out)
        assertTrue(r is FfmpegAssembler.AssembleResult.Success && r.strategy == FfmpegAssembler.Strategy.CONCAT_COPY)
    }

    @Test
    fun `快路径失败降级归一化再失败分段导出`() {
        val dir = createTempDir()
        val clips = (1..9).map { File(dir, "$it.mp4").apply { writeBytes(ByteArray(8)) } }
        val out = File(dir, "final.mp4")
        val partsCreated = mutableListOf<File>()
        val asm = FfmpegAssembler(executor = { args ->
            when {
                args.contains("filter_complex") -> 1 to "mediacodec unavailable"   // 归一化失败
                args.contains("-part") || args.any { it.contains("_part") } -> {
                    val dst = args[args.indexOfFirst { it.contains("_part") }]
                    File(dst).writeBytes(ByteArray(64)); partsCreated.add(File(dst)); 0 to ""
                }
                else -> 1 to "copy failed"
            }
        })
        val r = asm.assemble(clips, out)
        assertTrue(r is FfmpegAssembler.AssembleResult.Segmented, "三级降级到分段导出")
        assertEquals(2, r.parts.size, "9镜按每8镜分段=2段")
    }

    private fun createTempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "dfa-" + System.nanoTime()).apply { mkdirs() }
}
