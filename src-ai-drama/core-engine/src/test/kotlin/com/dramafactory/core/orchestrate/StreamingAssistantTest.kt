package com.dramafactory.core.orchestrate

import com.dramafactory.core.model.ChatMessage
import com.dramafactory.core.model.ChatRequest
import com.dramafactory.core.model.ChatResponse
import com.dramafactory.core.provider.TextProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingAssistantTest {
    @Test
    fun `流式文本与动作结果分离并完成`() = runBlocking {
        val requests = mutableListOf<ChatRequest>()
        val provider = object : TextProvider {
            override val id = "fake-stream"
            override suspend fun chat(req: ChatRequest) = ChatResponse("unused", "")
            override fun streamChat(req: ChatRequest): Flow<String> {
                requests += req
                return flowOf("好的，开始处理。\n[ACT] extract_assets", "\n资产已提取")
            }
        }
        val assistant = StreamingAssistant(provider, "fake")
        val chunks = assistant.sayStreaming("提取资产").toList()
        assertTrue(chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("").contains("好的，开始处理"))
        assertTrue(chunks.any { it is StreamChunk.ActionComplete && it.module == "extract_assets" })
        assertEquals("提取资产", requests.single().messages.last().content)
        assertTrue(chunks.last() is StreamChunk.Done)
    }

    @Test
    fun `多轮请求携带上一轮消息`() = runBlocking {
        val requests = mutableListOf<ChatRequest>()
        val provider = object : TextProvider {
            override val id = "fake-history"
            override suspend fun chat(req: ChatRequest) = ChatResponse("unused", "")
            override fun streamChat(req: ChatRequest): Flow<String> {
                requests += req
                return flowOf("收到")
            }
        }
        val assistant = StreamingAssistant(provider, "fake")
        assistant.sayStreaming("我想做一个悬疑短剧").toList()
        assistant.sayStreaming("继续这个故事").toList()
        assertEquals(listOf("我想做一个悬疑短剧", "继续这个故事"), requests[1].messages.filter { it.role == "user" }.map { it.content })
        assertEquals("收到", (assistant.history.last()).content)
    }
}
