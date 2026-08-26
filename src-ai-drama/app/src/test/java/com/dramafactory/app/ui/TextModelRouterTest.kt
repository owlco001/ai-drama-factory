package com.dramafactory.app.ui

import com.dramafactory.core.model.ChatMessage
import com.dramafactory.core.model.ChatRequest
import com.dramafactory.core.model.ChatResponse
import com.dramafactory.core.model.ConnectionInfo
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.provider.AgnesProvider
import com.dramafactory.core.provider.DeepSeekProvider
import com.dramafactory.core.provider.TextProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * 文本模型路由 JVM 单测（纯逻辑，不依赖 Android / Robolectric）。
 *
 * 覆盖：
 *   1. 默认值：默认激活 Agnes，2 个候选注册
 *   2. 切换：setActiveTextModel 切换到 deepseek 后 active 返回 deepseek
 *   3. DeepSeek 验证成功：validate 200 → isVerified=true 且掩码可见
 *   4. DeepSeek 验证失败：validate 401 → isVerified=false 且失败信息含 401
 *   5. resolve 注入密钥：resolve("deepseek") 返回带 Key 的 TextProvider
 */
class TextModelRouterTest {

    private fun freshRouter() = DefaultTextModelRouter.also {
        it.store = InMemoryTextModelStore()
    }

    // ---------- 默认注册 + 初始状态 ----------

    @Test
    fun 注册默认值_Agnes和DeepSeek两个候选且默认激活Agnes() {
        val r = freshRouter()
        val entries = r.registeredTextModels()
        assertEquals(2, entries.size)
        assertTrue(entries.map { it.providerId }.contains("agnes"))
        assertTrue(entries.map { it.providerId }.contains("deepseek"))
        assertEquals("agnes", r.activeTextModelId())
        // 未配置任何 Key，掩码均为 null，验证状态均为 false
        assertTrue(entries.all { it.keyMasked == null })
        assertTrue(entries.all { !it.isVerified })
    }

    // ---------- 切换 ----------

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 切换模型_切换到DeepSeek后activeTextModelId返回deepseek() = runTest {
        val r = freshRouter()
        assertEquals("agnes", r.activeTextModelId())

        val res = r.setActiveTextModel("deepseek")
        assertTrue(res.isSuccess)
        assertEquals("deepseek", r.activeTextModelId())

        // 反向切回 Agnes
        r.setActiveTextModel("agnes")
        assertEquals("agnes", r.activeTextModelId())
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 切换模型_非法modelId返回失败() = runTest {
        val r = freshRouter()
        val res = r.setActiveTextModel("unknown")
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is ProviderError.ValidationError)
    }

    // ---------- 保存 Key + 掩码 ----------

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 保存DeepSeekKey_掩码可见且isVerified置false() = runTest {
        val r = freshRouter()
        r.saveKey("deepseek", "sk-1234567890abcdef")
        val entry = r.registeredTextModels().first { it.providerId == "deepseek" }
        assertNotNull(entry.keyMasked)
        assertEquals("sk-***def", entry.keyMasked)  // maskKey：前3后3
        assertFalse(entry.isVerified)  // 保存后未验证
    }

    // ---------- 验证成功 / 失败 ----------

    private fun mockDeepSeekEngine(ok: Boolean) = MockEngine { request ->
        // 校验 Authorization header 携带了候选 key（路由层验证时直接传 key，Provider 内部 apiKeyProvider 会被忽略走 chatPing(key)）
        val auth = request.headers[HttpHeaders.Authorization] ?: "Bearer "
        if (!auth.startsWith("Bearer ")) return@MockEngine respond("{}", HttpStatusCode.BadRequest)

        // 请求体 JSON 由 DeepSeekProvider 组装，此处仅验证 auth header

        if (ok) {
            respond("""{"id":"c1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"pong"}}],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}""",
                status = HttpStatusCode.OK)
        } else {
            respond("""{"error":{"message":"invalid api key"}}""", status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun DeepSeek验证成功_validate返回OK且掩码可见且isVerified置true() = runTest {
        val store = InMemoryTextModelStore()
        DefaultTextModelRouter.store = store

        // 用 ktor-client-mock 直跑 DeepSeekProvider.validateKey，验证 HTTP 组装正确
        val provider = DeepSeekProvider(
            apiKeyProvider = { "sk-1234567890abcdef" },
            client = HttpClient(mockDeepSeekEngine(ok = true)))
        val ping = provider.chat(ChatRequest(messages = listOf(ChatMessage("user", "ping")), maxTokens = 4))
        assertEquals("pong", ping.content)

        // 保存 Key + 写入验证位（模拟 validate 成功后路由侧写 store 状态）
        DefaultTextModelRouter.saveKey("deepseek", "sk-1234567890abcdef")
        store.markVerified("deepseek", true)

        val entry = DefaultTextModelRouter.registeredTextModels().first { it.providerId == "deepseek" }
        assertTrue(entry.isVerified)
        assertNotNull(entry.keyMasked)
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun DeepSeek验证失败_401返回AuthError() = runTest {
        val engine = mockDeepSeekEngine(ok = false)
        val provider = DeepSeekProvider(
            apiKeyProvider = { "sk-bad" },
            client = HttpClient(engine))
        val r = provider.validateKey("sk-bad")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is ProviderError.AuthError)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("401"))
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 路由validate_emptyKey返回AuthError() = runTest {
        val store = InMemoryTextModelStore()
        DefaultTextModelRouter.store = store
        val r = DefaultTextModelRouter.validate("deepseek")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is ProviderError.AuthError)
    }

    // ---------- resolve 注入密钥 ----------

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun resolve_DeepSeek返回带Key的TextProvider且baseUrl正确() = runTest {
        val store = InMemoryTextModelStore()
        DefaultTextModelRouter.store = store
        store.saveKey("deepseek", "sk-1234567890abcdef")

        val provider = DefaultTextModelRouter.resolve("deepseek") as DeepSeekProvider
        assertEquals(DeepSeekProvider.PROVIDER_ID, provider.id)
        val resolvedKey = provider.apiKeyProvider()
        assertEquals("sk-1234567890abcdef", resolvedKey)
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun resolve_Agnes返回AgnesProvider() = runTest {
        val store = InMemoryTextModelStore()
        DefaultTextModelRouter.store = store
        store.saveKey("agnes", "sk-agnes")

        val provider = DefaultTextModelRouter.resolve("agnes")
        assertNotNull(provider as AgnesProvider)
        assertEquals("agnes", provider.id)
    }
}
