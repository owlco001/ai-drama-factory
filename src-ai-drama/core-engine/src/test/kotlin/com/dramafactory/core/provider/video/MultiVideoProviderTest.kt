// v1.9.0：五家专属视频适配器（Kling/即梦/Runway/Luma/Pika）Mock HTTP 回归测试
// 覆盖：提交请求体字段、轮询三态、鉴权头差异、图像格式归一、错误分类、路由分池
package com.dramafactory.core.provider.video

import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.pipeline.DefaultRateGate
import com.dramafactory.core.provider.AgnesRegion
import com.dramafactory.core.provider.KeyVault
import com.dramafactory.core.provider.VideoProvider
import com.dramafactory.core.provider.VideoProviderRouter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiVideoProviderTest {

    // ---------------- 可编程 Mock 引擎 ----------------

    private class MockApi {
        var count = 0
        val urls = mutableListOf<String>()
        val methods = mutableListOf<HttpMethod>()
        val headers = mutableListOf<Headers>()
        val bodies = mutableListOf<String>()
        /** 按请求序号返回 (状态码, 响应体) */
        var responder: (Int) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to """{"id":"job-1"}""" }

        fun client() = HttpClient(MockEngine { req ->
            val n = ++count
            urls += req.url.toString()
            methods += req.method
            headers += req.headers
            bodies += (req.body as? OutgoingContent.ByteArrayContent)
                ?.bytes()?.decodeToString() ?: ""
            val (status, body) = responder(n)
            respond(body, status, headersOf("Content-Type" to listOf("application/json")))
        })

        fun reset() { count = 0; urls.clear(); methods.clear(); headers.clear(); bodies.clear() }
        val lastBody get() = bodies.lastOrNull() ?: ""
        val lastUrl get() = urls.lastOrNull() ?: ""
        val lastHeaders get() = headers.lastOrNull() ?: headersOf()
    }

    private val sleeps = mutableListOf<Long>()

    private fun req(
        first: String? = null, last: String? = null,
        inputImages: List<String> = emptyList(), audio: Boolean = true,
    ) = VideoSubmitRequest(
        shotId = "s1", prompt = "他推开门，烛火晃动",
        negativePrompt = "modern, neon",
        firstImageUri = first, lastImageUri = last,
        inputImages = inputImages, generateAudio = audio,
    )

    // ==================================================================
    // Kling 可灵
    // ==================================================================

    private fun kling(api: MockApi) = KlingProvider(
        apiKeyProvider = { "sk-kling" }, client = api.client(),
        rateGate = DefaultRateGate(0) {}, sleeper = { sleeps += it })

    @Test
    fun `可灵_文生视频走text2video且带音频与竖屏比`() = runBlocking {
        val api = MockApi()
        api.responder = { HttpStatusCode.OK to """{"data":{"task_id":"kl-1"}}""" }
        val id = kling(api).submitVideo(req())
        assertEquals("text2video:kl-1", id, "taskId 编码端点类型以便跨重启轮询")
        assertTrue(api.lastUrl.endsWith("/v1/videos/text2video"))
        val b = api.lastBody
        assertTrue(b.contains("\"model_name\":\"kling-v2-6\""), b)
        assertTrue(b.contains("\"aspect_ratio\":\"9:16\""), b)
        assertTrue(b.contains("\"sound\":\"on\""), b)
        assertTrue(b.contains("\"duration\":\"5\""), b)
        assertTrue(b.contains("\"negative_prompt\":\"modern, neon\""), b)
        assertEquals("Bearer sk-kling", api.lastHeaders["Authorization"])
    }

    @Test
    fun `可灵_首尾帧走image2video且图像剥成裸base64`() = runBlocking {
        val api = MockApi()
        api.responder = { HttpStatusCode.OK to """{"data":{"task_id":"kl-2"}}""" }
        // 关音频时应显式 sound=off（Kling 默认带音，必须显式关）
        val id = kling(api).submitVideo(req(
            first = "data:image/png;base64,AAA", last = "data:image/png;base64,BBB", audio = false))
        assertEquals("image2video:kl-2", id)
        assertTrue(api.lastUrl.endsWith("/v1/videos/image2video"))
        val b = api.lastBody
        assertTrue(b.contains("\"image\":\"AAA\""), "首帧剥掉 data: 前缀 → 裸 base64")
        assertTrue(b.contains("\"image_tail\":\"BBB\""), b)
        assertFalse(b.contains("data:image"), "可灵不接受 data URI")
        assertTrue(b.contains("\"sound\":\"off\""), b)
    }

    @Test
    fun `可灵_轮询三态_succeed取videos首个url`() = runBlocking {
        val api = MockApi()
        val p = kling(api)
        api.responder = { HttpStatusCode.OK to """{"data":{"task_status":"processing"}}""" }
        assertTrue(p.pollResult("text2video:kl-1") is PollResult.InProgress)

        api.responder = { HttpStatusCode.OK to
                """{"data":{"task_status":"succeed","task_result":{"videos":[{"url":"https://v/kl.mp4"}]}}}""" }
        assertEquals(PollResult.Completed("https://v/kl.mp4"), p.pollResult("image2video:kl-1"))
        assertTrue(api.lastUrl.endsWith("/v1/videos/image2video/kl-1"), "按编码端点拼轮询路径")

        api.responder = { HttpStatusCode.OK to """{"data":{"task_status":"failed","task_status_msg":"nsfw"}}""" }
        assertEquals(PollResult.Failed("nsfw"), p.pollResult("text2video:kl-1"))
    }

    @Test
    fun `可灵_2xx但缺task_id抛ReconcileRequired`() = runBlocking {
        val api = MockApi()
        api.responder = { HttpStatusCode.OK to """{"code":0}""" }
        assertFailsWith<ProviderError.ReconcileRequired> { kling(api).submitVideo(req()) }
    }

    // ==================================================================
    // 即梦 / Seedance（火山方舟）
    // ==================================================================

    private fun jimeng(api: MockApi) = JimengProvider(
        apiKeyProvider = { "sk-jimeng" }, client = api.client(),
        rateGate = DefaultRateGate(0) {}, sleeper = { sleeps += it })

    @Test
    fun `即梦_多模态content含首尾帧与参考图角色`() = runBlocking {
        val api = MockApi()
        api.responder = { HttpStatusCode.OK to """{"id":"jm-1"}""" }
        val id = jimeng(api).submitVideo(req(
            first = "data:image/png;base64,FIRST",
            last = "https://cdn/last.png",
            inputImages = listOf("data:image/png;base64,FIRST", "data:image/png;base64,REF"),
        ))
        assertEquals("jm-1", id)
        assertTrue(api.lastUrl.endsWith("/contents/generations/tasks"))
        val b = api.lastBody
        assertTrue(b.contains("\"role\":\"first_frame\""), b)
        assertTrue(b.contains("\"role\":\"last_frame\""), b)
        assertTrue(b.contains("\"role\":\"reference_image\""), b)
        assertTrue(b.contains("\"type\":\"text\""), b)
        assertTrue(b.contains("data:image/png;base64,FIRST"), "即梦接受 data URI")
        assertTrue(b.contains("\"generate_audio\":true"), b)
        assertTrue(b.contains("\"ratio\":\"9:16\""), b)
        assertTrue(b.contains("\"resolution\":\"720p\""), b)
    }

    @Test
    fun `即梦_视频参考注入video_url节点`() = runBlocking {
        val api = MockApi()
        api.responder = { HttpStatusCode.OK to """{"id":"jm-2"}""" }
        jimeng(api).submitVideo(req().copy(referenceVideoUri = "https://cdn/ref.mp4"))
        val b = api.lastBody
        assertTrue(b.contains("\"type\":\"video_url\""), b)
        assertTrue(b.contains("https://cdn/ref.mp4"), b)
    }

    @Test
    fun `即梦_轮询三态_succeeded取content首个video_url`() = runBlocking {
        val api = MockApi()
        val p = jimeng(api)
        api.responder = { HttpStatusCode.OK to """{"id":"jm-1","status":"running"}""" }
        assertTrue(p.pollResult("jm-1") is PollResult.InProgress)
        api.responder = { HttpStatusCode.OK to """{"id":"jm-1","status":"succeeded","content":[{"video_url":"https://v/jm.mp4"}]}""" }
        assertEquals(PollResult.Completed("https://v/jm.mp4"), p.pollResult("jm-1"))
        api.responder = { HttpStatusCode.OK to """{"id":"jm-1","status":"failed","error":{"code":"InvalidParameter"}}""" }
        assertTrue(p.pollResult("jm-1") is PollResult.Failed)
    }

    // ==================================================================
    // Runway
    // ==================================================================

    private fun runway(api: MockApi) = RunwayProvider(
        apiKeyProvider = { "sk-runway" }, client = api.client(),
        rateGate = DefaultRateGate(0) {}, sleeper = { sleeps += it })

    @Test
    fun `Runway_携带固定版本号头且图生视频走image_to_video`() = runBlocking {
        val api = MockApi()
        api.responder = { HttpStatusCode.OK to """{"id":"rw-1"}""" }
        val id = runway(api).submitVideo(req(first = "https://cdn/first.png"))
        assertEquals("rw-1", id)
        assertEquals("2024-11-06", api.lastHeaders["X-Runway-Version"], "Runway 强校验版本头")
        assertTrue(api.lastUrl.endsWith("/v1/image_to_video"))
        val b = api.lastBody
        assertTrue(b.contains("\"model\":\"gen4.5\""), b)
        assertTrue(b.contains("\"promptText\":\"他推开门，烛火晃动\""), "Runway 字段名是 promptText 非 prompt")
        assertTrue(b.contains("\"promptImage\":\"https://cdn/first.png\""), b)
        assertTrue(b.contains("\"ratio\":\"720:1280\""), "竖屏比例")
        assertTrue(b.contains("\"duration\":5"), b)
    }

    @Test
    fun `Runway_无图走text_to_video且轮询解析output数组与进度`() = runBlocking {
        val api = MockApi()
        val p = runway(api)
        api.responder = { HttpStatusCode.OK to """{"id":"rw-2"}""" }
        p.submitVideo(req())
        assertTrue(api.lastUrl.endsWith("/v1/text_to_video"))
        assertFalse(api.lastBody.contains("promptImage"))

        api.responder = { HttpStatusCode.OK to """{"id":"rw-2","status":"RUNNING","progress":"0.42"}""" }
        val ing = p.pollResult("rw-2")
        assertTrue(ing is PollResult.InProgress)
        api.responder = { HttpStatusCode.OK to """{"id":"rw-2","status":"SUCCEEDED","output":["https://v/rw.mp4"]}""" }
        assertEquals(PollResult.Completed("https://v/rw.mp4"), p.pollResult("rw-2"))
        api.responder = { HttpStatusCode.OK to """{"id":"rw-2","status":"FAILED","failure":"content filtered"}""" }
        assertEquals(PollResult.Failed("content filtered"), p.pollResult("rw-2"))
    }

    // ==================================================================
    // Luma
    // ==================================================================

    private fun luma(api: MockApi) = LumaProvider(
        apiKeyProvider = { "sk-luma" }, client = api.client(),
        rateGate = DefaultRateGate(0) {}, sleeper = { sleeps += it })

    @Test
    fun `Luma_仅接受URL关键帧_dataURI被跳过退化为纯文生`() = runBlocking {
        val api = MockApi()
        api.responder = { HttpStatusCode.OK to """{"id":"lm-1"}""" }
        luma(api).submitVideo(req(first = "data:image/png;base64,AAA", last = "data:image/png;base64,BBB"))
        val b = api.lastBody
        assertFalse(b.contains("keyframes"), "Luma 拒收 data URI → 不注入关键帧")
        assertTrue(b.contains("\"model\":\"ray-2\""), b)
        assertTrue(b.contains("\"aspect_ratio\":\"9:16\""), b)
        assertTrue(b.contains("\"duration\":\"5s\""), b)
        assertFalse(b.contains("negative_prompt"), "Luma 无反向提示词字段")
    }

    @Test
    fun `Luma_HTTPS关键帧写入frame0与frame1`() = runBlocking {
        val api = MockApi()
        api.responder = { HttpStatusCode.OK to """{"id":"lm-2"}""" }
        val id = luma(api).submitVideo(req(
            first = "https://cdn/f0.png", last = "https://cdn/f1.png"))
        assertEquals("lm-2", id)
        val b = api.lastBody
        assertTrue(b.contains("\"frame0\""), b)
        assertTrue(b.contains("https://cdn/f0.png"), b)
        assertTrue(b.contains("\"frame1\""), b)
    }

    @Test
    fun `Luma_轮询completed取assets_video`() = runBlocking {
        val api = MockApi()
        val p = luma(api)
        api.responder = { HttpStatusCode.OK to """{"id":"lm-1","state":"dreaming"}""" }
        assertTrue(p.pollResult("lm-1") is PollResult.InProgress)
        api.responder = { HttpStatusCode.OK to """{"id":"lm-1","state":"completed","assets":{"video":"https://v/lm.mp4"}}""" }
        assertEquals(PollResult.Completed("https://v/lm.mp4"), p.pollResult("lm-1"))
        api.responder = { HttpStatusCode.OK to """{"id":"lm-1","state":"failed","failure_reason":"prompt rejected"}""" }
        assertEquals(PollResult.Failed("prompt rejected"), p.pollResult("lm-1"))
    }

    // ==================================================================
    // Pika
    // ==================================================================

    private fun pika(api: MockApi) = PikaProvider(
        apiKeyProvider = { "sk-pika" }, client = api.client(),
        rateGate = DefaultRateGate(0) {}, sleeper = { sleeps += it })

    @Test
    fun `Pika_鉴权用X-API-Key且无Authorization头`() = runBlocking {
        val api = MockApi()
        api.responder = { HttpStatusCode.OK to """{"id":"pk-1"}""" }
        val id = pika(api).submitVideo(req())
        assertEquals("pk-1", id)
        assertEquals("sk-pika", api.lastHeaders["X-API-Key"])
        assertEquals(null, api.lastHeaders["Authorization"], "Pika 不用 Bearer")
        assertTrue(api.lastUrl.endsWith("/media/pika/pika-2.5/text-to-video"))
        val b = api.lastBody
        assertTrue(b.contains("\"duration_s\":5"), b)
        assertTrue(b.contains("\"resolution\":\"720p\""), b)
        assertTrue(b.contains("\"negative_prompt\":\"modern, neon\""), b)
    }

    @Test
    fun `Pika_URL图走image-to-video_dataURI退化文生`() = runBlocking {
        val api = MockApi()
        val p = pika(api)
        api.responder = { HttpStatusCode.OK to """{"id":"pk-2"}""" }
        p.submitVideo(req(first = "https://cdn/a.png"))
        assertTrue(api.lastUrl.endsWith("/media/pika/pika-2.5/image-to-video"))
        assertTrue(api.lastBody.contains("\"image\":\"https://cdn/a.png\""))

        api.reset()
        api.responder = { HttpStatusCode.OK to """{"id":"pk-3"}""" }
        p.submitVideo(req(first = "data:image/png;base64,AAA"))
        assertTrue(api.lastUrl.endsWith("/media/pika/pika-2.5/text-to-video"), "data URI 不支持 → 退化")
    }

    @Test
    fun `Pika_轮询completed取output_video_url`() = runBlocking {
        val api = MockApi()
        val p = pika(api)
        api.responder = { HttpStatusCode.OK to """{"id":"pk-1","status":"running"}""" }
        assertTrue(p.pollResult("pk-1") is PollResult.InProgress)
        api.responder = { HttpStatusCode.OK to """{"id":"pk-1","status":"completed","output":{"video":{"url":"https://v/pk.mp4"}}}""" }
        assertEquals(PollResult.Completed("https://v/pk.mp4"), p.pollResult("pk-1"))
        api.responder = { HttpStatusCode.OK to """{"id":"pk-1","status":"failed","error":{"message":"quota exceeded"}}""" }
        assertEquals(PollResult.Failed("quota exceeded"), p.pollResult("pk-1"))
        assertTrue(api.lastUrl.endsWith("/media/jobs/pk-1"))
    }

    // ==================================================================
    // 基类通用：错误分类 / 参数校验 / 连通性
    // ==================================================================

    @Test
    fun `401抛AuthError零重试_429抛QuotaError`() = runBlocking {
        val api = MockApi()
        api.responder = { HttpStatusCode.Unauthorized to """{"err":"bad key"}""" }
        val p = kling(api)
        assertFailsWith<ProviderError.AuthError> { p.submitVideo(req()) }
        assertEquals(1, api.count, "401 不烧重试")

        api.reset()
        api.responder = { HttpStatusCode.TooManyRequests to """{"err":"rate"}""" }
        assertFailsWith<ProviderError.QuotaError> { p.submitVideo(req()) }
        assertEquals(1, api.count, "提交层统一抛 QuotaError，由外层长退避兜")
    }

    @Test
    fun `400抛ValidationError_5xx抛可重试TransientError`() = runBlocking {
        val api = MockApi()
        val p = jimeng(api)
        api.responder = { HttpStatusCode.BadRequest to """{"error":"bad param"}""" }
        assertFailsWith<ProviderError.ValidationError> { p.submitVideo(req()) }

        api.reset()
        api.responder = { HttpStatusCode.ServiceUnavailable to """{"error":"down"}""" }
        val e = assertFailsWith<ProviderError.TransientError> { p.pollResult("jm-1") }
        assertTrue(e.retryable)
    }

    @Test
    fun `frameRate越界本地拦截不发请求`() = runBlocking {
        val api = MockApi()
        val p = runway(api)
        assertFailsWith<ProviderError.ValidationError> {
            p.submitVideo(req().copy(frameRate = 120f))
        }
        assertEquals(0, api.count, "参数前置校验省远程成本")
    }

    @Test
    fun `空Key校验失败_非空Key校验通过`() = runBlocking {
        val p = luma(MockApi())
        assertFalse(p.validateKey("").isSuccess)
        assertTrue(p.validateKey("sk-real-key").isSuccess)
        assertTrue(p.listModels().any { it.id == "ray-2" }, "模型清单非空")
    }

    // ==================================================================
    // 视频路由：供应商分池 + 激活解析
    // ==================================================================

    private class MemVault : KeyVault {
        val map = mutableMapOf<String, String>()
        override suspend fun save(configId: String, providerId: String, plainKey: String) { map[configId] = plainKey }
        override suspend fun load(configId: String): String = map.getValue(configId)
        override fun masked(configId: String): String = map[configId]?.take(3) ?: ""
        override suspend fun delete(configId: String) { map.remove(configId) }
        override fun readSync(configId: String): String = map[configId] ?: ""
        override fun writeSync(configId: String, plainValue: String) { map[configId] = plainValue }
    }

    private val stubAgnes = object : VideoProvider {
        override val id = "agnes"
        override suspend fun validateKey(key: String) = Result.success(com.dramafactory.core.model.ConnectionInfo(ok = true))
        override fun listModels() = emptyList<com.dramafactory.core.model.ModelSpec>()
        override suspend fun submitVideo(req: VideoSubmitRequest) = "agnes-1"
        override suspend fun pollResult(providerTaskId: String): PollResult = PollResult.Completed("https://v/a.mp4")
    }

    @Test
    fun `路由_Key按供应商分池_激活供应商决定解析结果`() {
        val vault = MemVault()
        VideoProviderRouter.init(vault, { AgnesRegion.INTERNATIONAL }) { stubAgnes }

        assertEquals("agnes-video", VideoProviderRouter.configIdFor("agnes", AgnesRegion.INTERNATIONAL))
        assertEquals("agnes-cn-video", VideoProviderRouter.configIdFor("agnes", AgnesRegion.CHINA))
        assertEquals("custom-video", VideoProviderRouter.configIdFor("custom", AgnesRegion.CHINA))
        assertEquals("kling-video", VideoProviderRouter.configIdFor("kling", AgnesRegion.CHINA), "第三方不受 region 影响")
        assertEquals("pika-video", VideoProviderRouter.configIdFor("pika", AgnesRegion.INTERNATIONAL))

        VideoProviderRouter.setActive("kling")
        assertEquals("kling", VideoProviderRouter.activeVideoProviderId())
        assertFalse(VideoProviderRouter.activeKeyReady(), "未存 Key → 闸门关闭")
        assertEquals("kling", VideoProviderRouter.resolve().id, "无 Key 也解析出供应商实例（由上层提示配 Key）")

        vault.map["kling-video"] = "sk-kling-real"
        assertTrue(VideoProviderRouter.activeKeyReady())
        assertEquals("pika", VideoProviderRouter.resolveFor("pika").id)
        // 激活态持久化后重新 init 能恢复
        VideoProviderRouter.init(vault, { AgnesRegion.INTERNATIONAL }) { stubAgnes }
        assertEquals("kling", VideoProviderRouter.activeVideoProviderId(), "重启后仍指向 kling")

        VideoProviderRouter.setActive("agnes")
        assertEquals(stubAgnes.id, VideoProviderRouter.resolve().id, "agnes/custom 复用同一实例")
        assertEquals(stubAgnes.id, VideoProviderRouter.resolveFor("custom").id)
    }

    @Test
    fun `路由_未知id回退Agnes不崩`() {
        val vault = MemVault()
        VideoProviderRouter.init(vault, { AgnesRegion.INTERNATIONAL }) { stubAgnes }
        assertEquals("agnes", VideoProviderRouter.resolveFor("no-such-vendor").id)
    }
}
