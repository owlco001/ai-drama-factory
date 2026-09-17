package com.dramafactory.app.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 存储状态（P0 持久化可靠性）。
 *
 * Room 初始化/迁移失败不得再以 no-op 的 Broken DAO 静默运行——存储不可用必须是显式、
 * 可观察、可阻断的状态。AI 创建/修改/生成/渲染动作在该状态下不得返回成功。
 */
sealed interface StorageState {
    data object Ready : StorageState

    data class Blocked(
        val code: String,              // ROOM_INIT_FAILED / MIGRATION_FAILED / DAO_UNAVAILABLE
        val userMessage: String,
        val diagnosticId: String,      // 脱敏诊断 id，只记 id 不记明文
    ) : StorageState
}

/** 存储不可用受控异常：DAO 层 / 执行器在非 Ready 时抛出。 */
class StorageUnavailableException(
    val code: String,
    val userMessage: String,
    val diagnosticId: String,
) : IllegalStateException("存储不可用($code): $userMessage")

/** 存储闸门：暴露当前状态，入口在 DAO/Provider 前调用 [requireReady] / [blockedState]。 */
interface StorageGuard {
    val state: StateFlow<StorageState>
    fun current(): StorageState
    /** 非 Ready 抛 [StorageUnavailableException]。 */
    fun requireReady()
    /** 返回非空即被阻断（null = Ready）；供入口返回失败而不抛。 */
    fun blockedState(): StorageState.Blocked?
}

class DefaultStorageGuard : StorageGuard {
    private val _state = MutableStateFlow<StorageState>(StorageState.Ready)
    override val state: StateFlow<StorageState> = _state.asStateFlow()
    override fun current(): StorageState = _state.value
    override fun blockedState(): StorageState.Blocked? = _state.value as? StorageState.Blocked
    override fun requireReady() {
        val b = _state.value as? StorageState.Blocked
        if (b != null) throw StorageUnavailableException(b.code, b.userMessage, b.diagnosticId)
    }

    internal fun block(code: String, userMessage: String, diagnosticId: String) {
        _state.value = StorageState.Blocked(code, userMessage, diagnosticId)
    }

    internal fun ready() {
        _state.value = StorageState.Ready
    }
}