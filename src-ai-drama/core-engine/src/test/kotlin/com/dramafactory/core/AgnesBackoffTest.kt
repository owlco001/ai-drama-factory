// 429退避重试次数 + 参数前置校验 + 中文配音注入测试
package com.dramafactory.core

import com.dramafactory.core.provider.AgnesProvider
import com.dramafactory.core.provider.ChineseAudioInjector
import com.dramafactory.core.provider.ProviderError
import com.dramafactory.core.pipeline.DefaultRateGate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.ContentType
import io.ktor.client.request.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AgnesBackoffTest {

    /** 可编程Mock引擎：按请求序号返回预设状态码 */
    private fun mockClient(handler: (n: Int) -> HttpStatusCode): Pair<HttpClient, () -> Int> {
        var count = 0
        val engine = MockEngine { req ->
            val n = ++count
            respond(
                content = if (req.url.toString().contains("/videos"))
                    """{"video_id":"vid-$n","status":"queued"}""" else """{"status":"unknown"}""",
                status = handler(n),
                headers = headersOf("Content-Type" to listOf("application/json")),
            )
        }
        return HttpClient(engine) to { count }
    }

    @Test
    fun `429两次后第三次成功_长退避恰好两次_base30_cap180`() = runBlocking {
        val (client, counter) = mockClient { n -> if (n <= 2) HttpStatusCode.TooManyRequests else HttpStatusCode.OK }
        val sleeps = mutableListOf<Long>()
        val provider = AgnesProvider(
            rateGate = DefaultRateGate(0) {},
            apiKeyProvider = { "sk-test1234567890" },
            client = client,
            sleeper = { sleeps += it },
        )
        val vid = provider.submitVideo(com.dramafactory.core.model.VideoSubmitRequest(shotId = "s1", prompt = "他抬头看天"))
        assertEquals("vid-3", vid)
        assertEquals(3, counter(), "共3次HTTP尝试：2次429+1次成功")
        assertEquals(listOf(30_000L, minOf(60_000L, AgnesProvider.SUBMIT_BACKOFF_CAP_MS)), sleeps,
            "长退避序列 base30s→×2=60s，cap180s")
    }

    @Test
    fun `连续三次429耗尽后上抛QuotaError不再重试`() = runBlocking {
        val (client, counter) = mockClient { _ -> HttpStatusCode.TooManyRequests }
        val provider = AgnesProvider(
            rateGate = DefaultRateGate(0) {}, apiKeyProvider = { "sk-test" }, client = client,
            sleeper = {},
        )
        assertFailsWith<ProviderError.QuotaError> {
            provider.submitVideo(com.dramafactory.core.model.VideoSubmitRequest(shotId = "s1", prompt = "台词"))
        }
        assertEquals(AgnesProvider.SUBMIT_MAX_ATTEMPTS, counter(), "最多3次尝试即止")
    }

    @Test
    fun `401立即抛AuthError零重试`() = runBlocking {
        val (client, counter) = mockClient { _ -> HttpStatusCode.Unauthorized }
        val provider = AgnesProvider(
            rateGate = DefaultRateGate(0) {}, apiKeyProvider = { "bad" }, client = client, sleeper = {},
        )
        assertFailsWith<ProviderError.AuthError> {
            provider.submitVideo(com.dramafactory.core.model.VideoSubmitRequest(shotId = "s1", prompt = "台词"))
        }
        assertEquals(1, counter(), "401不烧任何重试")
    }

    @Test
    fun `numFrames归一8n加1且尺寸64倍数`() {
        assertEquals(121, AgnesProvider.closestValidNumFrames(121))
        assertEquals(121, AgnesProvider.closestValidNumFrames(124))  // 就近归一
        assertEquals(441, AgnesProvider.closestValidNumFrames(999))  // clamp上限
        assertEquals(1, AgnesProvider.closestValidNumFrames(0))
        assertEquals(448, AgnesProvider.closestValidDimension(450))
        assertEquals(832, AgnesProvider.closestValidDimension(832))
        assertEquals(64, AgnesProvider.closestValidDimension(10))
    }

    @Test
    fun `keyframes双帧模式带mode字段`() = runBlocking {
        var captured = ""
        var count = 0
        val engine = MockEngine { req ->
            count++
            captured = req.bodyOrNull() ?: ""
            if (count == 1 && !captured.contains("\"mode\":\"keyframes\""))
                return@MockEngine respond("""{"error":"set mode=keyframes with 2"}""", HttpStatusCode.BadRequest)
            respond("""{"video_id":"vid-kf","status":"queued"}""", HttpStatusCode.OK,
                headersOf("Content-Type" to listOf("application/json")))
        }
        val provider = AgnesProvider(
            rateGate = DefaultRateGate(0) {}, apiKeyProvider = { "k" },
            client = HttpClient(engine), sleeper = {},
        )
        val vid = provider.submitVideo(com.dramafactory.core.model.VideoSubmitRequest(
            shotId = "s1", prompt = "她推开门",
            firstImageUri = "data:image/png;base64,AAA", lastImageUri = "data:image/png;base64,BBB",
        ))
        assertEquals("vid-kf", vid)
        assertTrue(captured.contains("\"mode\":\"keyframes\""), "双帧必须带mode=keyframes")
        assertTrue(captured.contains("全程使用中文普通话配音"), "决议Q9中文配音指令注入")
        assertTrue(captured.contains("\"generate_audio\":true"), "generate_audio=true原生配音")
    }

    @Test
    fun `Key掩码前3后3`() {
        assertEquals("sk-***xyz", AgnesProvider.maskKey("sk-abcdefghijklmnop-xyz"))
        assertEquals("***", AgnesProvider.maskKey("short"))
        assertEquals("<empty>", AgnesProvider.maskKey(""))
    }
}

/** body提取辅助 */
suspend fun io.ktor.client.engine.mock.MockHttpRequest.bodyOrNull(): String =
    (body as? io.ktor.client.engine.mock.MockOutgoingContent)?.let { null } ?: run {
        // MockEngine请求体为ByteArrayContent
        ((body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes())?.decodeToString()
    } ?: ""
