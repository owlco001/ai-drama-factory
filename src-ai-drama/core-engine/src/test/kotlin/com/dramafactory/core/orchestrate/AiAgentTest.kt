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
    fun `system prompt 注入人设`() = runBlocking {
        val agent = AiAgent(textProvider = FakeAgentProvider(), modelId = "fake")
        assertTrue(agent.messages.first().role == "system")
        assertTrue(agent.messages.first().content.contains("编剧导演"))
    }
}
