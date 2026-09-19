package com.dramafactory.core.provider

import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.model.ConnectionInfo

/** v1.8.9 隔离实例实现，便于测试与多端隔离 */
class DefaultTextModelRouterImpl(private val store: TextModelStore) : TextModelRouter {

    /** v1.8.8：Agnes 服务站点。构造后由 AppGraph/hydrate 同步持久值，保证冷启动正确。 */
    private var _agnesRegion: AgnesRegion = AgnesRegion.INTERNATIONAL

    override fun registeredTextModels(): List<TextModelEntry> {
        val candidates = listOf(
            TextModelEntry(AgnesProvider.MODEL_TEXT, "Agnes 文本 3.0 Flash", "agnes", AgnesProvider.BASE_URL, null, false),
            TextModelEntry(DeepSeekProvider.MODEL, "DeepSeek Chat", DeepSeekProvider.PROVIDER_ID, DeepSeekProvider.BASE_URL, null, false)
        )
        return candidates.map { base ->
            base.copy(
                keyMasked = store.masked(base.providerId),
                isVerified = store.isVerified(base.providerId)
            )
        }
    }

    override fun activeTextModelId(): String = store.loadActiveModel()

    override fun currentRegion(): AgnesRegion = _agnesRegion

    suspend fun setAgnesRegion(region: AgnesRegion) {
        _agnesRegion = region
        store.saveAgnesRegion(region)
    }

    override suspend fun setActiveTextModel(modelId: String): Result<Unit> = runCatching {
        val entry = registeredTextModels().firstOrNull { it.providerId == modelId || it.modelId == modelId }
            ?: throw ProviderError.ValidationError("未知的文本模型: $modelId")
        store.saveActiveModel(entry.providerId)
    }

    override suspend fun saveKey(modelId: String, key: String): Result<Unit> = runCatching {
        val entry = registeredTextModels().firstOrNull { it.providerId == modelId || it.modelId == modelId }
            ?: throw ProviderError.ValidationError("未知的文本模型: $modelId")
        store.saveKey(entry.providerId, key)
        store.markVerified(entry.providerId, false)
    }

    override suspend fun hasAnyKey(): Boolean =
        registeredTextModels().any { entry ->
            runCatching { store.loadKey(entry.providerId) }.getOrNull()?.isNotBlank() == true
        }

    override suspend fun validate(modelId: String, key: String?): Result<ConnectionInfo> {
        val entry = registeredTextModels().firstOrNull { it.providerId == modelId || it.modelId == modelId }
            ?: return Result.failure(ProviderError.ValidationError("未知的文本模型: $modelId"))
        val useKey = key ?: store.loadKey(entry.providerId)
        if (useKey.isBlank()) return Result.failure(ProviderError.AuthError("API Key 为空，请先保存"))
        val result = when (entry.providerId) {
            "agnes" -> AgnesProvider(apiKeyProvider = { useKey }, region = _agnesRegion).validateKey(useKey)
            DeepSeekProvider.PROVIDER_ID -> DeepSeekProvider(apiKeyProvider = { useKey }).validateKey(useKey)
            else -> Result.failure(ProviderError.ValidationError("暂不支持的 provider: ${entry.providerId}"))
        }
        result.onSuccess { store.markVerified(entry.providerId, true) }
        result.onFailure { store.markVerified(entry.providerId, false) }
        return result
    }

    override suspend fun resolve(modelId: String): TextProvider {
        val entry = registeredTextModels().firstOrNull { it.providerId == modelId || it.modelId == modelId }
            ?: throw ProviderError.ValidationError("未知的文本模型: $modelId")
        return when (entry.providerId) {
            "agnes" -> AgnesProvider(apiKeyProvider = { store.loadKey("agnes") }, region = _agnesRegion)
            DeepSeekProvider.PROVIDER_ID -> DeepSeekProvider(apiKeyProvider = { store.loadKey(DeepSeekProvider.PROVIDER_ID) })
            else -> throw ProviderError.ValidationError("暂不支持的 provider: ${entry.providerId}")
        }
    }
}