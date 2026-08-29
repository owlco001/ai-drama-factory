package com.dramafactory.core.orchestrate

import com.dramafactory.core.model.ChatMessage
import com.dramafactory.core.model.ChatRequest
import com.dramafactory.core.model.ChatResponse
import com.dramafactory.core.provider.TextProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 假 TextProvider：把用户最后一条消息回声，方便验证对话累积 */
class FakeAgentProvider(private val onReq: (ChatRequest) -> Unit = {}) : TextProvider {
    override val id = "fake"
    override suspend fun chat(req: ChatRequest): ChatResponse {
        onReq(req)
        val lastUser = req.messages.last { it.role == "user" }.content
        return ChatResponse(content = "收到：$lastUser", raw = "{}")
    }
}

class AiAgentTest {
    @Test
    fun `say 累积剧本草稿并多轮上下文`() = runBlocking {
        val provider = FakeAgentProvider()
        val agent = AiAgent(textProvider = provider, modelId = "fake")
        agent.say("你好")
        agent.say("东汉末年群雄并起，天下大乱，英雄辈出，故事绵延百年之久，权谋争斗不断上演于此。".repeat(3))
        // 剧本草稿应被记录
        assertTrue(agent.scriptDraft.length >= 100, "scriptDraft 应≥100")
        // 多轮：system + 2 user + 2 assistant = 5
        assertEquals(5, agent.messages.size)
        // canGenerate 应为 true
        assertTrue(agent.canGenerate())
    }

    @Test
    fun `resolveScript 优先用脚本草稿`() = runBlocking {
        val agent = AiAgent(textProvider = FakeAgentProvider(), modelId = "fake")
        agent.say("短句")
        agent.say("长剧本内容".repeat(20))
        val s = agent.resolveScript()
        assertTrue(s.length >= 100)
        assertEquals(agent.scriptDraft, s)
    }

    @Test
    fun `canGenerate 在剧本过短时 false`() = runBlocking {
        val agent = AiAgent(textProvider = FakeAgentProvider(), modelId = "fake")
        agent.say("只想聊聊想法，没粘剧本")
        assertFalse(agent.canGenerate())
    }

    @Test
    fun `system prompt 教学全粒度控动作`() = runBlocking {
        val agent = AiAgent(textProvider = FakeAgentProvider(), modelId = "fake")
        val sys = agent.messages.first().content
        assertTrue(sys.contains("编剧导演"))
        // 全粒度控 verb 应出现在系统 prompt（让 LLM 知道能用）
        listOf("set_cross_era", "list_assets", "remove_asset", "edit_asset",
            "stop_generate", "review_pass", "build_pose_pack", "generate").forEach {
            assertTrue(sys.contains(it), "系统prompt应教 [$it] 动作: $sys")
        }
        // 应先 list_assets 再操作带 assetId 的流程说明
        assertTrue(sys.contains("list_assets"), "应提示先查资产拿 id")
    }

    @Test
    fun `AI回复带ACT被剥离且handler执行回显`() = runBlocking {
        // 固定回复：正文 + [ACT] 指令
        val provider = object : TextProvider {
            override val id = "fake2"
            override suspend fun chat(req: ChatRequest): ChatResponse =
                ChatResponse(content = "好的，已为你放开现代器物限制。\n[ACT] set_cross_era | allowed=手机,眼镜,手表", raw = "{}")
        }
        var handled: String? = null
        val agent = AiAgent(
            textProvider = provider, modelId = "fake",
            actionHandler = { act, _ ->
                if (act.verb == "set_cross_era") "已放开：${act.paramList("allowed").joinToString("、")}" else null
            },
        )
        val out = agent.say("放开跨时代器物吧")
        assertTrue(out.contains("好的，已为你放开"), "展示文本保留正文: $out")
        assertTrue(!out.contains("[ACT]"), "展示文本不应含[ACT]标记: $out")
        assertTrue(out.contains("已放开：手机、眼镜、手表"), "应回显执行结果: $out")
    }

    @Test
    fun `无ACT时回复原样展示`() = runBlocking {
        val agent = AiAgent(textProvider = FakeAgentProvider(), modelId = "fake")
        val out = agent.say("随便聊聊想法")
        assertTrue(out.startsWith("收到："), "无ACT时应原样展示: $out")
    }
}
