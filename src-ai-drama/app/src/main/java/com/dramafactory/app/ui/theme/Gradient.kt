package com.dramafactory.app.ui.theme

import androidx.compose.ui.graphics.Brush
import com.dramafactory.app.ui.theme.DramaColor.NeonPurple
import com.dramafactory.app.ui.theme.DramaColor.Primary
import com.dramafactory.app.ui.theme.DramaColor.Secondary
import com.dramafactory.app.ui.theme.DramaColor.Tertiary

/**
 * 渐变集中定义。使用纪律：每页最多 3 处，
 * 仅用于标题渐变字 / 关键 CTA / 进度条 / AI 悬浮球。
 */
object DramaGradient {
    private val HeroColors = listOf(Primary, Secondary)   // #B388FF → #64E3FF
    private val AiColors   = listOf(Primary, Tertiary)    // #B388FF → #FF7AD9

    /** 标题渐变字、CTA 主按钮、进度条填充 */
    fun hero(): Brush = Brush.linearGradient(HeroColors)

    /** AI 悬浮球、AI 面板头带 */
    fun ai(): Brush = Brush.linearGradient(AiColors)

    /** 缩略图占位（低饱和版，按素材类型着色） */
    fun thumbSoft(tint: androidx.compose.ui.graphics.Color): Brush =
        Brush.linearGradient(listOf(tint.copy(alpha = 0.20f), tint.copy(alpha = 0.06f)))
}
