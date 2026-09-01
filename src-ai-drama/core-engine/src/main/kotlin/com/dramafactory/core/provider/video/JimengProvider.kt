package com.dramafactory.core.provider.video

import com.dramafactory.core.model.ModelSpec
import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.pipeline.DefaultRateGate
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 即梦 / 豆包 Seedance（火山方舟）视频生成适配器（专属实现）。
 *
 * API（生产）：https://ark.cn-beijing.volces.com/api/v3
 * - 异步任务 POST /contents/generations/tasks；鉴权 Authorization: Bearer <API_KEY>
 * - 多模态 content[]：text + 首/尾帧(role=first_frame/last_frame) + 参考图(role=reference_image) + 视频参考(video_url)
 * - 图像接受 data URI 或 URL；音频 generate_audio=true
 * - 轮询 GET /contents/generations/tasks/{id}；status: queued/pending/running/processing/succeeded/success/failed
 *   视频 URL：content[0].video_url
 */
class JimengProvider(
    override var apiKeyProvider: suspend () -> String = { "" },
    client: HttpClient = com.dramafactory.core.provider.SharedHttp.client,
    rateGate: DefaultRateGate = DefaultRateGate(),
    sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : BaseVideoProvider(apiKeyProvider, client, rateGate, sleeper) {

    override val id: String = "jimeng"
    override val baseUrl: String = "https://ark.cn-beijing.volces.com/api/v3"

    private val defaultModel = "doubao-seedance-2-0-260128"

    override fun listModels(): List<ModelSpec> = listOf(
        ModelSpec("doubao-seedance-2-0-260128", "即梦 Seedance 2.0").apply { supportsVideoReference = true },
        ModelSpec("doubao-seedance-1-5-pro-251215", "即梦 Seedance 1.5 Pro").apply { supportsVideoReference = true },
        ModelSpec("doubao-seedance-1-0-pro-250528", "即梦 Seedance 1.0 Pro").apply { supportsVideoReference = true },
    )

    override suspend fun doSubmit(req: VideoSubmitRequest): String {
        val first = firstReference(req)
        val last = req.lastImageUri
        val refs = req.inputImages.filter { it != first && it != last }

        val content = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", req.prompt) })
            normalizeImage(first, ImageAcceptance.DATA_URI)?.let { u ->
                add(buildJsonObject {
                    put("type", "image_url"); put("role", "first_frame")
                    put("image_url", buildJsonObject { put("url", JsonPrimitive(u)) })
                })
            }
            normalizeImage(last, ImageAcceptance.DATA_URI)?.let { u ->
                add(buildJsonObject {
                    put("type", "image_url"); put("role", "last_frame")
                    put("image_url", buildJsonObject { put("url", JsonPrimitive(u)) })
                })
            }
            refs.forEach { u ->
                normalizeImage(u, ImageAcceptance.DATA_URI)?.let { nu ->
                    add(buildJsonObject {
                        put("type", "image_url"); put("role", "reference_image")
                        put("image_url", buildJsonObject { put("url", JsonPrimitive(nu)) })
                    })
                }
            }
            // 视频参考输入（部分模型支持）：取 referenceVideoUri
            req.referenceVideoUri?.let { v ->
                add(buildJsonObject {
                    put("type", "video_url")
                    put("video_url", buildJsonObject { put("url", JsonPrimitive(v)) })
                })
            }
        }

        val resolution = if (req.height >= 1080) "1080p" else "720p"
        val body = buildJsonObject {
            put("model", defaultModel)
            put("content", content)
            put("duration", durationSeconds(req.numFrames, req.frameRate))
            put("ratio", aspectRatio(req.width, req.height))
            put("resolution", resolution)
            if (req.generateAudio) put("generate_audio", true)
        }
        val out = postJson("/contents/generations/tasks", body)
        val id = out["id"]?.jsonPrimitive?.content
            ?: throw ProviderError.ReconcileRequired(
                rawBody = out.toString().take(400),
                msg = "2xx but missing id; remote task may be billed — reconcile required")
        return id
    }

    override suspend fun doPoll(taskId: String): PollResult {
        val out = getJson("/contents/generations/tasks/$taskId")
        val status = out["status"]?.jsonPrimitive?.content ?: "processing"
        return when (status) {
            "succeeded", "success" -> {
                val url = out["content"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("video_url")?.jsonPrimitive?.content
                    ?: return PollResult.Failed("completed but missing video_url")
                PollResult.Completed(url)
            }
            "failed" -> {
                val err = out["error"]?.let { (it as? JsonObject)?.toString() } ?: "unknown"
                PollResult.Failed(err.take(400))
            }
            else -> PollResult.InProgress(null)
        }
    }
}
