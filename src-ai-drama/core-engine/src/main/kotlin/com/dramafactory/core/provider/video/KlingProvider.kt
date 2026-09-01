package com.dramafactory.core.provider.video

import com.dramafactory.core.model.ModelSpec
import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.pipeline.DefaultRateGate
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 可灵 Kling 视频生成适配器（专属实现，非 Agnes 兼容壳）。
 *
 * API（生产）：https://api-beijing.klingai.com
 * - 文生视频 POST /v1/videos/text2video；图生视频 POST /v1/videos/image2video
 * - 鉴权：Authorization: Bearer <API_KEY>
 * - 关键帧：image（首帧）+ image_tail（尾帧），URL 或裸 base64（非 data: URI）
 * - 尺寸仅 aspect_ratio（无 width/height）；时长 duration 字符串；sound=on/off 控制音频
 * - 轮询 GET /v1/videos/{type}/{task_id}；task_status: submitted/processing/succeed/failed
 *   视频 URL：data.task_result.videos[].url
 */
class KlingProvider(
    override var apiKeyProvider: suspend () -> String = { "" },
    client: HttpClient = com.dramafactory.core.provider.SharedHttp.client,
    rateGate: DefaultRateGate = DefaultRateGate(),
    sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : BaseVideoProvider(apiKeyProvider, client, rateGate, sleeper) {

    override val id: String = "kling"
    override val baseUrl: String = "https://api-beijing.klingai.com"

    private val defaultModel = "kling-v2-6"
    private val defaultAspect = "9:16"

    override fun listModels(): List<ModelSpec> = listOf(
        ModelSpec("kling-v2-6", "可灵 Kling v2.6").apply { supportsVideoReference = true },
        ModelSpec("kling-v3", "可灵 Kling v3").apply { supportsVideoReference = true },
        ModelSpec("kling-v1-6", "可灵 Kling v1.6（多图参考）").apply { supportsVideoReference = true },
    )

    override suspend fun doSubmit(req: VideoSubmitRequest): String {
        val first = firstReference(req)
        val last = req.lastImageUri
        val useImage = first != null || last != null
        val endpoint = if (useImage) "/v1/videos/image2video" else "/v1/videos/text2video"

        val firstNorm = normalizeImage(first, ImageAcceptance.RAW_BASE64)
        val lastNorm = normalizeImage(last, ImageAcceptance.RAW_BASE64)

        val body = buildJsonObject {
            put("model_name", defaultModel)
            put("prompt", req.prompt)
            put("duration", durationSeconds(req.numFrames, req.frameRate).toString())
            put("mode", "std")
            put("aspect_ratio", aspectRatio(req.width, req.height).takeIf { it != "1:1" } ?: defaultAspect)
            put("sound", if (req.generateAudio) "on" else "off")
            req.negativePrompt?.let { put("negative_prompt", it) }
            if (firstNorm != null) put("image", JsonPrimitive(firstNorm))
            if (lastNorm != null) put("image_tail", JsonPrimitive(lastNorm))
        }
        val out = postJson(endpoint, body)
        val taskId = out["data"]?.jsonObject?.get("task_id")?.jsonPrimitive?.content
            ?: throw ProviderError.ReconcileRequired(
                rawBody = out.toString().take(400),
                msg = "2xx but missing data.task_id; remote task may be billed — reconcile required")
        // 轮询路径依赖提交端点（text2video/image2video），编码进 taskId 以跨重启存活
        return "${endpoint.substringAfterLast("/")}:$taskId"
    }

    override suspend fun doPoll(taskId: String): PollResult {
        val (type, id) = taskId.split(":", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else "text2video" to taskId
        }
        val out = getJson("/v1/videos/$type/$id")
        val status = out["data"]?.jsonObject?.get("task_status")?.jsonPrimitive?.content ?: "processing"
        return when (status) {
            "succeed" -> {
                val videos = out["data"]?.jsonObject?.get("task_result")?.jsonObject
                    ?.get("videos")?.jsonArray
                val url = videos?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                    ?: return PollResult.Failed("completed but missing video url")
                PollResult.Completed(url)
            }
            "failed" -> {
                val msg = out["data"]?.jsonObject?.get("task_status_msg")?.jsonPrimitive?.content ?: "unknown"
                PollResult.Failed(msg.take(400))
            }
            else -> PollResult.InProgress(null)
        }
    }
}
