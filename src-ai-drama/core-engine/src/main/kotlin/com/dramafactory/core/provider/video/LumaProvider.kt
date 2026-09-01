package com.dramafactory.core.provider.video

import com.dramafactory.core.model.ModelSpec
import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.pipeline.DefaultRateGate
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Luma Dream Machine 视频生成适配器（专属实现）。
 *
 * API（生产）：https://api.lumalabs.ai/dream-machine/v1
 * - 提交 POST /generations/video；鉴权 Authorization: Bearer <API_KEY>
 * - 关键帧 keyframes.frame0/frame1：{type:image, url}，**仅接受 HTTPS URL（非 data URI）**
 * - 无 negative_prompt / fps / 视频内音频字段
 * - 轮询 GET /generations/{id}；state: queued/dreaming/completed/failed
 *   视频 URL：assets.video
 */
class LumaProvider(
    override var apiKeyProvider: suspend () -> String = { "" },
    client: HttpClient = com.dramafactory.core.provider.SharedHttp.client,
    rateGate: DefaultRateGate = DefaultRateGate(),
    sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : BaseVideoProvider(apiKeyProvider, client, rateGate, sleeper) {

    override val id: String = "luma"
    override val baseUrl: String = "https://api.lumalabs.ai/dream-machine/v1"

    private val defaultModel = "ray-2"

    override fun listModels(): List<ModelSpec> = listOf(
        ModelSpec("ray-2", "Luma Ray 2").apply { supportsVideoReference = true },
        ModelSpec("ray-flash-2", "Luma Ray Flash 2").apply { supportsVideoReference = true },
    )

    override suspend fun doSubmit(req: VideoSubmitRequest): String {
        // Luma 关键帧仅接受 URL：data URI 会被跳过（需先上传图床）
        val firstUrl = normalizeImage(req.firstImageUri, ImageAcceptance.URL_ONLY)
        val lastUrl = normalizeImage(req.lastImageUri, ImageAcceptance.URL_ONLY)
        // 无首帧时尝试用 inputImages 首张
        val first = firstUrl ?: normalizeImage(
            req.inputImages.firstOrNull()?.takeIf { req.firstImageUri == null }, ImageAcceptance.URL_ONLY)

        val keyframes = if (first != null) buildJsonObject {
            put("frame0", buildJsonObject { put("type", "image"); put("url", JsonPrimitive(first)) })
            if (lastUrl != null)
                put("frame1", buildJsonObject { put("type", "image"); put("url", JsonPrimitive(lastUrl)) })
        } else null

        val durationSec = if (durationSeconds(req.numFrames, req.frameRate) > 6) "9s" else "5s"
        val resolution = if (req.height >= 1080) "1080p" else "720p"

        val body = buildJsonObject {
            put("prompt", req.prompt)
            put("model", defaultModel)
            put("aspect_ratio", aspectRatio(req.width, req.height))
            put("resolution", resolution)
            put("duration", durationSec)
            keyframes?.let { put("keyframes", it) }
        }
        val out = postJson("/generations/video", body)
        val id = out["id"]?.jsonPrimitive?.content
            ?: throw ProviderError.ReconcileRequired(
                rawBody = out.toString().take(400),
                msg = "2xx but missing id; remote task may be billed — reconcile required")
        return id
    }

    override suspend fun doPoll(taskId: String): PollResult {
        val out = getJson("/generations/$taskId")
        val state = out["state"]?.jsonPrimitive?.content ?: "queued"
        return when (state) {
            "completed" -> {
                val url = out["assets"]?.jsonObject?.get("video")?.jsonPrimitive?.content
                    ?: return PollResult.Failed("completed but missing assets.video")
                PollResult.Completed(url)
            }
            "failed" -> {
                val reason = out["failure_reason"]?.jsonPrimitive?.content ?: "unknown"
                PollResult.Failed(reason.take(400))
            }
            else -> PollResult.InProgress(null)
        }
    }
}
