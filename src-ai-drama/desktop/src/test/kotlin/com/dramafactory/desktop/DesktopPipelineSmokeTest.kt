package com.dramafactory.desktop

import com.dramafactory.core.model.ChatRequest
import com.dramafactory.core.model.ChatResponse
import com.dramafactory.core.orchestrate.*
import com.dramafactory.core.orchestrate.DefaultAiOrchestrator.AiAsset
import com.dramafactory.core.orchestrate.DefaultAiOrchestrator.AiShot
import com.dramafactory.core.orchestrate.DefaultAiOrchestrator.AuditResult
import com.dramafactory.core.provider.TextProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

/** 桌面端冒烟：验证 core-engine 流水线在 JVM 桌面环境能完整跑通（假 provider，无需网络/key） */
class DesktopPipelineSmokeTest {

    private class FakeTextProvider : TextProvider {
        val ASSETS_JSON = """[{"kind":"character","name":"主角","desc":"古装少年侠客"},{"kind":"scene","name":"客栈","desc":"宋代木构酒楼"}]"""
        val SHOTS_JSON = """{"shots":[{"shot_no":1,"action":"主角踏入客栈环视","dialogue":"店家，来壶酒","narration":"","duration_seconds":6,"characters":["主角"],"beat_ref":"B01"},{"shot_no":2,"action":"主角落座擦剑","dialogue":"","narration":"夜色渐深","duration_seconds":5,"characters":["主角"],"beat_ref":"B02"}]}"""
        override val id: String = "fake"
        override suspend fun chat(req: ChatRequest): ChatResponse {
            val joined = req.messages.joinToString("\n") { it.content }
            val out = when {
                joined.contains("提取") || joined.contains("角色") -> ASSETS_JSON
                joined.contains("分镜") || joined.contains("镜头") -> SHOTS_JSON
                else -> "x"
            }
            return ChatResponse(out, out)
        }
    }

    @Test
    fun `桌面JVM跑通五阶段+合成`() = runBlocking {
        val graph = object {
            val projects = mutableMapOf<String, String>()
            val episodes = mutableMapOf<String, String>()
            val renderTasks = mutableMapOf<String, String>()
        }
        val orch = DefaultAiOrchestrator(
            createProject = { name -> "p1".also { graph.projects["p1"] = name } },
            createEpisode = { pid, script -> "p1_ep1".also { graph.episodes["p1_ep1"] = script } },
            extractAssets = { text, _ ->
                // 桌面冒烟：直接构造资产，验证编排框架（解析器在安卓端已验证）
                Result.success(listOf(
                    AiAsset("a_主角", "character", "主角", "古装少年侠客"),
                    AiAsset("a_客栈", "scene", "客栈", "宋代木构酒楼"),
                ))
            },
            generateImage = { Result.success("https://img/${it.name}.png") },
            auditAsset = { Result.success(AuditResult(passed = true)) },
            generateShots = { script, _ ->
                // 桌面冒烟：直接构造镜头，验证 orchestrator 编排框架（解析器在安卓端已验证）
                Result.success(listOf(
                    AiShot(1, "主角踏入客栈环视", "店家，来壶酒"),
                    AiShot(2, "主角落座擦剑", ""),
                ))
            },
            enqueueRender = { epId, shots ->
                shots.forEach { s -> graph.renderTasks["rt_${epId}_${s.shotNo}"] = "/tmp/clip_${s.shotNo}.mp4" }
                Result.success(shots.size)
            },
            checkModel = { Result.success(Unit) },
        )
        val script = "宋代，少年侠客踏入客栈，环视四周，落座擦剑，夜色渐深。".repeat(8)
        val res = orch.run(script, brief = null)
        assertTrue("orchestrator.run 应成功，实际: $res", res.isSuccess)
        val run = res.getOrThrow()
        assertEquals("p1_ep1", run.episodeId)
        assertTrue("渲染任务应非空，实际: ${graph.renderTasks}", graph.renderTasks.isNotEmpty())
    }
}
