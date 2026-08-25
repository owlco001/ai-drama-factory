// 429退避重试次数 + 参数前置校验 + 中文配音注入测试
package com.dramafactory.core

import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.pipeline.DefaultRateGate
import com.dramafactory.core.provider.AgnesProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AgnesBackoffTest {

    private fun videoJson(n: Int) = """{"video_id":"vid-$n","status":"queued"}"""

    /** 可编程Mock引擎：按请求序号返回预设状态码，并捕获请求体 */
    private class MockApi(private val statusOf: (Int) -> HttpStatusCode) {
        var count = 0
        var lastBody: String = ""
        private fun videoJson(n: Int) = """{"video_id":"vid-$n","status":"queued"}"""
        fun client() = HttpClient(MockEngine { req ->
            val n = ++count
            lastBody = (req.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)
                ?.bytes()?.decodeToString() ?: ""
            respond(videoJson(n), statusOf(n),
                headersOf("Content-Type" to listOf("application/json")))
        })
    }

    @Test
    fun `429两次后第三次成功_长退避恰好两次_base30_cap180`() = runBlocking {
        val api = MockApi { n -> if (n <= 2) HttpStatusCode.TooManyRequests else HttpStatusCode.OK }
        val sleeps = mutableListOf<Long>()
        val provider = AgnesProvider(
            rateGate = DefaultRateGate(0) {},
            apiKeyProvider = { "sk-test1234567890" },
            client = api.client(),
            sleeper = { sleeps += it },
        )
        val vid = provider.submitVideo(VideoSubmitRequest(shotId = "s1", prompt = "他抬头看天"))
        assertEquals("vid-3", vid)
        assertEquals(3, api.count, "共3次HTTP尝试：2次429+1次成功")
        assertEquals(listOf(30_000L, 60_000L), sleeps, "长退避序列 base30s→60s（cap180s）")
    }

    @Test
    fun `连续三次429耗尽后上抛QuotaError不再重试`() = runBlocking {
        val api = MockApi { _ -> HttpStatusCode.TooManyRequests }
        val provider = AgnesProvider(
            rateGate = DefaultRateGate(0) {}, apiKeyProvider = { "sk-test" },
            client = api.client(), sleeper = {},
        )
        assertFailsWith<ProviderError.QuotaError> {
            provider.submitVideo(VideoSubmitRequest(shotId = "s1", prompt = "台词"))
        }
        assertEquals(AgnesProvider.SUBMIT_MAX_ATTEMPTS, api.count, "最多3次尝试即止")
    }

    @Test
    fun `401立即抛AuthError零重试`() = runBlocking {
        val api = MockApi { _ -> HttpStatusCode.Unauthorized }
        val provider = AgnesProvider(
            rateGate = DefaultRateGate(0) {}, apiKeyProvider = { "bad" },
            client = api.client(), sleeper = {},
        )
        assertFailsWith<ProviderError.AuthError> {
            provider.submitVideo(VideoSubmitRequest(shotId = "s1", prompt = "台词"))
        }
        assertEquals(1, api.count, "401不烧任何重试")
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
    fun `keyframes双帧模式带mode字段与中文配音指令`() = runBlocking {
        val api = MockApi { _ -> HttpStatusCode.OK }
        val provider = AgnesProvider(
            rateGate = DefaultRateGate(0) {}, apiKeyProvider = { "k" },
            client = api.client(), sleeper = {},
        )
        val vid = provider.submitVideo(VideoSubmitRequest(
            shotId = "s1", prompt = "她推开门",
            firstImageUri = "data:image/png;base64,AAA", lastImageUri = "data:image/png;base64,BBB",
        ))
        assertTrue(vid.startsWith("vid-"), "双帧带mode=keyframes时服务端200")
        assertTrue(api.lastBody.contains("\"mode\":\"keyframes\""), "双帧必须带mode=keyframes")
        assertTrue(api.lastBody.contains("全程使用中文普通话配音"), "决议Q9中文配音指令注入")
        assertTrue(api.lastBody.contains("\"generate_audio\":true"), "generate_audio=true原生配音")
        // keyframes模式请求体含首尾两帧
        assertTrue(api.lastBody.contains("AAA") && api.lastBody.contains("BBB"))
    }

    @Test
    fun `Key掩码前3后3`() {
        assertEquals("sk-***xyz", AgnesProvider.maskKey("sk-abcdefghijklmnop-xyz"))
        assertEquals("***", AgnesProvider.maskKey("short"))
        assertEquals("<empty>", AgnesProvider.maskKey(""))
    }

    @Test
    fun `轮询间隔自适应30s到60s`() {
        val now = System.currentTimeMillis()
        assertEquals(30_000L, providerPollInterval(now, now), "10分钟内30s")
        assertEquals(60_000L, providerPollInterval(now - 11 * 60_000L, now), "超10分钟降60s")
    }

    private fun providerPollInterval(submittedAt: Long, now: Long): Long =
        if (now - submittedAt < 10 * 60_000L) 30_000L else 60_000L
}
