package com.dramafactory.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import com.dramafactory.app.ui.theme.DramaColor
import com.dramafactory.app.ui.theme.DramaShapes
import com.dramafactory.app.ui.theme.DramaTypography

/**
 * v1.7.5 UI 设计系统落地：暗色霓虹紫/青 · AI 科技感。
 * 取色唯一来源走 DramaColor / MaterialTheme.colorScheme（页面内不再硬编码色值）。
 * 函数签名不变，调用方无感。
 */
private val DarkScheme = darkColorScheme(
    primary = DramaColor.Primary,
    onPrimary = DramaColor.OnPrimary,
    primaryContainer = DramaColor.PrimaryContainer,
    onPrimaryContainer = DramaColor.OnPrimaryContainer,

    secondary = DramaColor.Secondary,
    onSecondary = DramaColor.OnSecondary,
    secondaryContainer = DramaColor.SecondaryContainer,
    onSecondaryContainer = DramaColor.OnSecondaryContainer,

    tertiary = DramaColor.Tertiary,
    onTertiary = DramaColor.OnTertiary,
    tertiaryContainer = DramaColor.TertiaryContainer,
    onTertiaryContainer = DramaColor.OnTertiaryContainer,

    background = DramaColor.Background,
    onBackground = DramaColor.OnBackground,
    surface = DramaColor.Surface,
    onSurface = DramaColor.OnSurface,
    surfaceVariant = DramaColor.SurfaceVariant,
    onSurfaceVariant = DramaColor.OnSurfaceVariant,

    // M3 1.3.0 支持：暗色纵深靠 surface 层级亮度表达
    surfaceContainerLowest = DramaColor.SurfaceContainerLowest,
    surfaceContainerLow = DramaColor.SurfaceContainerLow,
    surfaceContainer = DramaColor.SurfaceContainer,
    surfaceContainerHigh = DramaColor.SurfaceContainerHigh,
    surfaceContainerHighest = DramaColor.SurfaceContainerHighest,
    surfaceTint = DramaColor.SurfaceTint,

    error = DramaColor.Error,
    onError = DramaColor.OnError,
    errorContainer = DramaColor.ErrorContainer,
    onErrorContainer = DramaColor.OnErrorContainer,

    outline = DramaColor.Outline,
    outlineVariant = DramaColor.OutlineVariant,
    scrim = DramaColor.Scrim,
)

@Composable
fun DramaFactoryTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    // v1.8.3：全局最小可交互尺寸提到 48dp（Material3 默认 48，但 Button 的
    // 实际高度会被内容压到 40dp，小按钮手指点不准）。在这里统一提一次，
    // 全 App 的 Button / IconButton / Checkbox / Switch 都跟着受益。
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 48.dp,
    ) {
        MaterialTheme(
            colorScheme = DarkScheme,      // 本规格以暗色为主，浅色暂不优化
            typography = DramaTypography,
            shapes = DramaShapes,
            content = content,
        )
    }
}
