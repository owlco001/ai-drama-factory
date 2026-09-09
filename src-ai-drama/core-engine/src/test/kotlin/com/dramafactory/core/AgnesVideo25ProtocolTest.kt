// v1.9.27：Agnes Video 2.5 / 2.5-flash 官方独立协议回归测试
// 背景：v1.9.16 把 2.5 按 v2.0 形态发（width/height/num_frames + extra_body.reference_images），
// 官方网关 400 invalid_request: width is a forbidden field。
// 官方文档（wiki.agnes-ai.com agnes-video-25）：mode 必填（text/keyframe/reference），
// 尺寸=size档位+aspect_ratio，时长=seconds 字符串，媒体字段顶层；width/height/fps/num_frames 一律 400。
package com.dramafactory.core

import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.pipeline.DefaultRateGate
import com.dramafactory.core.provider.AgnesProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AgnesVideo25ProtocolTest {

    private class MockApi {
        var count = 0
        var lastBody: String = ""
        var lastPath: String = ""
        var responder: (Int) -> Pair<HttpStatusCode, String> = { n ->
            HttpStatusCode.OK to if (lastPath.contains("agnesapi"))
                """{"status":"completed","metadata":{"url":"https://cdn/v.mp4"}}"""
            else """{"video_id":"vid-$n","status":"queued"}"""
        }
        fun client(): HttpClient = HttpClient(MockEngine { req ->
            count++
            lastPath = req.url.encodedPath + req.url.encodedQuery
            lastBody = (req.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)
                ?.bytes()?.decodeToString() ?: ""
            val (status, body) = responder(count)
            respond(body, status, headersOf("Content-Type" to listOf("application/json")))
        })
    }

    private fun provider(model: String, api: MockApi) = AgnesProvider(
        rateGate = DefaultRateGate(0) {}, apiKeyProvider = { "sk-t" },
        client = api.client(), sleeper = {}, videoModelOverride = model,
    )

    @Test
    fun `智能选型_纯文生与五张以内参考图用Flash_多图或视频参考用25`() {
        assertEquals("agnes-video-2.5-flash", AgnesProvider.pickVideoModel("", imageCount = 0, hasReferenceVideo = false, region = com.dramafactory.core.provider.AgnesRegion.INTERNATIONAL))
        assertEquals("agnes-video-2.5-flash", AgnesProvider.pickVideoModel("", imageCount = 5, hasReferenceVideo = false, region = com.dramafactory.core.provider.AgnesRegion.INTERNATIONAL))
        assertEquals("agnes-video-2.5", AgnesProvider.pickVideoModel("", imageCount = 6, hasReferenceVideo = false, region = com.dramafactory.core.provider.AgnesRegion.INTERNATIONAL))
        assertEquals("agnes-video-2.5", AgnesProvider.pickVideoModel("", imageCount = 1, hasReferenceVideo = true, region = com.dramafactory.core.provider.AgnesRegion.INTERNATIONAL))
        assertEquals("agnes-video-v2.0", AgnesProvider.pickVideoModel("", imageCount = 8, hasReferenceVideo = true, region = com.dramafactory.core.provider.AgnesRegion.CHINA))
        assertEquals("agnes-video-2.5", AgnesProvider.pickVideoModel("agnes-video-2.5", imageCount = 0, hasReferenceVideo = false, region = com.dramafactory.core.provider.AgnesRegion.INTERNATIONAL))
    }

    @Test
    fun `model_not_found即使HTTP503也不重试并明确分类`() = runBlocking {
        val api = MockApi().apply {
            responder = { HttpStatusCode.ServiceUnavailable to """{"error":"model_not_found: No available channel for model agnes-video-2.5"}""" }
        }
        val sleeps = mutableListOf<Long>()
        val error = assertFailsWith<ProviderError.ValidationError> {
            AgnesProvider(
                rateGate = DefaultRateGate(0) {}, apiKeyProvider = { "sk-t" },
                client = api.client(), sleeper = { sleeps += it }, videoModelOverride = "agnes-video-2.5",
            ).submitVideo(VideoSubmitRequest(shotId = "s1", prompt = "x"))
        }
        assertTrue(error.message.orEmpty().contains("视频模型不可用"), error.message)
        assertEquals(1, api.count, "模型不可用不是瞬时故障，不应重复请求")
    }

    @Test
    fun `25 reference模式 禁width字段 images顶层 mode必填`() = runBlocking {
        val api = MockApi()
        provider("agnes-video-2.5", api).submitVideo(VideoSubmitRequest(
            shotId = "s1", prompt = "主角踏入客栈环视",
            firstImageUri = "data:image/png;base64,FIRST",
            inputImages = listOf("data:image/png;base64,REF1", "data:image/png;base64,REF2"),
        ))
        val b = api.lastBody
        assertFalse(b.contains("\"width\""), "2.5 禁 width（官方 400 forbidden field）")
        assertFalse(b.contains("\"height\""), "2.5 禁 height")
        assertFalse(b.contains("\"num_frames\""), "2.5 禁 num_frames")
        assertFalse(b.contains("\"frame_rate\""), "2.5 禁 frame_rate")
        assertFalse(b.contains("extra_body"), "媒体字段必须顶层，不再走 extra_body")
        assertTrue(b.contains("\"mode\":\"reference\""), b)
        assertTrue(b.contains("\"images\":["), "参考图走顶层 images 数组")
        assertTrue(b.contains("FIRST") && b.contains("REF1") && b.contains("REF2"))
        assertTrue(b.contains("<Picture 1>"), "reference prompt 需 Picture 指代首帧为视觉主体")
        assertTrue(b.contains("\"seconds\":\""), "时长用 seconds 字符串")
        assertTrue(b.contains("\"size\":\"720P\""))
        assertTrue(b.contains("\"aspect_ratio\":\"9:16\""), "默认 448x832 → 9:16 竖屏")
    }

    @Test
    fun `25flash参考图上限5张 25上限8张`() = runBlocking {
        val many = (1..9).map { "data:image/png;base64,R$it" }
        val apiF = MockApi()
        provider("agnes-video-2.5-flash", apiF).submitVideo(VideoSubmitRequest(
            shotId = "s1", prompt = "x", inputImages = many,
        ))
        val imgCountF = Regex("data:image/png;base64,R\\d").findAll(apiF.lastBody).map { it.value }.distinct().count()
        assertEquals(5, imgCountF, "flash images 最多 5 张（超出 400）")

        val api25 = MockApi()
        provider("agnes-video-2.5", api25).submitVideo(VideoSubmitRequest(
            shotId = "s1", prompt = "x", inputImages = many,
        ))
        val imgCount25 = Regex("data:image/png;base64,R\\d").findAll(api25.lastBody).map { it.value }.distinct().count()
        assertEquals(8, imgCount25, "2.5 官方上限 8 张")
    }

    @Test
    fun `25 首尾帧无参考图走keyframe 无图走text`() = runBlocking {
        val api = MockApi()
        provider("agnes-video-2.5", api).submitVideo(VideoSubmitRequest(
            shotId = "s1", prompt = "推近", firstImageUri = "data:image/png;base64,F", lastImageUri = "data:image/png;base64,L",
        ))
        assertTrue(api.lastBody.contains("\"mode\":\"keyframe\""), api.lastBody)
        assertTrue(api.lastBody.contains("\"first_frame\"") && api.lastBody.contains("\"last_frame\""))
        assertFalse(api.lastBody.contains("\"images\""), "keyframe 模式不允许 images 字段")

        api.count = 0
        provider("agnes-video-2.5", api).submitVideo(VideoSubmitRequest(shotId = "s2", prompt = "纯文"))
        assertTrue(api.lastBody.contains("\"mode\":\"text\""), api.lastBody)
    }

    @Test
    fun `25轮询带model_name参数`() = runBlocking {
        val api = MockApi()
        val p = provider("agnes-video-2.5", api)
        val r = p.pollResult("vid-42")
        assertTrue(r is com.dramafactory.core.model.PollResult.Completed)
        assertTrue(api.lastPath.contains("model_name=agnes-video-2.5"), "keyframe/reference 轮询必须带 model_name: ${api.lastPath}")
    }

    @Test
    fun `v2点0协议不变 仍发width与num_frames`() = runBlocking {
        val api = MockApi()
        provider("agnes-video-v2.0", api).submitVideo(VideoSubmitRequest(
            shotId = "s1", prompt = "老协议", firstImageUri = "data:image/png;base64,F",
        ))
        val b = api.lastBody
        assertTrue(b.contains("\"width\"") && b.contains("\"height\""), "v2.0 保留 width/height")
        assertTrue(b.contains("\"num_frames\"") && b.contains("\"frame_rate\""))
        assertFalse(b.contains("\"aspect_ratio\""))
    }

    @Test
    fun `辅助函数 seconds画幅与图数上限`() {
        assertEquals("5", AgnesProvider.video25Seconds(121, 24f))   // 121/24≈5
        assertEquals("4", AgnesProvider.video25Seconds(25, 24f))    // 1s → clamp 4
        assertEquals("12", AgnesProvider.video25Seconds(441, 24f))  // 18s → clamp 12
        assertEquals("9:16", AgnesProvider.video25AspectRatio(448, 832))
        assertEquals("16:9", AgnesProvider.video25AspectRatio(832, 448))
        assertEquals("1:1", AgnesProvider.video25AspectRatio(640, 640))
        assertEquals(5, AgnesProvider.video25MaxImages("agnes-video-2.5-flash"))
        assertEquals(8, AgnesProvider.video25MaxImages("agnes-video-2.5"))
    }
}
