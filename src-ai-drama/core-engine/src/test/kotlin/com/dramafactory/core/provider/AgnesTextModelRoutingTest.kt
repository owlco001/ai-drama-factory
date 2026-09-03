// v1.9.5 文本通道模型路由测试：官方网关下 ChatRequest.model 必须命中官方目录，否则回退自动选模。
// 背景：AiAgent 曾把 **providerId**（"agnes"）当 modelId 传入，该值非空使自动选模失效，
// 官方网关收到 model="agnes" 直接拒绝 → 「设置页测试连通成功（validateKey 走有效默认值），
// 但 AI 助手聊天失败」。自定义 baseUrl 供应商的模型名不受官方目录限制，需单独保证不被误伤。
package com.dramafactory.core.provider

import com.dramafactory.core.model.ChatMessage
import com.dramafactory.core.model.ChatRequest
import com.dramafactory.core.pipeline.DefaultRateGate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AgnesTextModelRoutingTest {

    /** 捕获发出请求体，便于断言最终落到网关的 model 字段 */
    private class MockApi {
        var lastBody: String = ""
        fun client() = HttpClient(MockEngine { req ->
            lastBody = (req.body as? OutgoingContent.ByteArrayContent)
                ?.bytes()?.decodeToString() ?: ""
            respond(
                """{"choices":[{"message":{"content":"ok"}}]}""",
                HttpStatusCode.OK,
                headersOf("Content-Type" to listOf("application/json")),
            )
        })
        val sentModel: String
            get() = Json.parseToJsonElement(lastBody)
                .jsonObject["model"]!!.jsonPrimitive.content
    }

    private fun provider(client: HttpClient, baseUrlOverride: String? = null) = AgnesProvider(
        rateGate = DefaultRateGate(0) {},
        apiKeyProvider = { "sk-test-1234567890" },
        client = client,
        sleeper = {},
        baseUrlOverride = baseUrlOverride,
    )

    /** 回归核心：providerId 不是模型名，绝不能原样发往网关 */
    @Test
    fun `传入 providerId agnes 时回退自动选模而非原样发出`() = runBlocking {
        val api = MockApi()
        provider(api.client()).chat(
            ChatRequest(messages = listOf(ChatMessage("user", "你好")), model = "agnes"))
        assertNotEquals("agnes", api.sentModel, "providerId 非合法模型 ID，不得原样发往网关")
        assertEquals(AgnesProvider.MODEL_TEXT, api.sentModel, "短输入自动选 agnes-2.5-flash")
    }

    @Test
    fun `传入合法官方模型时原样保留`() = runBlocking {
        val api = MockApi()
        provider(api.client()).chat(
            ChatRequest(messages = listOf(ChatMessage("user", "你好")),
                model = AgnesProvider.MODEL_TEXT_MID))
        assertEquals(AgnesProvider.MODEL_TEXT_MID, api.sentModel)
    }

    @Test
    fun `model 为空时走自动选模`() = runBlocking {
        val api = MockApi()
        provider(api.client()).chat(
            ChatRequest(messages = listOf(ChatMessage("user", "你好")), model = ""))
        assertEquals(AgnesProvider.MODEL_TEXT, api.sentModel)
    }

    /** 自定义 OpenAI 兼容供应商：模型名由用户配置，不能被官方目录白名单误伤 */
    @Test
    fun `自定义 baseUrl 时尊重调用方配置的模型名`() = runBlocking {
        val api = MockApi()
        provider(api.client(), baseUrlOverride = "https://api.example.com/v1").chat(
            ChatRequest(messages = listOf(ChatMessage("user", "你好")), model = "my-custom-model"))
        assertEquals("my-custom-model", api.sentModel,
            "自定义供应商模型名不受官方目录限制")
    }

    /** 官方目录外但形似模型的未知名，同样回退自动选模 */
    @Test
    fun `未知模型名回退自动选模`() = runBlocking {
        val api = MockApi()
        provider(api.client()).chat(
            ChatRequest(messages = listOf(ChatMessage("user", "你好")), model = "gpt-4o-mini"))
        assertEquals(AgnesProvider.MODEL_TEXT, api.sentModel)
    }

    /** 自动选模仍按输入规模降级（100K~200K → 2.0-flash） */
    @Test
    fun `超长输入自动降级到中等模型`() = runBlocking {
        val api = MockApi()
        val long = "汉".repeat(150_000)
        provider(api.client()).chat(
            ChatRequest(messages = listOf(ChatMessage("user", long)), model = ""))
        assertEquals(AgnesProvider.MODEL_TEXT_MID, api.sentModel,
            "100K~200K token 应降级为 agnes-2.0-flash")
    }
}
