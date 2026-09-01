package com.dramafactory.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.dramafactory.app.ui.theme.DramaShapes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 统一瞬时反馈通道（替代此前各页各搞一套、甚至可能用 Toast 的碎片做法）。
 *
 * 此前全 App 没有全局 Snackbar/Toast：持久结果靠行内 StatusCard，一次性确认（已保存/已删除/
 * 复制成功）则完全没有统一出口。本文件把"瞬时反馈"收敛成唯一来源：
 *   - [DramaSnackbarController.show] 非挂起，页面任意位置拿到 [LocalDramaSnackbar] 即可调用；
 *   - [DramaSnackbarHost] 是挂在 DramaApp Scaffold 上的唯一宿主，视觉走 DramaFactory 规格
 *     （surfaceContainerHigh 底 / onSurface 字 / primary 动作色 / medium 圆角），深色下对比度达标。
 *
 * 规则（页面遵循）：
 *   - 会一直留着看的结果（生成中、完成、失败需排查）→ 行内 StatusCard；
 *   - 一次性确认（已保存 / 已删除 / 已复制 / 连通成功）→ 走 Snackbar。
 */
class DramaSnackbarController(
    private val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    fun show(
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        withDismissAction: Boolean = false,
        duration: SnackbarDuration = SnackbarDuration.Short,
    ) {
        scope.launch {
            val result = hostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = withDismissAction,
                duration = duration,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

/** 页面内通过 `LocalDramaSnackbar.current.show("已保存")` 触发统一 Snackbar */
val LocalDramaSnackbar = compositionLocalOf<DramaSnackbarController> {
    error("LocalDramaSnackbar 未提供：请用 CompositionLocalProvider 在 DramaApp 注入 controller。")
}

/** 挂在 Scaffold 上的唯一 Snackbar 宿主，视觉规格统一走 DramaFactory 主题 */
@Composable
fun DramaSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = DramaShapes.medium,
            containerColor = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
            actionColor = scheme.primary,
            dismissActionContentColor = scheme.onSurfaceVariant,
        )
    }
}
