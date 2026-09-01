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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Runway（Gen-4/4.5）视频生成适配器（专属实现）。
 *
 * API（生产）：https://api.dev.runwayml.com/v1
 * - 文生视频 POST /v1/text_to_video；图生视频 POST /v1/image_to_video
 * - 鉴权：Authorization: Bearer <API_KEY> + 固定头 X-Runway-Version: 2024-11-06
 * - 当前生产 API 仅收单张 promptImage（URL 或 data URI），无首/尾帧字段；音频由模型能力决定
 * - 轮询 GET /v1/tasks/{id}；status: PENDING/THROTTLED/RUNNING/SUCCEEDED/FAILED/CANCELLED
 *   视频 URL：output[]（字符串数组，24~48h 到期，需及时转存）
 */
class RunwayProvider(
    override var apiKeyProvider: suspend () -> String = { "" },
    client: HttpClient = com.dramafactory.core.provider.SharedHttp.client,
    rateGate: DefaultRateGate = DefaultRateGate(),
    sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : BaseVideoProvider(apiKeyProvider, client, rateGate, sleeper) {

    override val id: String = "runway"
    override val baseUrl: String = "https://api.dev.runwayml.com/v1"

    private val runwayVersion = "2024-11-06"
    private val defaultModel = "gen4.5"

    override fun authHeaders(key: String): Map<String, String> =
        mapOf(HttpHeaders.Authorization to "Bearer $key", "X-Runway-Version" to runwayVersion)

    override fun listModels(): List<ModelSpec> = listOf(
        ModelSpec("gen4.5", "Runway Gen-4.5").apply { supportsVideoReference = true },
        ModelSpec("gen4_turbo", "Runway Gen-4 Turbo").apply { supportsVideoReference = true },
        ModelSpec("veo3.1", "Runway Veo 3.1（带音轨）").apply { supportsVideoReference = true },
    )

    override suspend fun doSubmit(req: VideoSubmitRequest): String {
        val image = normalizeImage(firstReference(req), ImageAcceptance.DATA_URI)
        val endpoint = if (image != null) "/v1/image_to_video" else "/v1/text_to_video"
        val ratio = if (req.width >= req.height) "1280:720" else "720:1280"
        val duration = durationSeconds(req.numFrames, req.frameRate, min = 2, max = 10)

        val body = buildJsonObject {
            put("model", defaultModel)
            put("promptText", req.prompt)
            put("ratio", ratio)
            put("duration", duration)
            if (image != null) put("promptImage", JsonPrimitive(image))
            req.negativePrompt?.let { put("negative_prompt", it) }
        }
        val out = postJson(endpoint, body)
        val id = out["id"]?.jsonPrimitive?.content
            ?: throw ProviderError.ReconcileRequired(
                rawBody = out.toString().take(400),
                msg = "2xx but missing id; remote task may be billed — reconcile required")
        return id
    }

    override suspend fun doPoll(taskId: String): PollResult {
        val out = getJson("/v1/tasks/$taskId")
        val status = out["status"]?.jsonPrimitive?.content ?: "PENDING"
        return when (status) {
            "SUCCEEDED" -> {
                val outArr = out["output"]?.jsonArray
                val url = outArr?.firstOrNull()?.jsonPrimitive?.content
                    ?: return PollResult.Failed("completed but missing output url")
                PollResult.Completed(url)
            }
            "FAILED" -> {
                val reason = out["failure"]?.jsonPrimitive?.content
                    ?: (out as? JsonObject)?.get("failure")?.toString() ?: "unknown"
                PollResult.Failed(reason.take(400))
            }
            else -> PollResult.InProgress(out["progress"]?.jsonPrimitive?.content?.toIntOrNull())
        }
    }
}
