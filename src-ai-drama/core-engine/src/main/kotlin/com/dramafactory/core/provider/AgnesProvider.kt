package com.dramafactory.core.provider

import com.dramafactory.core.model.ChatMessage
import com.dramafactory.core.model.ChatRequest
import com.dramafactory.core.model.ChatResponse
import com.dramafactory.core.model.ConnectionInfo
import com.dramafactory.core.model.ImageGenRequest
import com.dramafactory.core.model.ModelSpec
import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.pipeline.DefaultRateGate
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * AgnesVideoAdapter —— Agnes/PavoAPI 三通道适配器（语义对齐 agnes_client.py v0.9.4+）。
 *
 * 实战继承要点（架构§4）：
 * 1. submitVideo 第一行过 120s 限速门；
 * 2. 429 从HTTP层立即抛 QuotaError，视频提交外层专用长退避 base=30s cap=180s 最多3次；
 * 5xx 走指数退避（2s×2^n ≤3次）；
 * 3. 参数前置校验：num_frames 归一 8n+1 且≤441；宽高取64倍数；frame_rate∈[1,60]；
 * 4. keyframes 双帧必须同时传 image=[first,last] + mode="keyframes"；
 * 5. 中文配音指令注入（决议Q9）+ generate_audio=true/audio=true；
 * 6. 文本通道 enable_thinking=false；JSON解析3次退避重试由调用方处理；
 * 7. 日志脱敏：Key 掩码（前3后3）、响应体截断。
 */
class AgnesProvider(
    private val rateGate: DefaultRateGate = DefaultRateGate(),
    /** 明文Key来源：生产为KeyVault.load()，测试注入假实现。仅进Authorization header */
    var apiKeyProvider: suspend () -> String = { "" },
    private val client: HttpClient = defaultClient(),
    /** 可注入时钟/睡眠以便JVM测试时序断言 */
    private val sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : VideoProvider, TextProvider, ImageProvider {

    companion object {
        const val BASE_URL = "https://apihub.agnes-ai.com/v1"
        const val VIDEO_RESULT_URL = "https://apihub.agnes-ai.com/agnesapi" // ?video_id=...
        const val MODEL_TEXT = "agnes-2.5-flash"
        const val MODEL_IMAGE = "agnes-image-2.1-flash"
        const val MODEL_VIDEO = "agnes-video-v2.0"
        const val MAX_NUM_FRAMES = 441
        const val NUM_FRAMES_MOD = 8      // 必须 8n+1
        const val DIMENSION_MULTIPLE = 64 // 宽高必须64的倍数

        // 视频提交长退避参数（对齐pavo VIDEO_SUBMIT_BACKOFF_*）
        const val SUBMIT_BACKOFF_BASE_MS = 30_000L
        const val SUBMIT_BACKOFF_CAP_MS = 180_000L
        const val SUBMIT_MAX_ATTEMPTS = 3
        const val HTTP_MAX_RETRIES = 3
        const val INITIAL_BACKOFF_MS = 2_000L

        // pavo _mask_key 语义移植：前3后3
        fun maskKey(k: String): String =
            when { k.isEmpty() -> "<empty>"; k.length <= 8 -> "***"; else -> "${k.take(3)}***${k.takeLast(3)}" }

        /** num_frames 归一到最近的 8n+1，clamp到[1,441]（对齐closest_valid_num_frames） */
        fun closestValidNumFrames(target: Int): Int {
            if (target <= 1) return 1
            val capped = minOf(target, MAX_NUM_FRAMES)
            val n = Math.round((capped - 1).toDouble() / NUM_FRAMES_MOD)
            return NUM_FRAMES_MOD * n + 1
        }

        /** 尺寸归一到64的倍数（≥64），向下取整（对齐closest_valid_dimension） */
        fun closestValidDimension(target: Int): Int {
            if (target <= 0) return DIMENSION_MULTIPLE
            return maxOf(DIMENSION_MULTIPLE, target / DIMENSION_MULTIPLE * DIMENSION_MULTIPLE)
        }
    }

    override val id: String = "agnes"

    private val json = Json { ignoreUnknownKeys = true }

    private fun defaultClient() = HttpClient {
        // 底层OkHttp engine默认即可；超时按请求配置（架构§1.2）
    }

    // ------------------------------------------------------------------
    // 低层HTTP：POST/GET 带可重试状态分类（对齐_post_json/_get_json）
    // 注意：429 在此层立即抛 QuotaError，绝不HTTP层快重试——
    //       视频提交的长退避在外层循环处理。
    // ------------------------------------------------------------------
    private suspend fun postJson(path: String, body: JsonObject): JsonObject {
        var backoff = INITIAL_BACKOFF_MS
        var lastErr: Exception? = null
        repeat(HTTP_MAX_RETRIES) { attempt ->
            try {
                val resp = client.post("$BASE_URL$path") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer ${apiKeyProvider()}")
                    setBody(body.toString())
                }
                return when {
                    resp.status.value == 200 || resp.status.value == 201 ->
                        json.parseToJsonElement(resp.bodyAsText().ifEmpty { "{}" }).jsonObject
                    resp.status.value == 429 ->
                        throw ProviderError.QuotaError("429 Too Many Requests: ${resp.snip()}")
                    resp.status.value == 401 ->
                        throw ProviderError.AuthError("401 Unauthorized: ${resp.snip()}")
                    resp.status.value == 400 || resp.status.value == 422 ->
                        throw ProviderError.ValidationError("${resp.status.value}: ${resp.snip()}")
                    resp.isRetryable() -> {
                        lastErr = ProviderError.TransientError("HTTP ${resp.status.value} (retryable)")
                        sleeper(backoff); backoff *= 2; null!!
                    }
                    else -> throw ProviderError.TransientError("HTTP ${resp.status.value}: ${resp.snip()}")
                }
            } catch (e: java.io.IOException) {
                // 网络瞬断：指数退避重试
                lastErr = e; if (attempt == HTTP_MAX_RETRIES - 1) break
                sleeper(backoff); backoff *= 2
            }
        }
        throw ProviderError.TransientError("giving up on $path after $HTTP_MAX_RETRIES attempts: $lastErr")
    }

    private suspend fun getJson(url: String): JsonObject {
        var backoff = INITIAL_BACKOFF_MS
        var lastErr: Exception? = null
        repeat(HTTP_MAX_RETRIES) { attempt ->
            try {
                val resp = client.get(url) {
                    header(HttpHeaders.Authorization, "Bearer ${apiKeyProvider()}")
                }
                return when {
                    resp.status.value == 200 ->
                        json.parseToJsonElement(resp.bodyAsText().ifEmpty { "{}" }).jsonObject
                    resp.status.value == 429 ->
                        throw ProviderError.QuotaError("429 Too Many Requests: ${resp.snip()}")
                    resp.status.value == 401 ->
                        throw ProviderError.AuthError("401 Unauthorized: ${resp.snip()}")
                    resp.isRetryable() -> {
                        lastErr = ProviderError.TransientError("HTTP ${resp.status.value} (retryable)")
                        sleeper(backoff); backoff *= 2; null!!
                    }
                    else -> throw ProviderError.TransientError("HTTP ${resp.status.value}: ${resp.snip()}")
                }
            } catch (e: java.io.IOException) {
                lastErr = e; if (attempt == HTTP_MAX_RETRIES - 1) break
                sleeper(backoff); backoff *= 2
            }
        }
        throw ProviderError.TransientError("giving up on GET after $HTTP_MAX_RETRIES attempts: $lastErr")
    }

    private fun HttpResponse.isRetryable(): Boolean =
        status.value in intArrayOf(408, 500, 502, 503, 504, 520, 522, 524)

    /** 日志脱敏：响应体截断记录（架构§4.9） */
    private suspend fun HttpResponse.snip(): String = bodyAsText().take(400)

    // ------------------------------------------------------------------
    // VideoProvider
    // ------------------------------------------------------------------
    override suspend fun validateKey(key: String): Result<ConnectionInfo> {
        // 最小成本请求：1-token chat ping
        return try {
            val saved = apiKeyProvider
            apiKeyProvider = { key }
            try {
                val t0 = System.currentTimeMillis()
                chat(ChatRequest(messages = listOf(ChatMessage("user", "ping")), maxTokens = 8))
                Result.success(ConnectionInfo(true, System.currentTimeMillis() - t0, "chat ping ok"))
            } finally { apiKeyProvider = saved }
        } catch (e: ProviderError.AuthError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun listModels(): List<ModelSpec> = listOf(
        ModelSpec(MODEL_VIDEO, "Agnes 视频 v2.0"),
        ModelSpec(MODEL_TEXT, "Agnes 文本 2.5 Flash"),
        ModelSpec(MODEL_IMAGE, "Agnes 图像 2.1 Flash"),
    )

    override suspend fun submitVideo(req: VideoSubmitRequest): String {
        // ① 先过120s限速门再干活（首发免等）
        rateGate.awaitSlot()

        // 参数前置校验：本地暴露4xx问题，省远程成本（架构§4.4）
        if (req.frameRate < 1f || req.frameRate > 60f)
            throw ProviderError.ValidationError("frame_rate ${req.frameRate} out of range [1,60]")
        val nf = closestValidNumFrames(req.numFrames)
        val w = closestValidDimension(req.width)
        val h = closestValidDimension(req.height)

        // 决议Q9：中文配音指令注入（中文台词主导开头+显式指令追加）
        val prompt = ChineseAudioInjector.inject(req.prompt)

        val body = buildJsonObject {
            put("model", MODEL_VIDEO)
            put("prompt", prompt)
            put("width", w); put("height", h)
            put("num_frames", nf); put("frame_rate", req.frameRate.toDouble())
            if (req.firstImageUri != null && req.lastImageUri != null) {
                // keyframes模式：双帧必须同时传 image=[first,last] + mode=keyframes（缺mode会400）
                put("image", buildJsonArray {
                    add(json.parseToJsonElement("\"${req.firstImageUri}\""))
                    add(json.parseToJsonElement("\"${req.lastImageUri}\""))
                })
                put("mode", "keyframes")
            } else {
                req.firstImageUri?.let { put("image", it) }
            }
            req.negativePrompt?.let { put("negative_prompt", it) }
            if (req.generateAudio) {
                // 原生语音轨=人声+环境音+SFX一体，永不做静音+重配
                put("generate_audio", true)
                put("audio", true)
            }
        }

        // ② 429长退避不快重试：base=30s cap=180s 最多3次（对齐generate_video外层循环）
        var backoff = SUBMIT_BACKOFF_BASE_MS
        repeat(SUBMIT_MAX_ATTEMPTS) { attempt ->
            try {
                // maxRetries语义=1：本层不做HTTP快重试，429/5xx全走长退避
                val out = postJson("/videos", body)
                val videoId = out["video_id"]?.jsonPrimitive?.content
                    ?: throw ProviderError.ValidationError("missing video_id in response")
                // 返回providerTaskId；调用方拿到后【立即】落库submitted态
                return videoId
            } catch (e: ProviderError.QuotaError) {
                if (attempt == SUBMIT_MAX_ATTEMPTS - 1) throw e
                sleeper(backoff); backoff = minOf(backoff * 2, SUBMIT_BACKOFF_CAP_MS)
            } catch (e: ProviderError.TransientError) {
                // 仅5xx类走长退避重试；其余上抛
                val retryable = e.message?.contains("(retryable)") == true
                if (!retryable || attempt == SUBMIT_MAX_ATTEMPTS - 1) throw e
                sleeper(backoff); backoff = minOf(backoff * 2, SUBMIT_BACKOFF_CAP_MS)
            }
        }
        throw ProviderError.TransientError("video submit failed after $SUBMIT_MAX_ATTEMPTS attempts")
    }

    override suspend fun pollResult(providerTaskId: String): PollResult {
        // GET /agnesapi?video_id= —— 推荐轮询端点（真实Agnes）
        val out = getJson("$VIDEO_RESULT_URL?video_id=$providerTaskId")
        val status = out["status"]?.jsonPrimitive?.content ?: "unknown"
        return when (status) {
            "completed" -> {
                val url = out["url"]?.jsonPrimitive?.contentOrNull
                    ?: out["metadata"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull ?: ""
                PollResult.Completed(url)
            }
            "failed" -> {
                val err = out["error"]?.jsonObject.toString()
                PollResult.Failed(err.take(400))
            }
            else -> PollResult.InProgress(out["progress"]?.jsonPrimitive?.intOrNull)
        }
    }

    // 轮询自适应间隔：submitted初期30s，10分钟后降60s（PRD F09/架构§7.1）
    suspend fun adaptivePollInterval(submittedAtMs: Long, nowMs: Long = System.currentTimeMillis()): Long =
        if (nowMs - submittedAtMs < 10 * 60_000L) 30_000L else 60_000L

    // ------------------------------------------------------------------
    // TextProvider（enable_thinking=false 约定，避免reasoning吃空content）
    // ------------------------------------------------------------------
    override suspend fun chat(req: ChatRequest): ChatResponse {
        val body = buildJsonObject {
            put("model", req.model.ifEmpty { MODEL_TEXT })
            put("messages", buildJsonArray {
                req.messages.forEach { m ->
                    add(buildJsonObject { put("role", m.role); put("content", m.content) })
                }
            })
            put("temperature", req.temperature)
            req.maxTokens?.let { put("max_tokens", it) }
            if (!req.enableThinking) {
                // 默认false：agnes-2.5-flash reasoning会吃空content导致静默空响应
                put("chat_template_kwargs", buildJsonObject { put("enable_thinking", false) })
            }
        }
        val out = postJson("/chat/completions", body)
        val content = out["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: throw ProviderError.ValidationError("unexpected chat response: ${out.toString().take(400)}")
        return ChatResponse(content, out.toString())
    }

    // ------------------------------------------------------------------
    // ImageProvider
    // ------------------------------------------------------------------
    override suspend fun generateImage(req: ImageGenRequest): String {
        val body = buildJsonObject {
            put("model", MODEL_IMAGE)
            put("prompt", req.prompt)
            put("size", req.size)
            // response_format 放 extra_body 而非顶层（对齐pavo实战注释）
            put("extra_body", buildJsonObject {
                put("response_format", "url")
                if (req.inputImages.isNotEmpty()) {
                    put("image", buildJsonArray { req.inputImages.forEach { add(json.parseToJsonElement("\"$it\"")) } })
                }
            })
            req.negativePrompt?.let { put("negative_prompt", it) }
        }
        val out = postJson("/images/generations", body)
        val item = out["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw ProviderError.ValidationError("unexpected image response: ${out.toString().take(400)}")
        val url = item["url"]?.jsonPrimitive?.contentOrNull
        val b64 = item["b64_json"]?.jsonPrimitive?.contentOrNull
        return url ?: b64?.let { "data:image/png;base64,$it" }
        ?: throw ProviderError.ValidationError("image item has no url/b64_json")
    }
}
