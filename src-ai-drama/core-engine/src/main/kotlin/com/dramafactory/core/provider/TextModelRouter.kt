package com.dramafactory.core.provider

import com.dramafactory.core.model.ConnectionInfo
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.provider.AgnesProvider
import com.dramafactory.core.provider.DeepSeekProvider
import com.dramafactory.core.provider.KeyVault
import com.dramafactory.core.provider.TextProvider

/** 文本通道默认激活的 provider（候选表首项 Agnes 为 MVP 唯一供应商） */
const val DEFAULT_ACTIVE_TEXT_MODEL = "agnes"

/** 已注册文本模型条目（T014 §2.3） */
data class TextModelEntry(
    val modelId: String,         // "deepseek-chat" / "agnes-2.5-flash" / ...
    val label: String,           // UI 展示：DeepSeek / Agnes 文本 2.5 Flash
    val providerId: String,      // "deepseek" / "agnes" / "openai_compat"
    val baseUrl: String,         // "https://api.deepseek.com/v1"
    val keyMasked: String?,      // 已存 Key 掩码；null=未配置
    val isVerified: Boolean,     // 是否通过 validate
)

/**
 * 文本通道模型路由（架构 T014 §2.3 接口签名）。
 *
 * 注册：Agnes 自动选模 + DeepSeek Chat 两个默认候选。
 * Key 各自独立保存（T014 决议 Q4 b：文本/视频 Key 分池）。
 *
 * 已从安卓 :app 上移到 core-engine（v1.4.9 桌面版共享）。
 */
interface TextModelRouter {
    fun registeredTextModels(): List<TextModelEntry>
    fun activeTextModelId(): String
    suspend fun setActiveTextModel(modelId: String): Result<Unit>
    suspend fun saveKey(modelId: String, key: String): Result<Unit>
    suspend fun validate(modelId: String, key: String? = null): Result<ConnectionInfo>
    suspend fun resolve(modelId: String): TextProvider
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
 * 默认路由 —— 生产注入 AndroidKeyVault（安卓）或 FileKeyVault（桌面），测试注入内存 store 直跑 JVM。
 */
object DefaultTextModelRouter : TextModelRouter {

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

    override fun registeredTextModels(): List<TextModelEntry> =
        CANDIDATES.map { base ->
            base.copy(
                keyMasked = store.masked(base.providerId),
                isVerified = store.isVerified(base.providerId),
            )
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
        store.markVerified(entry.providerId, false)
    }

    override suspend fun validate(modelId: String, key: String?): Result<ConnectionInfo> {
        val entry = resolveId(modelId)
            ?: return Result.failure(ProviderError.ValidationError("未知的文本模型: $modelId"))
        val useKey = key ?: store.loadKey(entry.providerId)
        if (useKey.isBlank()) return Result.failure(ProviderError.AuthError("API Key 为空，请先保存"))
        val result = when (entry.providerId) {
            "agnes" -> AgnesProvider(apiKeyProvider = { useKey }).validateKey(useKey)
            DeepSeekProvider.PROVIDER_ID -> DeepSeekProvider(apiKeyProvider = { useKey }).validateKey(useKey)
            else -> Result.failure(ProviderError.ValidationError("暂不支持的 provider: ${entry.providerId}"))
        }
        result.onSuccess { store.markVerified(entry.providerId, true) }
        result.onFailure { store.markVerified(entry.providerId, false) }
        return result
    }

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

/** 内存实现：JVM 单测默认走这个；生产 AppGraph 注入带 AndroidKeyVault 的实例 */
class InMemoryTextModelStore(private var keyVault: KeyVault? = null) : TextModelStore {
    // v1.8.2 修复：默认激活模型此前写成 "deepseek"，与接口契约（默认候选表首项 = Agnes）
    // 及 TextModelRouterTest 的期望都不一致 —— 用户未手动切换时文本通道实际走 DeepSeek，
    // 而设置页 UI 按 Agnes 为默认渲染，状态与展示对不上。
    @Volatile private var activeModelId = DEFAULT_ACTIVE_TEXT_MODEL
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
