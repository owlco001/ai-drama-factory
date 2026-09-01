package com.dramafactory.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 间距体系（8pt 栅格，4dp 半档）。
 *
 * 此前各页卡片内边距在 12 / 14 / 16 之间漂移、页级外边距与卡间间距也各自为政，
 * 视觉节奏不统一。这里把"节奏"收敛成唯一来源：
 *
 * - 页面内容外边距：lg(16)
 * - 分区卡片内边距：lg(16)
 * - 卡片之间 / 分组之间：md(12)
 * - 控件内部小间距 / 图标-文字：sm(8)、xs(4)
 * - 大区块留白（空态等）：xl(24) / xxl(32) / screen(48)
 *
 * 页面与组件统一从这里取值，不再散落魔法数字。
 */
object DramaSpacing {
    val xs      = 4.dp
    val sm      = 8.dp
    val md      = 12.dp
    val lg      = 16.dp
    val xl      = 24.dp
    val xxl     = 32.dp
    val screen  = 48.dp
}
