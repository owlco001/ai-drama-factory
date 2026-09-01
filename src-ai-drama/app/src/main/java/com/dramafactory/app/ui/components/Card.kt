package com.dramafactory.app.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.dp
import com.dramafactory.app.ui.theme.DramaShapes
import com.dramafactory.app.ui.theme.DramaSpacing

/**
 * 统一分区卡片：全 App 的"设置段 / 信息块 / 列表容器"都走这一套规格，
 * 取代此前散落各页、内边距在 12/14/16 漂移、浮起层级不一致的 `Card(...)`。
 *
 * 规格（设计系统唯一来源）：
 * - 圆角：DramaShapes.large（18dp，与 Shape.kt 卡片档位一致）
 * - 底色：surfaceContainerLow（暗色纵深靠层级亮度表达，不靠阴影）
 * - 浮起：tonalElevation 1.dp（轻量，避免深色下阴影发灰）
 * - 内边距：DramaSpacing.lg（16dp），由本组件统一施加，调用方不要再给内层 Column 加 padding
 *
 * 用法：
 *   DramaCard(Modifier.fillMaxWidth()) {
 *       Text(...)              // 内容直接写，16dp 内边距已由组件提供
 *   }
 *   DramaCard(Modifier.fillMaxWidth(), onClick = { ... }) { ... }  // 可点击卡片
 */
@Composable
fun DramaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(DramaSpacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = DramaShapes.large
    val scheme = MaterialTheme.colorScheme
    val color = containerColor ?: scheme.surfaceContainerLow
    if (onClick != null) {
        Surface(
            onClick = onClick,
            tonalElevation = 1.dp,
            shape = shape,
            color = color,
            border = border,
            modifier = modifier,
        ) {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(contentPadding),
                content = content,
            )
        }
    } else {
        Surface(
            tonalElevation = 1.dp,
            shape = shape,
            color = color,
            border = border,
            modifier = modifier,
        ) {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(contentPadding),
                content = content,
            )
        }
    }
}
