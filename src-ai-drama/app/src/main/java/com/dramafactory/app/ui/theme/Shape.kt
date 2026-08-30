package com.dramafactory.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 圆角体系：徽章 6 / Chip 10 / 按钮 14 / 卡片 18 / 对话框 24 */
val DramaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small      = RoundedCornerShape(10.dp),
    medium     = RoundedCornerShape(14.dp),
    large      = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * 聊天气泡：非对称圆角，尖角指向说话方（设计规格 .bub-ai / .bub-me）。
 * 数值只取规格内档位（18 = r-lg、6 = r-xs），不另立新值。
 */
val BubbleAiShape   = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp)
val BubbleUserShape = RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
