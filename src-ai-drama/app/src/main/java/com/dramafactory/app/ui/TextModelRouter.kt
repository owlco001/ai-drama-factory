package com.dramafactory.app.ui

import com.dramafactory.core.model.ConnectionInfo
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.provider.AgnesProvider
import com.dramafactory.core.provider.DeepSeekProvider
import com.dramafactory.core.provider.KeyVault
import com.dramafactory.core.provider.TextProvider

/**
 * 文本通道模型路由（架构 T014 §2.3 接口签名）。
 *
 * 注册：Agnes 自动选模 + DeepSeek Chat 两个默认候选。
 * Key 各自独立保存（T014 决议 Q4 b：文本/视频 Key 分池，同一 provider 内 text/video 也不同 configId）。
 *
 * 数据层在 :app（架构 2.3 规定），业务纯 Kotlin —— JVM 可单测。
 */
interface TextModelRouter {

    /** 已注册且可用作"文本大脑"的模型列表（设置页单选数据来源） */
    fun registeredTextModels(): List<TextModelEntry>

    /** 当前生效的文本模型 id（providerId 语义，如 "agnes" / "deepseek"） */
    fun activeTextModelId(): String

    /** 切换当前生效模型（Key 各自保存，随时互切，Q4） */
    suspend fun setActiveTextModel(modelId: String): Result<Unit>

    /** 保存某个模型对应的 Key（未测试过也允许落库，但 isVerified 置 false，须再 validate） */
    suspend fun saveKey(modelId: String, key: String): Result<Unit>

    /** 测试连通（对应设置页「测试连通」按钮；key 为空时走 store 里已存的 Key） */
    suspend fun validate(modelId: String, key: String? = null): Result<ConnectionInfo>

    /** 解析为一次可注入密钥的 TextProvider（AiOrchestrator.run 走这条路） */
    suspend fun resolve(modelId: String): TextProvider

    data class TextModelEntry(
        val modelId: String,         // "deepseek-chat" / "agnes-2.5-flash" / ...
        val label: String,           // UI 展示：DeepSeek / Agnes 文本 2.5 Flash
        val providerId: String,      // "deepseek" / "agnes" / "openai_compat"
        val baseUrl: String,         // "https://api.deepseek.com/v1"
        val keyMasked: String?,      // 已存 Key 掩码；null=未配置
        val isVerified: Boolean,     // 是否通过 validate
    )
}

/** 底层存储：活跃模型 + 每 provider 的 Key + 验证状态 */
interface TextModelStore {
    fun loadActiveModel(): String
    suspend fun saveActiveModel(modelId: String)
    suspend fun loadKey(providerId: String): String
    suspend fun saveKey(providerId: String, key: String)
    fun masked(providerId: String): String?
    fun isVerified(providerId: String): Boolean
    suspend fun markVerified(providerId: String, ok: Boolean)
}

/**
 * 默认路由 —— 生产注入 AndroidKeyVault，测试注入内存 store 直跑 JVM。
 */
object DefaultTextModelRouter : TextModelRouter {

    /** 测试侧可换 InMemoryTextModelStore；生产由 AppGraph 换 Android 实现 */
    var store: TextModelStore = InMemoryTextModelStore()

    private val CANDIDATES = listOf(
        TextModelEntry(
            modelId = AgnesProvider.MODEL_TEXT,
            label = "Agnes 文本 2.5 Flash",
            providerId = "agnes",
            baseUrl = AgnesProvider.BASE_URL,
            keyMasked = null,
            isVerified = false,
        ),
        TextModelEntry(
            modelId = DeepSeekProvider.MODEL,
            label = "DeepSeek Chat",
            providerId = DeepSeekProvider.PROVIDER_ID,
            baseUrl = DeepSeekProvider.BASE_URL,
            keyMasked = null,
            isVerified = false,
        ),
    )

    private fun resolveId(id: String): TextModelEntry? =
        CANDIDATES.firstOrNull { it.providerId == id || it.modelId == id }

    override fun registeredTextModels(): List<TextModelEntry> {
        return CANDIDATES.map { base ->
            base.copy(
                keyMasked = store.masked(base.providerId),
                isVerified = store.isVerified(base.providerId),
            )
        }
    }

    override fun activeTextModelId(): String = store.loadActiveModel()

    override suspend fun setActiveTextModel(modelId: String): Result<Unit> = runCatching {
        val entry = resolveId(modelId)
            ?: throw ProviderError.ValidationError("未知的文本模型: $modelId")
        store.saveActiveModel(entry.providerId)
    }

    override suspend fun saveKey(modelId: String, key: String): Result<Unit> = runCatching {
        val entry = resolveId(modelId)
            ?: throw ProviderError.ValidationError("未知的文本模型: $modelId")
        store.saveKey(entry.providerId, key)
        store.markVerified(entry.providerId, false) // 保存后需重新验证
    }

    override suspend fun validate(modelId: String, key: String? = null): Result<ConnectionInfo> =
        runCatching {
            val entry = resolveId(modelId)
                ?: throw ProviderError.ValidationError("未知的文本模型: $modelId")
            val useKey = key ?: store.loadKey(entry.providerId)
            if (useKey.isBlank()) throw ProviderError.AuthError("API Key 为空，请先保存")
            val r = when (entry.providerId) {
                "agnes" -> AgnesProvider(apiKeyProvider = { useKey }).validateKey(useKey)
                DeepSeekProvider.PROVIDER_ID -> DeepSeekProvider(apiKeyProvider = { useKey }).validateKey(useKey)
                else -> throw ProviderError.ValidationError("暂不支持的 provider: ${entry.providerId}")
            }
            r
        }.onSuccess { store.markVerified(resolveId(modelId)!!.providerId, true) }
            .onFailure { e ->
                val resolved = resolveId(modelId)
                resolved?.let { store.markVerified(it.providerId, false) }
                if (e !is ProviderError) Result.failure(ProviderError.TransientError(e.message ?: "validate 失败"))
            }.getOrNull()
            ?: Result.failure(ProviderError.TransientError("validate 异常"))

    override suspend fun resolve(modelId: String): TextProvider {
        val entry = resolveId(modelId)
            ?: throw ProviderError.ValidationError("未知的文本模型: $modelId")
        return when (entry.providerId) {
            "agnes" -> AgnesProvider(apiKeyProvider = { store.loadKey("agnes") })
            DeepSeekProvider.PROVIDER_ID -> DeepSeekProvider(apiKeyProvider = { store.loadKey(DeepSeekProvider.PROVIDER_ID) })
            else -> throw ProviderError.ValidationError("暂不支持的 provider: ${entry.providerId}")
        }
    }
}

/** 内存实现：JVM 单测默认走这个；生产 AppGraph 会换成 AndroidKeyVault 包装版本 */
class InMemoryTextModelStore(override var keyVault: KeyVault? = null) : TextModelStore {
    @Volatile private var activeModelId = "agnes"
    private val keys = mutableMapOf<String, String>()
    private val verified = mutableSetOf<String>()

    override fun loadActiveModel(): String = activeModelId
    override suspend fun saveActiveModel(modelId: String) { activeModelId = modelId }

    override suspend fun loadKey(providerId: String): String =
        keys[providerId] ?: keyVault?.load("text-$providerId").orEmpty()

    override suspend fun saveKey(providerId: String, key: String) {
        keys[providerId] = key
        keyVault?.save("text-$providerId", providerId, key)
    }

    override fun masked(providerId: String): String? =
        keys[providerId]?.let { AgnesProvider.maskKey(it) }
            ?: keyVault?.masked("text-$providerId")?.takeIf { it != "<empty>" }

    override fun isVerified(providerId: String): Boolean = verified.contains(providerId)

    override suspend fun markVerified(providerId: String, ok: Boolean) {
        if (ok) verified.add(providerId) else verified.remove(providerId)
    }
}
