package com.dramafactory.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号体系：正文基准 14sp，全 App 正文不低于 14sp，12sp 仅用于辅助说明。
 * 不引入字体文件，沿用系统中文字体，保证各 ROM 渲染一致。
 */
val DramaTypography = Typography(
    displaySmall   = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold,    lineHeight = 44.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp),
    headlineSmall  = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    titleLarge     = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium    = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium,   lineHeight = 24.sp),
    titleSmall     = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium,   lineHeight = 20.sp),
    bodyLarge      = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal,   lineHeight = 24.sp),
    bodyMedium     = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal,   lineHeight = 20.sp),
    bodySmall      = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal,   lineHeight = 16.sp),
    labelLarge     = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium,   lineHeight = 20.sp),
    labelMedium    = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium,   lineHeight = 16.sp),
    labelSmall     = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium,   lineHeight = 16.sp),
)
