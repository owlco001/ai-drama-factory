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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    // 默认走进程级共享客户端：Provider 是按次创建的，逐次 new HttpClient 会泄漏连接池
    private val client: HttpClient = SharedHttp.client,
    /** 可注入时钟/睡眠以便JVM测试时序断言 */
    private val sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : VideoProvider, TextProvider, ImageProvider {

    /** P1-1：验证期间的候选Key通道（null=用常规apiKeyProvider） */
    class ValidatingKeyContext(val candidateKey: String) : AbstractCoroutineContextElement(ValidatingKeyContext) {
        companion object Key : CoroutineContext.Key<ValidatingKeyContext>
    }

    /** P1-1：per-call取Key——优先协程上下文中的验证Key，否则常规来源。并发请求互不干扰 */
    private suspend fun currentApiKey(): String =
        kotlinx.coroutines.currentCoroutineContext()[ValidatingKeyContext]?.candidateKey ?: apiKeyProvider()

    companion object {
        const val BASE_URL = "https://apihub.agnes-ai.com/v1"
        const val VIDEO_RESULT_URL = "https://apihub.agnes-ai.com/agnesapi" // ?video_id=...
        const val MODEL_TEXT = "agnes-2.5-flash"
        const val MODEL_TEXT_MID = "agnes-2.0-flash"     // 256K 上下文
        const val MODEL_TEXT_LIGHT = "agnes-1.5-flash"   // 256K，低延迟
        /** 第十轮：熔断阈值——估算token超过此值不发API（官方上限512K，预留输出） */
        const val TEXT_INPUT_TOKEN_LIMIT = 230_000L

        /**
         * 按输入规模自动选型（第十轮「自动选择对应模型」）：
         * 中文≈1字符1token、ASCII≈4字符1token 的保守估算。
         * <100K → agnes-2.5-flash（512K窗口，质量优先）
         * <200K → agnes-2.0-flash（256K窗口）
         * <230K → agnes-1.5-flash（低延迟兜底）
         * ≥230K → 熔断抛 ValidationError，绝不发必爆请求
         */
        fun estimateTokens(text: String): Long {
            var cjk = 0L
            for (c in text) if (c.code > 0x2E80) cjk++
            return cjk + (text.length - cjk) / 4
        }

        fun pickTextModel(req: ChatRequest): String {
            val total = req.messages.sumOf { estimateTokens(it.content) + (it.imageUrl?.let { u -> estimateTokens(u) / 3 } ?: 0L) }
            if (total >= TEXT_INPUT_TOKEN_LIMIT)
                throw ProviderError.ValidationError(
                    "context overload: ~${total}K tokens exceeds ${TEXT_INPUT_TOKEN_LIMIT / 1000}K safe limit; 请精简输入或缩小图片")
            return when {
                total < 100_000 -> MODEL_TEXT          // agnes-2.5-flash
                total < 200_000 -> MODEL_TEXT_MID      // agnes-2.0-flash
                else -> MODEL_TEXT_LIGHT               // agnes-1.5-flash
            }
        }
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
            val n = Math.round((capped - 1).toDouble() / NUM_FRAMES_MOD).toInt()
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

    // ------------------------------------------------------------------
    // 低层HTTP：POST/GET 带可重试状态分类（对齐_post_json/_get_json）
    // 注意：429 在此层立即抛 QuotaError，绝不HTTP层快重试——
    //       视频提交的长退避在外层循环处理。
    // ------------------------------------------------------------------
    private suspend fun postJson(path: String, body: JsonObject): JsonObject {
        var backoff = INITIAL_BACKOFF_MS
        var lastErr: Exception? = null
        for (attempt in 0 until HTTP_MAX_RETRIES) {
            try {
                val resp = client.post("$BASE_URL$path") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer ${currentApiKey()}")
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
                        lastErr = ProviderError.TransientError("HTTP ${resp.status.value} retryable: ${resp.snip()}", retryable = true)
                        sleeper(backoff); backoff *= 2
                        continue
                    }
                    else -> throw ProviderError.TransientError("HTTP ${resp.status.value}: ${resp.snip()}")
                }
            } catch (e: java.io.IOException) {
                // 网络瞬断：指数退避重试（ProviderError不在此列，直接上抛）
                // v1.6.7 改进：把 lastErr 的可读信息（类名+message）累加到 reasons
                lastErr = e
                val reason = "${e.javaClass.simpleName}: ${e.message?.take(120) ?: ""}"
                // core-engine 不能依赖 app 模块的 CrashLog，只 println（Android logcat 可见）
                println("AgnesProvider postJson[$path] attempt=${attempt + 1}/$HTTP_MAX_RETRIES $reason")
                if (attempt == HTTP_MAX_RETRIES - 1) break
                sleeper(backoff); backoff *= 2
            }
        }
        // v1.6.7 改进：toString() 太长被 FQN 截断，改用类名+message
        val errInfo = if (lastErr != null) {
            "${lastErr!!.javaClass.simpleName}: ${lastErr!!.message?.take(120) ?: ""}"
        } else "no error captured"
        throw ProviderError.TransientError("giving up on $path after $HTTP_MAX_RETRIES attempts: $errInfo")
    }

    private suspend fun getJson(url: String): JsonObject {
        var backoff = INITIAL_BACKOFF_MS
        var lastErr: Exception? = null
        for (attempt in 0 until HTTP_MAX_RETRIES) {
            try {
                val resp = client.get(url) {
                    header(HttpHeaders.Authorization, "Bearer ${currentApiKey()}")
                }
                return when {
                    resp.status.value == 200 ->
                        json.parseToJsonElement(resp.bodyAsText().ifEmpty { "{}" }).jsonObject
                    resp.status.value == 429 ->
                        throw ProviderError.QuotaError("429 Too Many Requests: ${resp.snip()}")
                    resp.status.value == 401 ->
                        throw ProviderError.AuthError("401 Unauthorized: ${resp.snip()}")
                    resp.isRetryable() -> {
                        lastErr = ProviderError.TransientError("HTTP ${resp.status.value} retryable", retryable = true)
                        sleeper(backoff); backoff *= 2
                        continue
                    }
                    else -> throw ProviderError.TransientError("HTTP ${resp.status.value}: ${resp.snip()}")
                }
            } catch (e: java.io.IOException) {
                lastErr = e
                if (attempt == HTTP_MAX_RETRIES - 1) break
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
        // P1-1：候选Key经协程上下文注入——仅本次验证调用链可见，并发请求零影响
        return withContext(ValidatingKeyContext(key)) {
            try {
                val t0 = System.currentTimeMillis()
                chat(ChatRequest(messages = listOf(ChatMessage("user", "ping")), maxTokens = 8))
                Result.success(ConnectionInfo(true, System.currentTimeMillis() - t0, "chat ping ok"))
            } catch (e: ProviderError.AuthError) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override fun listModels(): List<ModelSpec> = listOf(
        ModelSpec(MODEL_VIDEO, "Agnes 视频 v2.0").apply { supportsVideoReference = true },
        ModelSpec(MODEL_TEXT, "Agnes 文本 2.5 Flash"),
        ModelSpec(MODEL_TEXT_MID, "Agnes 文本 2.0 Flash"),
        ModelSpec(MODEL_TEXT_LIGHT, "Agnes 文本 1.5 Flash"),
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
            // v1.7.2：套用 pavo 锁脸——组装 image 列表（keyframes/首帧 + 角色/场景资产参考图）
            val images = mutableListOf<String>()
            if (req.firstImageUri != null && req.lastImageUri != null) {
                images.add(req.firstImageUri); images.add(req.lastImageUri)
            } else if (req.firstImageUri != null) {
                images.add(req.firstImageUri)
            } else if (req.referenceImageUri != null) {
                images.add(req.referenceImageUri)
            }
            images.addAll(req.inputImages)
            if (images.isNotEmpty()) {
                // 用 JsonPrimitive 直接构造：原写法是手拼 "\"$it\"" 再反解析，
                // URI 里一旦出现引号/反斜杠就会拼出非法 JSON 并抛异常
                put("image", buildJsonArray { images.forEach { add(JsonPrimitive(it)) } })
                if (req.firstImageUri != null && req.lastImageUri != null) put("mode", "keyframes")
            }
            // 视频参考输入：部分供应商支持，仅当模型标记支持且提供了URI时填入
            req.referenceVideoUri?.let { put("reference_video", it) }
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
                    ?: throw ProviderError.ReconcileRequired(
                        rawBody = out.toString().take(400),
                        msg = "2xx but missing video_id; remote task may be billed — reconcile required",
                    )
                // 返回providerTaskId；调用方拿到后【立即】落库submitted态
                return videoId
            } catch (e: ProviderError.QuotaError) {
                if (attempt == SUBMIT_MAX_ATTEMPTS - 1) throw e
                sleeper(backoff); backoff = minOf(backoff * 2, SUBMIT_BACKOFF_CAP_MS)
            } catch (e: ProviderError.TransientError) {
                // 仅可重试的5xx类走长退避重试；其余上抛（P2-4：显式retryable字段）
                if (!e.retryable || attempt == SUBMIT_MAX_ATTEMPTS - 1) throw e
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
            // 第十轮：模型自动选择——按输入规模挑最合适的chat模型（官方目录512K/256K/256K）
            put("model", req.model.ifEmpty { pickTextModel(req) })
            put("messages", buildJsonArray {
                req.messages.forEach { m ->
                    if (m.imageUrl != null) {
                        // OpenAI 视觉格式：image_url 支持 http(s)/data URI（官方目录：三模型均支持 image understanding）
                        add(buildJsonObject {
                            put("role", m.role)
                            put("content", buildJsonArray {
                                add(buildJsonObject { put("type", "text"); put("text", m.content) })
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", m.imageUrl) })
                                })
                            })
                        })
                    } else {
                        add(buildJsonObject { put("role", m.role); put("content", m.content) })
                    }
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
                    put("image", buildJsonArray { req.inputImages.forEach { add(JsonPrimitive(it)) } })
                }
                // ★v1.7.8 修复：Agnes 图像队列不支持 negative_prompt（400 invalid_request），
                // 双写负向导致整张图生成失败。移除之；时代红线禁词改为在 AssetsViewModel 并入正向 prompt。
            })
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
