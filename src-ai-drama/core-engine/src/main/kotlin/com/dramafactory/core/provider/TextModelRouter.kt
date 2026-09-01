package com.dramafactory.core.provider

import com.dramafactory.core.model.ConnectionInfo
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.provider.AgnesProvider
import com.dramafactory.core.provider.DeepSeekProvider
import com.dramafactory.core.provider.KeyVault
import com.dramafactory.core.provider.TextProvider

/** 文本通道默认激活的 provider（候选表首项 Agnes 为 MVP 唯一供应商） */
const val DEFAULT_ACTIVE_TEXT_MODEL = "agnes"

/** 持久化激活文本模型的独立 configId（借 KeyVault 落地，区别于 text-<provider> 的 Key 存储） */
const val PREF_ACTIVE_TEXT_MODEL = "text-active-model"

/** v1.8.8：Agnes 服务站点偏好（agnes-region，值= AgnesRegion.name：INTERNATIONAL / CHINA） */
const val PREF_AGNES_REGION = "agnes-region"

/**
 * v1.8.9：Agnes Key 按 region 分池——中国站与国际站 **不共用 API Key**。
 * 中国站的 configId 在 "agnes" 段后插 "-cn"：
 *   text-agnes → text-agnes-cn；agnes → agnes-cn；agnes-video → agnes-cn-video；agnes-image → agnes-cn-image。
 * 非 agnes 系（custom-video 等）与 INTERNATIONAL 原样返回。不做跨池回退（国际站 Key 在中国站必然 401）。
 */
fun agnesScopedConfigId(configId: String, region: AgnesRegion): String =
    if (region != AgnesRegion.CHINA) configId
    else when {
        configId.startsWith("text-agnes") -> "text-agnes-cn" + configId.removePrefix("text-agnes")
        configId.startsWith("agnes") -> "agnes-cn" + configId.removePrefix("agnes")
        else -> configId
    }

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
    /** v1.8.4：从 KeyVault 预热持久化的激活模型；AppGraph.init 阶段调用一次，无值则保留内存默认 */
    suspend fun hydrateActive()
    suspend fun loadKey(providerId: String): String
    suspend fun saveKey(providerId: String, key: String)
    fun masked(providerId: String): String?
    fun isVerified(providerId: String): Boolean
    suspend fun markVerified(providerId: String, ok: Boolean)
    /** v1.8.8：Agnes 服务站点（无值/异常时回退 INTERNATIONAL） */
    fun loadAgnesRegion(): AgnesRegion
    suspend fun saveAgnesRegion(region: AgnesRegion)
}

/**
 * 默认路由 —— 生产注入 AndroidKeyVault（安卓）或 FileKeyVault（桌面），测试注入内存 store 直跑 JVM。
 */
object DefaultTextModelRouter : TextModelRouter {

    var store: TextModelStore = InMemoryTextModelStore()

    /** v1.8.8：Agnes 服务站点（影响 text 通道 + AppGraph 据此构建 video/image provider）。
     * 默认国际站；AppGraph.init 从 KeyVault 预热为持久值。 */
    var agnesRegion: AgnesRegion = AgnesRegion.INTERNATIONAL

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
            "agnes" -> AgnesProvider(apiKeyProvider = { useKey }, region = agnesRegion).validateKey(useKey)
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
            "agnes" -> AgnesProvider(apiKeyProvider = { store.loadKey("agnes") }, region = agnesRegion)
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
    /** v1.8.8：Agnes 站点内存缓存（KeyVault.load 为 suspend，非 suspend 读取走缓存；
     * 持久值由 AppGraph.init 经 keyVault 预热到 DefaultTextModelRouter.agnesRegion） */
    @Volatile private var agnesRegionCache = AgnesRegion.INTERNATIONAL

    override fun loadActiveModel(): String = activeModelId

    override suspend fun saveActiveModel(modelId: String) {
        activeModelId = modelId
        // v1.8.4：落盘，防止重启 App 后回退默认值 agnes。
        // providerId 传空（语义上这不是一条 Key，只是偏好串）。
        runCatching { keyVault?.save(PREF_ACTIVE_TEXT_MODEL, "", modelId) }
    }

    override suspend fun hydrateActive() {
        // v1.8.4：AppGraph.init 同步预热；无值 / KeyVault 异常时保留内存默认（agnes）。
        runCatching { keyVault?.load(PREF_ACTIVE_TEXT_MODEL) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { activeModelId = it }
    }

    override suspend fun loadKey(providerId: String): String =
        keys[memKey(providerId)]
            ?: keyVault?.load(agnesScopedConfigId("text-$providerId", agnesRegionCache)).orEmpty()

    override suspend fun saveKey(providerId: String, key: String) {
        keys[memKey(providerId)] = key
        keyVault?.save(agnesScopedConfigId("text-$providerId", agnesRegionCache), providerId, key)
    }

    override fun masked(providerId: String): String? =
        keys[memKey(providerId)]?.let { AgnesProvider.maskKey(it) }
            ?: keyVault?.masked(agnesScopedConfigId("text-$providerId", agnesRegionCache))?.takeIf { it != "<empty>" }

    override fun isVerified(providerId: String): Boolean = verified.contains(memKey(providerId))
    override suspend fun markVerified(providerId: String, ok: Boolean) {
        val k = memKey(providerId)
        if (ok) verified.add(k) else verified.remove(k)
    }

    /** v1.8.9：内存态键随 region 分池（中国站 agnes→agnes-cn），与 KeyVault configId 同一规则 */
    private fun memKey(providerId: String): String =
        if (providerId == "agnes") agnesScopedConfigId(providerId, agnesRegionCache) else providerId

    override fun loadAgnesRegion(): AgnesRegion = agnesRegionCache

    override suspend fun saveAgnesRegion(region: AgnesRegion) {
        agnesRegionCache = region
        runCatching { keyVault?.save(PREF_AGNES_REGION, "", region.name) }
    }
}
