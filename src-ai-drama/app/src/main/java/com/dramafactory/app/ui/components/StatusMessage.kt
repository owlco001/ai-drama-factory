package com.dramafactory.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 状态级别：取代此前靠文案前缀（✅/❌/⚠️）判色的脆弱写法 */
enum class StatusLevel { INFO, SUCCESS, ERROR }

data class StatusMessage(val text: String, val level: StatusLevel)

fun statusInfo(text: String) = StatusMessage(text, StatusLevel.INFO)
fun statusOk(text: String) = StatusMessage(text, StatusLevel.SUCCESS)
fun statusErr(text: String) = StatusMessage(text, StatusLevel.ERROR)

/**
 * 行内状态短语（图标 + 短文案），用于「已保存」「连通成功/失败」等就地提示，
 * 替代此前 "✓ " / "✗ " / "● " 之类的字符前缀。
 */
@Composable
fun InlineStatus(
    icon: ImageVector,
    text: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/**
 * 统一状态条：语义色取自 MaterialTheme（errorContainer / primaryContainer），
 * 不再硬编码半透明红，深色模式下同样满足对比度要求。
 */
@Composable
fun StatusCard(
    msg: StatusMessage,
    modifier: Modifier = Modifier,
    dense: Boolean = true,
) {
    val (container, content, icon) = when (msg.level) {
        StatusLevel.SUCCESS -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Default.CheckCircle as ImageVector,
        )
        StatusLevel.ERROR -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.Warning as ImageVector,
        )
        StatusLevel.INFO -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Info as ImageVector,
        )
    }
    Surface(
        tonalElevation = if (dense) 1.dp else 2.dp,
        shape = MaterialTheme.shapes.medium,
        color = container,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = if (dense) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Text(
                msg.text,
                modifier = Modifier.weight(1f),
                style = if (dense) MaterialTheme.typography.bodySmall
                else MaterialTheme.typography.bodyMedium,
                color = content,
            )
        }
    }
}
