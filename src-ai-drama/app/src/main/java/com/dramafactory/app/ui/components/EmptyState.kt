package com.dramafactory.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 统一空状态（v1.8.3）。
 *
 * 此前各页空态三种写法并存：资产页是 180dp 插画 + 标题 + 副文案，
 * 剧集/成片库是 Card 里一行灰色小字，项目列表和渲染队列干脆没有空态
 * （新用户打开就是一片空白）。这里收敛成一个组件，保证插画尺寸、
 * 标题层级、文案颜色、行动区位置全 App 一致。
 *
 * 两种主视觉二选一：
 * - [illustration]：插画（Painter），用于核心空态（资产库等）
 * - [icon]：图标（ImageVector），置于圆角方形浅底容器中，用于一般空态
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    illustration: Painter? = null,
    illustrationDesc: String? = null,
    icon: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            illustration != null -> {
                androidx.compose.foundation.Image(
                    painter = illustration,
                    contentDescription = illustrationDesc,
                    modifier = Modifier.size(180.dp),
                )
            }
            icon != null -> {
                Box(
                    Modifier
                        .size(96.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.extraLarge,
                        ),
                    contentAlignment = Alignment.Center,
                    content = { icon() },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** ImageVector 便捷重载 */
@Composable
fun EmptyState(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    EmptyState(
        title = title,
        modifier = modifier,
        icon = {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
        },
        subtitle = subtitle,
        action = action,
    )
}
