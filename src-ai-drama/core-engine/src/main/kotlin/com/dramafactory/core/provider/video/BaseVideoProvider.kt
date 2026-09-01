package com.dramafactory.core.provider.video

import com.dramafactory.core.model.ConnectionInfo
import com.dramafactory.core.model.ModelSpec
import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.model.VideoParams
import com.dramafactory.core.pipeline.DefaultRateGate
import com.dramafactory.core.provider.SharedHttp
import com.dramafactory.core.provider.VideoProvider
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 视频供应商适配器共享基类 —— 五个专属适配器（Kling / 即梦 / Runway / Luma / Pika）共用。
 *
 * 统一职责（对齐 AgnesProvider 实战要点）：
 * 1. submitVideo 首行过 120s 限速门（RateGate.awaitSlot）；
 * 2. HTTP 状态码分类：429→QuotaError、401→AuthError、400/422→ValidationError、
 *    5xx/网络瞬断→TransientError(retryable) 并指数退避；
 * 3. 参数前置校验（frame_rate 范围），省远程成本；
 * 4. 图像格式归一（data URI / URL / 裸 base64）按各供应商接受度处理；
 * 5. 子类只需实现 doSubmit / doPoll / listModels / baseUrl / authHeaders。
 *
 * 子类必须尊重 req.generateAudio（仅在该供应商支持时注入音频字段）。
 */
abstract class BaseVideoProvider(
    /** 明文Key来源：生产为 KeyVault.load()，测试注入假实现。仅进鉴权 header */
    open var apiKeyProvider: suspend () -> String = { "" },
    /** 进程级共享客户端；单测注入 MockEngine */
    protected val client: HttpClient = SharedHttp.client,
    protected val rateGate: DefaultRateGate = DefaultRateGate(),
    /** 可注入睡眠以便 JVM 测试时序断言 */
    protected val sleeper: suspend (Long) -> Unit = { delay(it) },
) : VideoProvider {

    /** 生产网关 base（不含尾斜杠） */
    protected abstract val baseUrl: String

    /** 鉴权 header；默认 Bearer（Pika 覆写为 X-API-Key） */
    protected open fun authHeaders(key: String): Map<String, String> =
        mapOf(HttpHeaders.Authorization to "Bearer $key")

    protected suspend fun currentApiKey(): String = apiKeyProvider()

    protected val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val HTTP_MAX_RETRIES = 3
        const val INITIAL_BACKOFF_MS = 2_000L
        // 视频提交长退避（对齐 Agnes：429/5xx 不快重试，走长退避最多3次）
        const val SUBMIT_BACKOFF_BASE_MS = 30_000L
        const val SUBMIT_BACKOFF_CAP_MS = 180_000L
        const val SUBMIT_MAX_ATTEMPTS = 3
        const val DIMENSION_MULTIPLE = 64
    }

    // ------------------------------------------------------------------
    // 低层 HTTP（带可重试状态分类）
    // ------------------------------------------------------------------
    protected suspend fun postJson(
        path: String,
        body: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject {
        var backoff = INITIAL_BACKOFF_MS
        var lastErr: Exception? = null
        for (attempt in 0 until HTTP_MAX_RETRIES) {
            try {
                val resp = client.post("$baseUrl$path") {
                    contentType(ContentType.Application.Json)
                    authHeaders(currentApiKey()).forEach { (k, v) -> header(k, v) }
                    extraHeaders.forEach { (k, v) -> header(k, v) }
                    setBody(body.toString())
                }
                return classify(resp) { resp.bodyAsText().ifEmpty { "{}" } }
            } catch (e: java.io.IOException) {
                lastErr = e
                if (attempt == HTTP_MAX_RETRIES - 1) break
                sleeper(backoff); backoff *= 2
            }
        }
        throw ProviderError.TransientError("giving up on $path after $HTTP_MAX_RETRIES attempts: $lastErr")
    }

    protected suspend fun getJson(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject {
        var backoff = INITIAL_BACKOFF_MS
        var lastErr: Exception? = null
        for (attempt in 0 until HTTP_MAX_RETRIES) {
            try {
                val resp = client.get(url) {
                    authHeaders(currentApiKey()).forEach { (k, v) -> header(k, v) }
                    extraHeaders.forEach { (k, v) -> header(k, v) }
                }
                return classify(resp) { resp.bodyAsText().ifEmpty { "{}" } }
            } catch (e: java.io.IOException) {
                lastErr = e
                if (attempt == HTTP_MAX_RETRIES - 1) break
                sleeper(backoff); backoff *= 2
            }
        }
        throw ProviderError.TransientError("giving up on GET after $HTTP_MAX_RETRIES attempts: $lastErr")
    }

    private suspend fun classify(resp: HttpResponse, body: suspend () -> String): JsonObject {
        return when {
            resp.status.value == 200 || resp.status.value == 201 ->
                json.parseToJsonElement(body()).jsonObject
            resp.status.value == 429 ->
                throw ProviderError.QuotaError("429 Too Many Requests: ${resp.snip()}")
            resp.status.value == 401 ->
                throw ProviderError.AuthError("401 Unauthorized: ${resp.snip()}")
            resp.status.value == 400 || resp.status.value == 422 ->
                throw ProviderError.ValidationError("${resp.status.value}: ${resp.snip()}")
            resp.status.value in intArrayOf(408, 500, 502, 503, 504, 520, 522, 524) -> {
                throw ProviderError.TransientError("HTTP ${resp.status.value} retryable: ${resp.snip()}", retryable = true)
            }
            else -> throw ProviderError.TransientError("HTTP ${resp.status.value}: ${resp.snip()}")
        }
    }

    protected suspend fun HttpResponse.snip(): String = bodyAsText().take(400)

    // ------------------------------------------------------------------
    // 视频提交模板：限速门 + 参数校验 + 子类 doSubmit
    // ------------------------------------------------------------------
    override suspend fun submitVideo(req: VideoSubmitRequest): String {
        rateGate.awaitSlot()
        if (req.frameRate < 1f || req.frameRate > 60f)
            throw ProviderError.ValidationError("frame_rate ${req.frameRate} out of range [1,60]")
        return doSubmit(req)
    }

    protected abstract suspend fun doSubmit(req: VideoSubmitRequest): String
    protected abstract suspend fun doPoll(taskId: String): PollResult

    override suspend fun pollResult(taskId: String): PollResult = doPoll(taskId)

    // ------------------------------------------------------------------
    // 图像格式归一：各供应商接受度不同
    // ------------------------------------------------------------------
    protected enum class ImageAcceptance { DATA_URI, RAW_BASE64, URL_ONLY }

    protected fun normalizeImage(uri: String?, mode: ImageAcceptance): String? {
        if (uri == null) return null
        return when {
            uri.startsWith("http://") || uri.startsWith("https://") -> uri
            uri.startsWith("data:") -> when (mode) {
                ImageAcceptance.DATA_URI -> uri
                ImageAcceptance.RAW_BASE64 -> uri.substringAfter(",", "")
                ImageAcceptance.URL_ONLY -> {
                    println("[BaseVideoProvider] 跳过 data URI（供应商仅接受 URL，需先上传图床）: ${uri.take(40)}...")
                    null
                }
            }
            else -> null
        }
    }

    /** 由宽高推导宽高比（9:16 / 16:9 / 1:1） */
    protected fun aspectRatio(w: Int, h: Int): String = when {
        w >= h * 1.2 -> "16:9"
        h >= w * 1.2 -> "9:16"
        else -> "1:1"
    }

    /** 由帧数/帧率推导时长（秒），clamp 到 [min,max] */
    protected fun durationSeconds(numFrames: Int, frameRate: Float, min: Int = 3, max: Int = 15): Int {
        val secs = (numFrames / maxOf(1f, frameRate)).toInt()
        return secs.coerceIn(min, max)
    }

    // ------------------------------------------------------------------
    // 测试连通：默认仅校验 Key 非空（子类可覆写为真实轻量探活）
    // ------------------------------------------------------------------
    protected open suspend fun doValidate(key: String): Result<ConnectionInfo>? = null

    override suspend fun validateKey(key: String): Result<ConnectionInfo> {
        val saved = apiKeyProvider
        // 候选 Key 临时覆盖本次调用链（还原前仅本协程可见）
        apiKeyProvider = { key }
        return try {
            val custom = doValidate(key)
            if (custom != null) custom
            else runCatching {
                if (key.isBlank()) throw ProviderError.AuthError("API Key 为空，请先保存")
                ConnectionInfo(ok = true, detail = "key 已填写（未做在线探测）")
            }
        } finally {
            apiKeyProvider = saved
        }
    }

    /** 复用 AgnesProvider 的 64 倍数尺寸归一 */
    protected fun closestValidDimension(target: Int): Int {
        if (target <= 0) return DIMENSION_MULTIPLE
        return maxOf(DIMENSION_MULTIPLE, target / DIMENSION_MULTIPLE * DIMENSION_MULTIPLE)
    }

    /** 取首帧：优先 firstImageUri，否则 inputImages 首张；用于仅支持单参考图的供应商 */
    protected fun firstReference(req: VideoSubmitRequest): String? =
        req.firstImageUri ?: req.inputImages.firstOrNull()
}
