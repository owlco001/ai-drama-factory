package com.dramafactory.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * v1.7.2 UI 美化：短剧工厂专属配色（影院暗色 + 紫红主色，凸显「影视创作台」气质）。
 * 不引入第三方依赖，纯 Material3 调色。
 */
private val BrandPurple = Color(0xFF7C4DFF)      // 主色：紫
private val BrandMagenta = Color(0xFFE040FB)     // 次色：品红（AI 助手高亮）
private val Ink900 = Color(0xFF0E0B1A)           // 背景：近黑带紫
private val Ink850 = Color(0xFF171327)           // 表面
private val Ink800 = Color(0xFF221C3A)           // 表面变体

private val DarkScheme = darkColorScheme(
    primary = BrandPurple,
    onPrimary = Color.White,
    primaryContainer = BrandPurple.copy(alpha = 0.18f),
    onPrimaryContainer = BrandMagenta,
    secondary = BrandMagenta,
    onSecondary = Color.White,
    background = Ink900,
    onBackground = Color(0xFFECE8FF),
    surface = Ink850,
    onSurface = Color(0xFFECE8FF),
    surfaceVariant = Ink800,
    onSurfaceVariant = Color(0xFFB9B0D8),
    outline = Color(0xFF4A4068),
)

private val LightScheme = lightColorScheme(
    primary = BrandPurple,
    onPrimary = Color.White,
    secondary = BrandMagenta,
    onSecondary = Color.White,
    background = Color(0xFFF6F3FF),
    onBackground = Color(0xFF1B1530),
    surface = Color.White,
    onSurface = Color(0xFF1B1530),
    surfaceVariant = Color(0xFFEFEAFE),
    onSurfaceVariant = Color(0xFF5B5178),
    outline = Color(0xFFD3C9F2),
)

@Composable
fun DramaFactoryTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
