package com.dramafactory.core.provider

import com.dramafactory.core.model.ChatMessage
import com.dramafactory.core.model.ChatRequest
import com.dramafactory.core.model.ChatResponse
import com.dramafactory.core.model.ConnectionInfo
import com.dramafactory.core.model.ProviderError
import io.ktor.client.HttpClient
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * DeepSeekProvider —— OpenAI 兼容的文本通道实现（T014 决议Q4）。
 *
 * 只暴露 chat 通道（架构 2.3：文本模型作为"大脑"，不承载视频/图像提交）：
 * - base_url = https://api.deepseek.com/v1
 * - model = deepseek-chat
 * - 请求体对齐 AgnesProvider.chat()：chat/completions，enable_thinking=false（避免 reasoning 吃空 content）
 * - 仅实现 TextProvider 接口；不提供 VideoProvider/ImageProvider 通道
 *
 * Key 由调用方通过 apiKeyProvider 注入；validateKey 使用最小成本 chat ping。
 * JVM 可测试：client 可注入 ktor-client-mock。
 */
class DeepSeekProvider(
    /** 明文Key来源：生产为KeyVault.load("text-deepseek")，测试可注入假实现。仅进 Authorization header */
    var apiKeyProvider: suspend () -> String = { "" },
    private val client: HttpClient = HttpClient { /* 默认 OkHttp 引擎 */ },
) : TextProvider {

    companion object {
        const val BASE_URL = "https://api.deepseek.com/v1"
        const val MODEL = "deepseek-chat"
        const val PROVIDER_ID = "deepseek"
        private const val HTTP_MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 2_000L
        const val TEXT_INPUT_TOKEN_LIMIT = 230_000L

        /** 中文≈1token、ASCII≈4字符1token 的保守估算（对齐 AgnesProvider.estimateTokens） */
        fun estimateTokens(text: String): Long {
            var cjk = 0L
            for (c in text) if (c.code > 0x2E80) cjk++
            return cjk + (text.length - cjk) / 4
        }
    }

    override val id: String = PROVIDER_ID

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------
    // 低层HTTP：POST /chat/completions，HTTP 状态分类对齐 AgnesProvider.postJson
    // ------------------------------------------------------------------
    private suspend fun postJson(body: JsonObject): JsonObject {
        var backoff = INITIAL_BACKOFF_MS
        var lastErr: Exception? = null
        for (attempt in 0 until HTTP_MAX_RETRIES) {
            try {
                val resp = client.post("$BASE_URL/chat/completions") {
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
                        lastErr = ProviderError.TransientError("HTTP ${resp.status.value} retryable")
                        kotlinx.coroutines.delay(backoff); backoff *= 2; continue
                    }
                    else -> throw ProviderError.TransientError("HTTP ${resp.status.value}: ${resp.snip()}")
                }
            } catch (e: java.io.IOException) {
                lastErr = e
                val reason = "${e.javaClass.simpleName}: ${e.message?.take(120) ?: ""}"
                println("DeepSeekProvider postJson attempt=${attempt + 1}/$HTTP_MAX_RETRIES $reason")
                if (attempt == HTTP_MAX_RETRIES - 1) break
                kotlinx.coroutines.delay(backoff); backoff *= 2
            }
        }
        val errInfo = if (lastErr != null) {
            "${lastErr!!.javaClass.simpleName}: ${lastErr!!.message?.take(120) ?: ""}"
        } else "no error captured"
        throw ProviderError.TransientError("giving up after $HTTP_MAX_RETRIES attempts: $errInfo")
    }

    private fun HttpResponse.isRetryable(): Boolean =
        status.value in intArrayOf(408, 500, 502, 503, 504, 520, 522, 524)

    private suspend fun HttpResponse.snip(): String = bodyAsText().take(400)

    // ------------------------------------------------------------------
    // 最小成本 chat ping（validateKey 用）
    // ------------------------------------------------------------------
    private suspend fun chatPing() {
        val body = buildJsonObject {
            put("model", MODEL)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "ping") })
            })
            put("max_tokens", 4)
            put("temperature", 0.0)
            put("chat_template_kwargs", buildJsonObject { put("enable_thinking", false) })
        }
        postJson(body)
    }

    /** 验证 Key（架构 §2.3：SettingsPage「测试连通」按钮对接） */
    suspend fun validateKey(key: String): Result<ConnectionInfo> {
        val saved = apiKeyProvider()
        apiKeyProvider = { key }
        val t0 = System.currentTimeMillis()
        val r = runCatching { chatPing() }
        apiKeyProvider = { saved }
        return when {
            r.isSuccess -> Result.success(
                ConnectionInfo(ok = true, latencyMs = System.currentTimeMillis() - t0, detail = "chat ping ok"))
            else -> {
                val e = r.exceptionOrNull()
                Result.failure(e as? ProviderError ?: ProviderError.TransientError("chat ping failed: ${e?.message}"))
            }
        }
    }

    // ------------------------------------------------------------------
    // TextProvider.chat —— enable_thinking=false 约定
    // ------------------------------------------------------------------
    override suspend fun chat(req: ChatRequest): ChatResponse {
        val total = req.messages.sumOf { estimateTokens(it.content) }
        if (total >= TEXT_INPUT_TOKEN_LIMIT)
            throw ProviderError.ValidationError(
                "context overload: ~${total / 1000}K tokens exceeds ${TEXT_INPUT_TOKEN_LIMIT / 1000}K safe limit")
        val body = buildJsonObject {
            put("model", MODEL)
            put("messages", buildJsonArray {
                req.messages.forEach { m ->
                    if (m.imageUrl != null) {
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
            put("chat_template_kwargs", buildJsonObject { put("enable_thinking", false) })
        }
        val out = postJson(body)
        val content = out["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: throw ProviderError.ValidationError("unexpected chat response: ${out.toString().take(400)}")
        return ChatResponse(content, out.toString())
    }
}
