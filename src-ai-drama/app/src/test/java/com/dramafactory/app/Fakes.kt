package com.dramafactory.app

import com.dramafactory.core.model.ConnectionInfo
import com.dramafactory.core.model.BudgetUsage
import com.dramafactory.core.model.QueueSnapshot
import com.dramafactory.core.provider.KeyVault
import com.dramafactory.core.provider.VideoProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 测试假实现集合 —— 仅JVM单测用 */
class FakeVault : KeyVault {
    val saved = mutableMapOf<String, String>()
    override suspend fun save(configId: String, providerId: String, plainKey: String) { saved[configId] = plainKey }
    override suspend fun load(configId: String): String = saved.getValue(configId)
    override fun masked(configId: String): String =
        (saved[configId] ?: "").let { k ->
            when { k.isEmpty() -> "<empty>"; k.length <= 8 -> "***"; else -> "${k.take(3)}***${k.takeLast(3)}" }
        }
    override suspend fun delete(configId: String) { saved.remove(configId) }
}

class FakeVideoProvider(private val ok: Boolean) : VideoProvider {
    override val id = "fake"
    override suspend fun validateKey(key: String): Result<ConnectionInfo> =
        if (ok) Result.success(ConnectionInfo(ok = true, latencyMs = 42L))
        else Result.failure(com.dramafactory.core.model.ProviderError.AuthError("401 · API Key无效"))
    override fun listModels() = emptyList<com.dramafactory.core.model.ModelSpec>()
    override suspend fun submitVideo(req: com.dramafactory.core.model.VideoSubmitRequest): String = "t1"
    override suspend fun pollResult(providerTaskId: String): com.dramafactory.core.model.PollResult =
        com.dramafactory.core.model.PollResult.Completed("http://v/$providerTaskId.mp4")
}

class FakeQueue : com.dramafactory.core.provider.RenderQueue {
    val snapshot = MutableStateFlow(QueueSnapshot())
    override val state: StateFlow<QueueSnapshot> get() = snapshot
    var lastResume: Pair<Int, Boolean>? = null   // (调用次数, confirmedByUser)
    private var resumeCount = 0
    override suspend fun enqueueEpisode(episodeId: String, shots: List<com.dramafactory.core.model.ShotMeta>) {}
    override suspend fun pause() {}
    override suspend fun resume(confirmedByUser: Boolean) { lastResume = ++resumeCount to confirmedByUser }
    override fun cancelShot(shotId: String) {}
}

class FakeBudget : com.dramafactory.core.provider.BudgetGuard {
    private val _usage = MutableStateFlow(BudgetUsage(50, 50))
    override val usage: StateFlow<BudgetUsage> get() = _usage
    override fun canSubmit(projectId: String) = false
    override fun consumeSubmitted(projectId: String) {}
}
