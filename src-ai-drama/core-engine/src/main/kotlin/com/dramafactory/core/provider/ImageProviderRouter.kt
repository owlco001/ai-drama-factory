package com.dramafactory.core.provider

import com.dramafactory.core.model.ImageGenRequest
import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.model.VideoSubmitRequest
import kotlinx.coroutines.delay

/**
 * 图像通道路由（v1.9.1）—— 对应视频通道的 [VideoProviderRouter]。
 *
 * 资产图 / 封面图由图像通道生成。Agnes 三通道通吃，原生支持 `/images/generations`；
 * Kling / 即梦 / Runway / Luma / Pika 是纯视频供应商，无独立 image 端点。
 *
 * v1.9.0 把「视频模型」开放成多家可选后，用户配的 Key 多半是某家视频供应商的（而非 Agnes），
 * 而图像通道仍硬走 Agnes，于是出现「切了 Kling 就断图像」的困惑。路由规则：
 * - 激活 **agnes / custom** → 用 Agnes 原生 image 端点（质量最好、最快）
 * - 激活 **其他家** → [DegradedImageProvider]：
 *     · Agnes Key 仍存在 → 优先 Agnes 生成（透明 fallback，用户无感）
 *     · Agnes Key 不存在 → 退化该家 image2video 取首帧（满足「切了也能生图」）
 *
 * [frameExtractor] 由 app 层注入（MediaMetadataRetriever 截首帧，core 不依赖 Android）。
 */
object ImageProviderRouter {

    const val PREF_ACTIVE = VideoProviderRouter.PREF_ACTIVE

    lateinit var videoRouter: VideoProviderRouter
    lateinit var agnesProvider: () -> ImageProvider
    lateinit var agnesKeyReady: suspend () -> Boolean
    /** app 注入：视频 URL → 首帧 PNG data URI（core 不依赖 Android 专属 API） */
    lateinit var frameExtractor: suspend (String) -> String

    fun init(
        videoRouter: VideoProviderRouter,
        agnesProvider: () -> ImageProvider,
        agnesKeyReady: suspend () -> Boolean,
        frameExtractor: suspend (String) -> String,
    ) {
        this.videoRouter = videoRouter
        this.agnesProvider = agnesProvider
        this.agnesKeyReady = agnesKeyReady
        this.frameExtractor = frameExtractor
    }

    /** 解析当前激活供应商对应的 ImageProvider */
    fun resolve(): ImageProvider {
        val active = videoRouter.activeVideoProviderId()
        if (active == "agnes" || active == "custom") return agnesProvider()
        return DegradedImageProvider(
            videoProvider = videoRouter.resolveFor(active),
            agnes = agnesProvider(),
            agnesKeyReady = agnesKeyReady,
            frameExtractor = frameExtractor,
        )
    }

    /**
     * 激活供应商无原生 image 端点时的退化实现：
     * 优先用 Agnes（若其 Key 已配），否则退化到该家的 image2video 取首帧。
     */
    class DegradedImageProvider(
        private val videoProvider: VideoProvider,
        private val agnes: ImageProvider,
        private val agnesKeyReady: suspend () -> Boolean,
        private val frameExtractor: suspend (String) -> String,
    ) : ImageProvider {
        override val id: String = "${videoProvider.id}+image-fallback"

        override suspend fun generateImage(req: ImageGenRequest): String {
            // 质量优先：Agnes Key 在，直接走原生 image 端点（最快最好）
            if (agnesKeyReady()) return agnes.generateImage(req)
            // 退化：text2video 生成一段极短视频，取首帧当资产图
            val (w, h) = parseSize(req.size)
            val taskId = videoProvider.submitVideo(
                VideoSubmitRequest(
                    shotId = "img_${System.nanoTime()}",
                    prompt = req.prompt,
                    width = w, height = h,
                    numFrames = 1, frameRate = 1f,
                    generateAudio = false,
                    inputImages = req.inputImages,
                )
            )
            val videoUrl = pollUntilDone(taskId)
            return frameExtractor(videoUrl)
        }

        private suspend fun pollUntilDone(taskId: String): String {
            repeat(MAX_POLL) { i ->
                if (i > 0) delay(POLL_INTERVAL_MS)
                when (val r = videoProvider.pollResult(taskId)) {
                    is PollResult.Completed -> return r.videoUrl
                    is PollResult.Failed -> throw ProviderError.TransientError("image2video 退化失败: ${r.reason}")
                    is PollResult.InProgress -> Unit
                }
            }
            throw ProviderError.TransientError("image2video 退化超时（>${MAX_POLL} 次轮询）")
        }

        companion object {
            const val MAX_POLL = 90
            const val POLL_INTERVAL_MS = 3_000L
            /** "1024x768" → (1024, 768)；解析失败回退 9:16 竖屏默认 */
            fun parseSize(size: String): Pair<Int, Int> {
                val m = Regex("""(\d+)\s*[xX]\s*(\d+)""").find(size)
                return if (m != null) {
                    m.groupValues[1].toInt() to m.groupValues[2].toInt()
                } else VideoSubmitRequest.DEFAULT_WIDTH to VideoSubmitRequest.DEFAULT_HEIGHT
            }
        }
    }
}
