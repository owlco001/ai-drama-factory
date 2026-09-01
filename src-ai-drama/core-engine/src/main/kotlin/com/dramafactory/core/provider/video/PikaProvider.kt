package com.dramafactory.core.provider.video

import com.dramafactory.core.model.ModelSpec
import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.pipeline.DefaultRateGate
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pika Labs 视频生成适配器（专属实现）。
 *
 * API（生产）：https://api.pika.art/v1
 * - 文生视频 POST /media/pika/{model}/text-to-video；图生视频 POST /media/pika/{model}/image-to-video
 * - 鉴权：**X-API-Key**（非 Bearer）
 * - 图像仅接受 URL（data URI 会被跳过 → 退化为文生视频）；支持 negative_prompt / resolution / duration_s
 * - 轮询 GET /media/jobs/{id}；status: queued/running/completed/failed
 *   视频 URL：output.video.url
 */
class PikaProvider(
    override var apiKeyProvider: suspend () -> String = { "" },
    client: HttpClient = com.dramafactory.core.provider.SharedHttp.client,
    rateGate: DefaultRateGate = DefaultRateGate(),
    sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : BaseVideoProvider(apiKeyProvider, client, rateGate, sleeper) {

    override val id: String = "pika"
    override val baseUrl: String = "https://api.pika.art/v1"

    private val defaultModel = "pika-2.5"

    override fun authHeaders(key: String): Map<String, String> =
        mapOf("X-API-Key" to key)

    override fun listModels(): List<ModelSpec> = listOf(
        ModelSpec("pika-2.5", "Pika 2.5").apply { supportsVideoReference = true },
        ModelSpec("pika-2.2", "Pika 2.2").apply { supportsVideoReference = true },
    )

    override suspend fun doSubmit(req: VideoSubmitRequest): String {
        // Pika 图生视频仅接受 URL（需先 /v1/media/uploads 拿 url）；data URI 退化文生视频
        val imageUrl = normalizeImage(firstReference(req), ImageAcceptance.URL_ONLY)
        val endpoint = if (imageUrl != null) "/media/pika/$defaultModel/image-to-video"
        else "/media/pika/$defaultModel/text-to-video"
        val resolution = if (req.height >= 1080) "1080p" else "720p"
        val durationS = if (durationSeconds(req.numFrames, req.frameRate) > 7) 10 else 5

        val body = buildJsonObject {
            put("prompt", req.prompt)
            put("resolution", resolution)
            put("duration_s", durationS)
            req.negativePrompt?.let { put("negative_prompt", it) }
            if (imageUrl != null) put("image", JsonPrimitive(imageUrl))
        }
        val out = postJson(endpoint, body)
        val id = out["id"]?.jsonPrimitive?.content
            ?: throw ProviderError.ReconcileRequired(
                rawBody = out.toString().take(400),
                msg = "2xx but missing id; remote task may be billed — reconcile required")
        return id
    }

    override suspend fun doPoll(taskId: String): PollResult {
        val out = getJson("/media/jobs/$taskId")
        val status = out["status"]?.jsonPrimitive?.content ?: "queued"
        return when (status) {
            "completed" -> {
                val url = out["output"]?.jsonObject?.get("video")?.jsonObject
                    ?.get("url")?.jsonPrimitive?.content
                    ?: return PollResult.Failed("completed but missing output.video.url")
                PollResult.Completed(url)
            }
            "failed" -> {
                val err = out["error"]?.jsonObject
                val msg = err?.get("message")?.jsonPrimitive?.content ?: err?.toString() ?: "unknown"
                PollResult.Failed(msg.take(400))
            }
            else -> PollResult.InProgress(null)
        }
    }
}
