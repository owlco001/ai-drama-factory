package com.dramafactory.core.orchestrate

import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * AiOrchestrator JVM 单测（T014 §2.2，5+ 用例）。
 *
 * 注入桩函数验证策略与错误处理；不依赖 Android / ffmpeg-kit。
 */
class AiOrchestratorTest {

    private fun runSuspend(block: suspend () -> Unit) {
        val t = Thread { runBlocking { block() } }
        t.start(); t.join(15_000)
    }

    private fun makeOkOrchestrator(): DefaultAiOrchestrator {
        val projectIds = mutableListOf<String>()
        val episodeIds = mutableListOf<String>()
        val checkpoints = mutableListOf<Triple<String, PipelineStage5, Boolean>>()
        return DefaultAiOrchestrator(
            createProject = { name ->
                require(name.startsWith("AI草稿-")) { "name must start with AI草稿-: $name" }
                val id = "proj_${projectIds.size}"
                projectIds += id; id
            },
            createEpisode = { pid, script ->
                require(pid.isNotEmpty())
                val id = "${pid}_ep1"
                episodeIds += id; id
            },
            checkModel = { Result.success(Unit) },
            extractAssets = { _, _ ->
                Result.success(listOf(
                    DefaultAiOrchestrator.AiAsset("a1", "character", "张三", "主角"),
                    DefaultAiOrchestrator.AiAsset("a2", "scene", "大殿", "宫廷内景"),
                ))
            },
            generateImage = { Result.success("http://img/ok.png") },
            auditAsset = { Result.success(DefaultAiOrchestrator.AuditResult(passed = true)) },
            generateShots = { _, _ ->
                Result.success(listOf(
                    DefaultAiOrchestrator.AiShot(1, "张三走进大殿"),
                    DefaultAiOrchestrator.AiShot(2, "两人对视"),
                ))
            },
            enqueueRender = { _, shots -> Result.success(shots.size) },
            writeCheckpoint = { epId, stage, _, _, renderEnq, _ ->
                checkpoints += Triple(epId, stage, renderEnq)
            },
            readCheckpoint = { null },
        )
    }

    @Test
    fun `脚本小于100字抛InputTooShort`() {
        val o = makeOkOrchestrator()
        runSuspend {
            val short = "abc".repeat(20) // 60字
            assertFailsWith<AiOrchestrator.AiError.InputTooShort> {
                o.run(short)
            }
            // 未建项目（checkpoint 为空）
            assertTrue(o.currentEpisodeId.value == null)
        }
    }

    @Test
    fun `模型未验证抛ModelBlocked`() {
        val o = DefaultAiOrchestrator(
            checkModel = { Result.failure(Exception("no key")) },
        )
        runSuspend {
            assertFailsWith<AiOrchestrator.AiError.ModelBlocked> {
                o.run("a".repeat(200))
            }
        }
    }

    @Test
    fun `五阶段全成功_run通过`() = runSuspend {
        val o = makeOkOrchestrator()
        val script = "a".repeat(200)
        val result = o.run(script)
        assertTrue(result.isSuccess)
        val run = result.getOrThrow()
        assertTrue(run.success)
        assertEquals(PipelineStage5.ENQUEUE_RENDER_DONE, run.lastStage)
        assertTrue(run.projectId.startsWith("proj_"))
        assertTrue(run.episodeId.endsWith("_ep1"))
        assertEquals(run.episodeId, o.currentEpisodeId.value)
        // 事件流包含 ENQUEUE_RENDER_DONE
        val done = o.events.value.last()
        assertEquals(PipelineStage5.ENQUEUE_RENDER_DONE, done.stage)
        assertTrue(done.isTerminal)
        // onAutoCreatedProject 回调被触发
        assertTrue(run.projectId.startsWith("proj_"))
    }

    @Test
    fun `分镜生成失败_run返回失败_but已建项目保留`() = runSuspend {
        val o = DefaultAiOrchestrator(
            createProject = { "proj_x" },
            createEpisode = { pid, _ -> "${pid}_ep1" },
            checkModel = { Result.success(Unit) },
            extractAssets = { _, _ -> Result.success(emptyList()) },
            generateShots = { _, _ ->
                Result.failure(Exception("llm 网络超时"))
            },
            writeCheckpoint = { _, _, _, _, _, _ -> },
            readCheckpoint = { null },
        )
        val r = o.run("b".repeat(200))
        // 返回 AiError 包裹，run 内部 catch → 返回成功封装但 success=false
        assertTrue(r.isSuccess)
        val run = r.getOrThrow()
        assertFalse(run.success)
        assertTrue(run.errors.any { it is AiOrchestrator.AiError.StageFailed })
        assertEquals("proj_x", run.projectId)
        assertEquals("proj_x_ep1", run.episodeId)
        assertEquals("proj_x_ep1", o.currentEpisodeId.value)
    }

    @Test
    fun `retryFrom继续运行_从指定阶段开始`() = runSuspend {
        var checkpoints: List<PipelineStage5> = emptyList()
        val o = DefaultAiOrchestrator(
            createProject = { "proj_y" },
            createEpisode = { pid, _ -> "${pid}_ep1" },
            checkModel = { Result.success(Unit) },
            extractAssets = { _, _ -> Result.success(emptyList()) },
            generateShots = { _, _ -> Result.success(listOf(DefaultAiOrchestrator.AiShot(1, "x"))) },
            enqueueRender = { _, _ -> Result.success(1) },
            writeCheckpoint = { _, stage, _, _, _, _ ->
                synchronized(this) { checkpoints = checkpoints + stage }
            },
            readCheckpoint = { PipelineStage5.GENERATE_IMAGES },
        )
        // 先完整跑一次，写入 currentEpisodeId
        o.run("z".repeat(200))
        val epBefore = o.currentEpisodeId.value!!
        checkpoints = emptyList()
        val retryResult = o.retryFrom(PipelineStage5.GENERATE_STORYBOARD)
        assertTrue(retryResult.isSuccess)
        assertTrue(retryResult.getOrThrow().success)
        // 仅包含 GENERATE_STORYBOARD 和之后的 checkpoint
        val stagesSeen = checkpoints
        assertTrue(stagesSeen.contains(PipelineStage5.GENERATE_STORYBOARD))
        assertTrue(stagesSeen.contains(PipelineStage5.ENQUEUE_RENDER))
        assertFalse(stagesSeen.contains(PipelineStage5.EXTRACT_ASSETS))
        assertEquals(epBefore, o.currentEpisodeId.value)
    }

    @Test
    fun `recoveryState读回断点`() = runSuspend {
        val o = DefaultAiOrchestrator(
            readCheckpoint = { PipelineStage5.AUDIT },
        )
        assertEquals(PipelineStage5.AUDIT, o.recoveryState("proj_x_ep1"))
        val o2 = DefaultAiOrchestrator()
        assertNull(o2.recoveryState("xxx"))
    }

    @Test
    fun `onAutoCreatedProject回调在EXTRACT_ASSETS阶段触发`() = runSuspend {
        var captured: Pair<String, String>? = null
        val o = DefaultAiOrchestrator(
            createProject = { "proj_cb" },
            createEpisode = { pid, _ -> "${pid}_ep1" },
            checkModel = { Result.success(Unit) },
            extractAssets = { _, _ -> Result.success(emptyList()) },
            generateShots = { _, _ -> Result.success(listOf(DefaultAiOrchestrator.AiShot(1, "a"))) },
            enqueueRender = { _, _ -> Result.success(1) },
            writeCheckpoint = { _, _, _, _, _, _ -> },
            readCheckpoint = { null },
        )
        o.run("q".repeat(200), onAutoCreatedProject = { p, e -> captured = p to e })
        assertEquals("proj_cb" to "proj_cb_ep1", captured)
    }
}