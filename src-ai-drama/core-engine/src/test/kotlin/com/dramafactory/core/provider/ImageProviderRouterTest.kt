package com.dramafactory.core.provider

import com.dramafactory.core.model.ConnectionInfo
import com.dramafactory.core.model.ImageGenRequest
import com.dramafactory.core.model.ModelSpec
import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.VideoSubmitRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 图像通道路由（v1.9.1）单测：agnes/custom 走原生，其他家退化为 image2video 首帧 */
class ImageProviderRouterTest {

    class FakeKeyVault : KeyVault {
        private val store = mutableMapOf<String, String>()
        override suspend fun save(configId: String, providerId: String, plainKey: String) { store[configId] = plainKey }
        override suspend fun load(configId: String): String = store[configId] ?: ""
        override fun masked(configId: String): String = store[configId]?.let { "sk-***" } ?: "<empty>"
        override suspend fun delete(configId: String) { store.remove(configId) }
        override fun readSync(configId: String): String = store[configId] ?: ""
        override fun writeSync(configId: String, plainValue: String) { store[configId] = plainValue }
    }

    class FakeVideoProvider(
        override val id: String = "kling",
        var submitCalls: Int = 0,
    ) : VideoProvider {
        override suspend fun validateKey(key: String): Result<ConnectionInfo> = Result.success(ConnectionInfo(ok = true))
        override fun listModels(): List<ModelSpec> = listOf(ModelSpec("m1", "M1"))
        override suspend fun submitVideo(req: VideoSubmitRequest): String {
            submitCalls++
            return "task_${req.shotId}"
        }
        override suspend fun pollResult(providerTaskId: String): PollResult =
            PollResult.Completed("https://v.example/${providerTaskId}.mp4")
    }

    class FakeImageProvider(
        override val id: String = "agnes",
        var genCalls: Int = 0,
    ) : ImageProvider {
        override suspend fun generateImage(req: ImageGenRequest): String {
            genCalls++
            return "https://img.agnes/${req.prompt.hashCode()}"
        }
    }

    private fun initRouters(activeId: String, agnesReady: Boolean): Pair<FakeVideoProvider, FakeImageProvider> {
        val kv = FakeKeyVault()
        VideoProviderRouter.init(
            keyVault = kv,
            regionProvider = { AgnesRegion.INTERNATIONAL },
            agnesProviderProvider = { error("unused") },
        )
        VideoProviderRouter.setActive(activeId)
        val fakeVid = FakeVideoProvider()
        val fakeImg = FakeImageProvider()
        ImageProviderRouter.init(
            videoRouter = VideoProviderRouter,
            agnesProvider = { fakeImg },
            agnesKeyReady = { agnesReady },
            frameExtractor = { "data:image/png;base64,FRAME_${it.hashCode()}" },
        )
        return fakeVid to fakeImg
    }

    @Test
    fun resolve_agnes_returnsAgnesImageProvider() = runTest {
        initRouters("agnes", agnesReady = false)
        val p = ImageProviderRouter.resolve()
        assertEquals("agnes", p.id)
        val img = p as FakeImageProvider
        p.generateImage(ImageGenRequest("角色图"))
        assertEquals(1, img.genCalls)
    }

    @Test
    fun resolve_custom_returnsAgnesImageProvider() = runTest {
        initRouters("custom", agnesReady = false)
        val p = ImageProviderRouter.resolve()
        assertEquals("agnes", p.id, "custom 应复用 Agnes 图像端点")
    }

    @Test
    fun resolve_otherProvider_returnsDegraded() = runTest {
        initRouters("kling", agnesReady = false)
        val p = ImageProviderRouter.resolve()
        assertTrue(p is ImageProviderRouter.DegradedImageProvider, "激活非 Agnes 视频供应商应返回退化 ImageProvider")
        assertEquals("kling+image-fallback", p.id)
    }

    @Test
    fun degraded_agnesKeyReady_prefersAgnes() = runTest {
        val (vid, img) = initRouters("kling", agnesReady = true)
        val p = ImageProviderRouter.DegradedImageProvider(
            videoProvider = vid, agnes = img, agnesKeyReady = { true },
            frameExtractor = { "data:image/png;base64,F" },
        )
        val out = p.generateImage(ImageGenRequest("主角红衣"))
        assertTrue(out.startsWith("https://img.agnes/"), "Agnes Key 在应优先 Agnes 端点")
        assertEquals(0, vid.submitCalls)
        assertEquals(1, img.genCalls)
    }

    @Test
    fun degraded_noAgnesKey_fallsBackToImage2VideoFirstFrame() = runTest {
        val (vid, img) = initRouters("kling", agnesReady = false)
        val p = ImageProviderRouter.DegradedImageProvider(
            videoProvider = vid, agnes = img, agnesKeyReady = { false },
            frameExtractor = { "data:image/png;base64,FRAME_${it.hashCode()}" },
        )
        val out = p.generateImage(ImageGenRequest("场景图"))
        assertEquals(1, vid.submitCalls, "退化应调 video.submitVideo")
        assertEquals(0, img.genCalls)
        assertTrue(out.startsWith("data:image/png;base64,FRAME_"), "应返回首帧 data URI")
    }

    @Test
    fun parseSize_parsesWxH_orFallsBack() = runTest {
        val a = ImageProviderRouter.DegradedImageProvider.parseSize("1024x768")
        assertEquals(1024, a.first)
        assertEquals(768, a.second)
        val b = ImageProviderRouter.DegradedImageProvider.parseSize("garbage")
        assertEquals(448, b.first)
        assertEquals(832, b.second)
    }
}
