package com.dramafactory.app.ui

import com.dramafactory.core.model.ConnectionInfo
import com.dramafactory.core.provider.KeyVault
import com.dramafactory.core.provider.VideoProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * 设置页ViewModel核心逻辑（P0）——与Android解耦，JVM可单测。
 *
 * 职责：视频模型供应商选择（MVP仅Agnes）+ API Key保存（KeyVault）+ 测试连通。
 * 语义约束：
 * - Key明文只在输入框存在，测试/保存后立即清出UI状态；
 * - 测试连通用「候选Key」直接验证（不落库），通过后才允许保存——避免坏Key覆盖好Key；
 * - UI只见masked掩码（sk-***abc），永不回显明文。
 */
class SettingsLogic(
    private val videoProvider: VideoProvider,
    private val keyVault: KeyVault,
    private val configId: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** 页面状态 */
    data class UiState(
        val providerLabel: String = "Agnes（MVP唯一供应商）",
        val keyInput: String = "",              // 输入框明文（仅输入期间）
        val maskedSaved: String? = null,        // 已存Key掩码；null=未配置
        val testing: Boolean = false,
        val testResult: TestResult? = null,
        val saved: Boolean = false,             // 保存成功一次性提示
    )

    sealed interface TestResult {
        data class Success(val latencyMs: Long) : TestResult
        data class Failure(val message: String) : TestResult
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> get() = _state

    /** 进入页面时刷新已存Key掩码 */
    suspend fun refresh() {
        val masked = runCatching { keyVault.masked(configId) }
            .getOrNull()?.takeIf { it != "<empty>" }
        _state.value = _state.value.copy(maskedSaved = masked, saved = false)
    }

    fun onKeyChanged(text: String) {
        _state.value = _state.value.copy(keyInput = text, saved = false)
    }

    /**
     * 「测试连通」：用候选Key调validateKey（最小成本请求）。
     * 成功→显示延迟；失败→显示错误分类信息（401/429/网络等）。
     */
    suspend fun testConnection() {
        val key = _state.value.keyInput.trim()
        if (key.isEmpty()) {
            _state.value = _state.value.copy(testResult = TestResult.Failure("请先输入API Key"))
            return
        }
        _state.value = _state.value.copy(testing = true, testResult = null)
        val result = withContext(io) { videoProvider.validateKey(key) }
        _state.value = when {
            result.isSuccess -> {
                val info = result.getOrThrow()
                _state.value.copy(testing = false,
                    testResult = if (info.ok) TestResult.Success(info.latencyMs)
                                 else TestResult.Failure(info.detail.ifEmpty { "连通失败" }))
            }
            else -> _state.value.copy(testing = false,
                testResult = TestResult.Failure(result.exceptionOrNull()?.message ?: "未知错误"))
        }
    }

    /**
     * 保存到KeyVault。约定：测试通过后才允许保存（防坏Key覆盖好Key）；
     * 若用户跳过测试强行保存也放行（尊重用户），但返回false由UI提示风险。
     * 保存成功后立即清空输入框明文并刷新掩码。
     */
    suspend fun saveKey(forceWithoutTest: Boolean = false): Boolean {
        val key = _state.value.keyInput.trim()
        if (key.isEmpty()) return false
        val tested = (_state.value.testResult as? TestResult.Success) != null
        if (!tested && !forceWithoutTest) return false
        withContext(io) { keyVault.save(configId, videoProvider.id, key) }
        _state.value = _state.value.copy(keyInput = "", saved = true)
        refresh()
        return true
    }

    /** 删除已存Key（换Key前清理） */
    suspend fun deleteKey() {
        withContext(io) { keyVault.delete(configId) }
        _state.value = _state.value.copy(maskedSaved = null, testResult = null)
    }
}
