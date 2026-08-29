# UI-COMPOSE-THEME · 主题落地代码（暗色霓虹紫/青）

> 依据 `docs/UI-BEAUTIFY-SPEC.md` 生成，配套可交互原型 `docs/ui-prototype/index.html`（浏览器直接打开）。
> 原型同时应用了信息架构收敛：底部导航 7 项 → 5 项，详见 §7。
> 目标环境：Kotlin + Jetpack Compose + **Material3 1.3.0**（BOM `2024.10.01`，与本仓库一致）。
>
> ⚠️ **两份产物均未在本仓库编译验证**（生成环境无 Android SDK）。落地时请按 §4 检查清单过一遍 `assembleDebug`。

---

## 0. 落地前必读：现有主题与规格不一致

`app/src/main/java/com/dramafactory/app/ui/Theme.kt`（v1.7.2）当前用的是**另一套色板**，与 `UI-BEAUTIFY-SPEC.md` 对不上：

| Token | 规格要求 | 现有 Theme.kt (v1.7.2) | 状态 |
|---|---|---|---|
| primary | `#B388FF` 霓虹紫 | `#7C4DFF` 紫 | ❌ 不一致 |
| secondary | `#64E3FF` 霓虹青 | `#E040FB` 品红 | ❌ 不一致 |
| tertiary | `#FF7AD9` 品红 | 未定义 | ❌ 缺失 |
| background | `#0D0A1A` | `#0E0B1A` | ⚠️ 接近但不同 |
| surface | `#151022` | `#171327` | ⚠️ 接近但不同 |
| surfaceVariant | `#221A38` | `#221C3A` | ⚠️ 接近但不同 |
| surfaceContainer* | 有完整 5 级 | 未定义 | ❌ 缺失 |
| error / 语义色 | `#FF6B7A` 等 | 未定义（用 M3 默认） | ❌ 缺失 |

**结论**：`UI-BEAUTIFY-SPEC.md` 已写但**尚未落地到 Theme**。本文件即为落地实现，按 §6 步骤 1–2 先建 `ui/theme/`，再替换 `DramaFactoryTheme` 的取色来源。

> **M3 1.3.0 已支持 `surfaceContainer` / `surfaceContainerLow` / `Lowest` / `High` / `Highest`**，无需按规格备注做 `surfaceVariant` 降级替代。

---

## 1. `ui/theme/Color.kt`

```kotlin
package com.dramafactory.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 暗色霓虹紫/青 · 语义色
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
```

## 2. `ui/theme/Gradient.kt`

```kotlin
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
```

## 3. `ui/theme/Type.kt`

```kotlin
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
```

## 4. `ui/theme/Shape.kt`

```kotlin
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
```

## 5. 替换 `ui/Theme.kt` 中的 `DramaFactoryTheme`

把现有 `DarkScheme` 换成下面这份（保留 `DramaFactoryTheme` 函数签名不变，调用方无需改动）：

```kotlin
package com.dramafactory.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import com.dramafactory.app.ui.theme.DramaColor
import com.dramafactory.app.ui.theme.DramaShapes
import com.dramafactory.app.ui.theme.DramaTypography

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
    MaterialTheme(
        colorScheme = DarkScheme,          // 本规格以暗色为主，浅色暂不优化
        typography = DramaTypography,
        shapes = DramaShapes,
        content = content,
    )
}
```

---

## 6. 落地检查清单

- [ ] 新建 `app/src/main/java/com/dramafactory/app/ui/theme/`，放入 `Color.kt` / `Gradient.kt` / `Type.kt` / `Shape.kt`
- [ ] 替换 `ui/Theme.kt` 的 `DarkScheme`（函数签名不变，调用方无感）
- [ ] `:app:assembleDebug` 编译通过
- [ ] 全 App 无硬编码色值，取色统一走 `MaterialTheme.colorScheme` / `DramaColor`
- [ ] 每页渐变 ≤ 3 处，未造成整页霓虹过曝
- [ ] 正文 ≥ 14sp；次级文字对比度 ≥ 4.5:1
- [ ] 各页面同一组件（卡片 / 按钮 / 输入框 / Chip）外观一致
- [ ] 状态徽章 = 色底 + 图标 + 文字（不靠颜色单独传达信息）
- [ ] 底部导航收敛为 5 项，剧集 / 设置改为子页并带返回箭头（见 §7）

---

## 7. 信息架构改进（已在原型中应用，代码侧需同步）

**问题**：`ui/DramaApp.kt` 的 `Page` 枚举有 7 项，全部平铺进 `NavigationBar`。392dp 宽下单格仅约 52dp，图标 21dp + 10.5sp 标签已贴极限，小屏机型会挤压。

**已在原型应用的改法**：底栏 5 项常驻 —— 项目 / 资产 / 分镜 / 渲染 / 成片，单格约 78dp，留白充足。

| 原底栏项 | 新归属 | 理由 |
|---|---|---|
| 项目 | 底栏常驻 | 创作入口 |
| 剧集 | **项目页二级子页** | `onEnterProject` 本就跳 `Page.EPISODES`，它从来就是项目的下一级，不该与项目平级 |
| 资产 / 分镜 / 渲染 | 底栏常驻 | 生产动线主干 |
| 成片 | 底栏常驻 | 关键产出，不宜收进「更多」 |
| 设置 | **顶栏齿轮** | 低频，符合 Android 惯例，不占创作动线 |

**代码侧落地要点**（`ui/DramaApp.kt`）：

1. `NavigationBar` 只渲染 5 项：`PROJECTS / ASSETS / STORYBOARD / QUEUE / LIBRARY`。
2. `EPISODES` 与 `SETTINGS` 保留在 `Page` 枚举中，但不进底栏；由页内动作触发切换（项目卡「进入」→ 剧集，顶栏齿轮 → 设置）。
3. 子页显示时，底栏高亮映射到其所属主标签（`EPISODES → PROJECTS`），避免"底栏无选中项"的悬空状态。
4. 子页顶部提供返回箭头：剧集返回项目列表；设置返回进入前的上一个主页面（需记录 `lastMain`）。
5. `aiVm.onGoto` 的跳转表保持 7 项不变 —— AI 助手仍可直接跳到任意页，不受底栏收敛影响。

> ⚠️ 此项涉及 `Page` 枚举与导航结构，属于功能/架构变更。本文件仅提供规格与要点，**未改动源码**。
