package com.dramafactory.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 暗色霓虹紫/青 · 语义色（UI 设计系统 v1.7.3 配套）。
 * 唯一取色来源：页面内禁止硬编码色值，一律从这里取。
 */
object DramaColor {
    // ---- 基底（带紫调的中性色，不使用纯黑）----
    val Background              = Color(0xFF0D0A1A)
    val OnBackground            = Color(0xFFE8E2F5)
    val Surface                 = Color(0xFF151022)
    val OnSurface               = Color(0xFFE8E2F5)
    val SurfaceVariant          = Color(0xFF221A38)
    val OnSurfaceVariant        = Color(0xFFB8AECF)

    // M3 1.3.0 容器层级：越高层级越亮，用于表达暗色纵深（不靠阴影）
    val SurfaceContainerLowest  = Color(0xFF0A0714)
    val SurfaceContainerLow     = Color(0xFF120D1F)
    val SurfaceContainer        = Color(0xFF171126)
    val SurfaceContainerHigh    = Color(0xFF1D1630)
    val SurfaceContainerHighest = Color(0xFF241C38)

    // ---- 主 / 次 / 第三色 ----
    val Primary                 = Color(0xFFB388FF)   // 霓虹紫
    val OnPrimary               = Color(0xFF1E1140)
    val PrimaryContainer        = Color(0xFF4A2E85)
    val OnPrimaryContainer      = Color(0xFFE8DDF5)

    val Secondary               = Color(0xFF64E3FF)   // 霓虹青
    val OnSecondary             = Color(0xFF00343D)
    val SecondaryContainer      = Color(0xFF0E3A47)
    val OnSecondaryContainer    = Color(0xFFC5F4FF)

    val Tertiary                = Color(0xFFFF7AD9)   // 霓虹品红
    val OnTertiary              = Color(0xFF3A0A2E)
    val TertiaryContainer       = Color(0xFF5A1B4C)
    val OnTertiaryContainer     = Color(0xFFFFD9F1)

    // ---- 语义色 ----
    val Error                   = Color(0xFFFF6B7A)
    val OnError                 = Color(0xFF3B0A12)
    val ErrorContainer          = Color(0xFF5C1A24)
    val OnErrorContainer        = Color(0xFFFFDADD)

    val Success                 = Color(0xFF57E8A0)
    val Warning                 = Color(0xFFFFC24D)

    // ---- 描边 ----
    val Outline                 = Color(0xFF5E5478)
    val OutlineVariant          = Color(0xFF3A3157)
    val SurfaceTint             = Color(0xFFB388FF)
    val Scrim                   = Color(0xFF000000)

    // ---- 霓虹点缀（仅用于高光，勿铺满）----
    val NeonPurple              = Color(0xFFC77DFF)
    val NeonCyan                = Color(0xFF22D3EE)
    val NeonMagenta             = Color(0xFFFF4FD8)
    val NeonGreen               = Color(0xFF57E8A0)
    val NeonAmber               = Color(0xFFFFC24D)

    // ---- 玻璃拟态 ----
    val GlassStroke             = Color.White.copy(alpha = 0.10f)
    val GlassFill               = Color.White.copy(alpha = 0.05f)
    val GlowShadow              = Color(0xFFB388FF).copy(alpha = 0.35f)
}
