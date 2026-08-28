# UI-BEAUTIFY-SPEC · AI短剧工厂 全局暗色 AI 科技感主题（视觉规格）

> 适用范围：Android · Kotlin + Jetpack Compose + Material3 · 全部 7 个标签页 + AI 悬浮球 + 开屏
> 性质：**纯视觉层规格，不涉及功能/架构变更，不写代码**。工程师按本规格实现样式。
> 风格关键词：暗色梦幻 · 霓虹紫/青 · AI 科技感 · 玻璃拟态点缀

---

## 0. 设计方向与原则

- **色调延续开屏**：开屏渐变 `0xFF1A1030 → 0xFF0D0A1A`，正文色 `0xFFE8DDF5`（见 DramaApp.kt:79-112），全局以此三色为暗色基底。
- **霓虹点缀克制使用**：紫 `0xFFB388FF` / 青 `0xFF64E3FF` / 品红 `0xFFFF7AD9` 仅用于主按钮、进度、标题强调、AI 悬浮球等**高光点**；背景与卡片保持低饱和深紫灰，避免整页霓虹。
- **可读性优先**：正文 ≥14sp；正文/次级文字对比度须满足 WCAG AA（onSurface 相对 surface ≥ 4.5:1）。
- **取色唯一来源**：全部颜色从 MaterialTheme.colorScheme / 全局常量取用，**禁止页面内硬编码色值**；渐变常量集中定义（建议 `ui/theme/` 下）。
- **组件一致性**：同一组件（卡片、按钮、输入框）在 7 个页面外观一致，差异只体现在内容与状态。

---

## 1. 设计 Token — Material3 ColorScheme（暗色）

### 1.1 语义色

| Token | 色值 | 用途说明 |
|---|---|---|
| background | `0xFF0D0A1A` | 页面底色，与开屏渐变底端一致 |
| onBackground | `0xFFE8E2F5` | 页面主文字（接近开屏文案色） |
| surface | `0xFF151022` | 卡片/容器默认面 |
| onSurface | `0xFFE8E2F5` | 卡片内主文字 |
| surfaceVariant | `0xFF221A38` | 次级容器/选中态底（深紫灰） |
| onSurfaceVariant | `0xFFB8AECF` | 次级文字、说明、placeholder |
| surfaceContainerLowest | `0xFF0A0714` | 模态/输入框底（比 surface 更暗） |
| surfaceContainerLow | `0xFF120D1F` | 输入框/列表项底 |
| surfaceContainer | `0xFF171126` | 底部导航底 |
| surfaceContainerHigh | `0xFF1D1630` | 对话框/悬浮面板底 |
| surfaceContainerHighest | `0xFF241C38` | AI 气泡底、选中列表项 |
| primary | `0xFFB388FF` | 霓虹紫（主强调/选中态文字） |
| onPrimary | `0xFF1E1140` | 主按钮文字（深紫） |
| primaryContainer | `0xFF4A2E85` | 主按钮底（可选）/chip 选中底 |
| onPrimaryContainer | `0xFFE8DDF5` | chip 选中文字 |
| secondary | `0xFF64E3FF` | 霓虹青（次级强调/进度/链接） |
| onSecondary | `0xFF00343D` | 青色元素上的文字（深青） |
| secondaryContainer | `0xFF0E3A47` | 青色 chip/标签底 |
| onSecondaryContainer | `0xFFC5F4FF` | 青色标签文字 |
| tertiary | `0xFFFF7AD9` | 霓虹品红（AI 悬浮球、少量点缀） |
| onTertiary | `0xFF3A0A2E` | 品红元素上的文字 |
| tertiaryContainer | `0xFF5A1B4C` | 品红标签底 |
| onTertiaryContainer | `0xFFFFD9F1` | 品红标签文字 |
| error | `0xFFFF6B7A` | 错误文字/失败状态 |
| onError | `0xFF3B0A12` | 错误按钮文字 |
| errorContainer | `0xFF5C1A24` | 错误提示底 |
| onErrorContainer | `0xFFFFDADD` | 错误提示文字 |
| outline | `0xFF5E5478` | 描边、禁用态、分隔 |
| outlineVariant | `0xFF3A3157` | 弱描边（卡片/输入框常态边） |
| surfaceTint | `0xFFB388FF` | 阴影主色调（与 primary 一致） |
| scrim | `0xFF000000` | 遮罩 |

> 备注：项目当前 M3 版本若不含 `surfaceContainer*` 系列，可用 `surface`/`surfaceVariant` 近似替代（surfaceContainer→surfaceVariant、surfaceContainerHigh→surfaceVariant 提亮 6%、surfaceContainerLowest→background）。

### 1.2 霓虹点缀色（专用常量）

| 常量名 | 色值 | 用途 |
|---|---|---|
| NeonPurple | `0xFFC77DFF` | 发光/描边/图标高光（比 primary 更艳，仅点缀） |
| NeonCyan | `0xFF22D3EE` | 进度条亮段、运行中状态 |
| NeonMagenta | `0xFFFF4FD8` | 悬浮球脉冲环、AI 强调 |
| NeonGreen | `0xFF57E8A0` | 成功/已保存/渲染完成 |
| NeonAmber | `0xFFFFC24D` | 警告/排队中 |
| GradHero | `0xFFB388FF → 0xFF64E3FF` | 标题渐变字、CTA 主按钮、进度渐变 |
| GradAI | `0xFFB388FF → 0xFFFF7AD9` | AI 悬浮球、AI 面板头 |
| GlassStroke | `0x1AFFFFFF`（白 10%） | 玻璃拟态卡片描边 |
| GlassFill | `0x0DFFFFFF`（白 5%） | 玻璃拟态面板填充 |
| GlowShadow | `0x59B388FF`（primary 35%） | 悬浮球/CTA 外发光阴影 |

---

## 2. Typography 字号体系

- 字体族：沿用系统默认（保持中文渲染与各 ROM 兼容，不引入字体文件；如需英文字距微调，仅对拉丁字符 +0.2sp）。
- 正文基准 **bodyMedium 14sp**，全 App 正文不低于 14sp；12sp 仅用于辅助说明/徽标。

| 样式 | 字号 | 字重 | 行距建议 | 用途 |
|---|---|---|---|---|
| displaySmall | 36sp | Bold(700) | 44sp | 开屏/欢迎语（罕见） |
| headlineMedium | 28sp | SemiBold(600) | 36sp | 项目页大标题（可选） |
| headlineSmall | 24sp | SemiBold(600) | 32sp | 各页顶部标题（现状统一改此） |
| titleLarge | 20sp | SemiBold(600) | 28sp | 卡片主标题/对话框标题 |
| titleMedium | 16sp | Medium(500) | 24sp | 区块标题（新建项目、视频模型供应商） |
| titleSmall | 14sp | Medium(500) | 20sp | 列表项标题/剧集名 |
| bodyLarge | 16sp | Regular(400) | 24sp | 正文强调 |
| bodyMedium | 14sp | Regular(400) | 20sp | **默认正文** |
| bodySmall | 12sp | Regular(400) | 16sp | 辅助说明、时间戳 |
| labelLarge | 14sp | Medium(500) | 20sp | 按钮文字 |
| labelMedium | 12sp | Medium(500) | 16sp | Chip、导航标签、徽章 |
| labelSmall | 11sp | Medium(500) | 16sp | 状态角标 |

---

## 3. Shapes（圆角体系）

| Token | 值 | 用途 |
|---|---|---|
| extraSmall | 6dp | 徽章、角标 |
| small | 10dp | Chip、输入框 |
| medium | 14dp | 按钮、列表项、RadioButton 行 |
| large | 18dp | 卡片 Card |
| extraLarge | 24dp | 对话框 AlertDialog、AI 聊天面板 |

---

## 4. 组件风格规范（暗色主题下）

| 组件 | 视觉规则 |
|---|---|
| **Card（卡片）** | 底色 `surface`，圆角 18dp，常态描边 `outlineVariant` 1dp（或 GlassStroke 白10% 玻璃风）；阴影低（elevation 1dp，色 `surfaceTint`）。高优先级卡（新建项目/当前选中）可用 `surfaceContainerHigh` 底 + 顶部 2dp 渐变条（GradHero）。列表卡间距 ≥12dp，内边距 16dp。 |
| **Button（主按钮/CTA）** | 圆角 14dp，高度 48dp。常规：底 `primary`、字 `onPrimary`；**关键动作（创建/继续渲染/保存）用 GradHero 渐变底** + 轻微 GlowShadow 外发光，字 `onPrimary`。禁用：底 `surfaceVariant` 38% 透明度、字 `onSurface` 38%。 |
| **OutlinedButton** | 透明底，描边 `outline` 1dp（focus/按压时描边 `primary` 2dp），文字 `primary`，圆角 14dp。 |
| **FilterChip** | 圆角 10dp。未选中：底 `surfaceVariant`、描边 `outlineVariant`、字 `onSurfaceVariant`；选中：底 `primaryContainer`、描边 `primary` 1dp、字 `onPrimaryContainer`、前置勾选图标 `primary`。 |
| **OutlinedTextField** | 圆角 12dp，底 `surfaceContainerLow`，常态描边 `outline` 1dp；聚焦描边 `primary` 2dp；label 色 `onSurfaceVariant`，聚焦 label 色 `primary`；光标 `primary`；错误态用 `error` 全套。密码/API Key 输入建议尾部加显隐开关。 |
| **NavigationBar（底部导航）** | 底 `surfaceContainer`，顶边 1dp `outlineVariant`；选中项图标+文字 `primary`（保留 M3 默认胶囊指示条，指示条色 `primaryContainer`），未选中 `onSurfaceVariant`；7 项字号用 labelMedium 11-12sp 防挤压。 |
| **AlertDialog** | 底 `surfaceContainerHigh`，圆角 24dp，标题 titleLarge、正文 bodyMedium，按钮文字 `primary`（危险操作按钮用 `error`）。 |
| **列表项** | 圆角 12dp，行内垂直间距 8-12dp，分隔优先用间距而非分割线；需要分割线时用 `outlineVariant` 50% 透明度 0.5dp。选中/当前项：底 `primaryContainer` 15% 透明 或 `surfaceContainerHighest` + 左侧 3dp `primary` 色条。 |
| **AI 悬浮球（FloatingActionButton）** | 56dp 圆形，底 GradAI 渐变（紫→品红），图标 `onPrimary`，外发光 GlowShadow（elevation 8dp + 阴影色 primary 35%）；可选 1.5s 周期呼吸脉冲环（NeonMagenta 20% 透明度扩散）。 |
| **AI 聊天面板** | 全屏或底部弹层：底 `surfaceContainerHigh`，顶部 24dp 圆角 + 24dp 高 GradAI 渐变头带（含「AI 助手」titleMedium + 状态点）；用户气泡：底 GradHero 渐变、字 `onPrimary`、右对齐；AI 气泡：底 `surfaceContainerHighest`、描边 GlassStroke、字 `onSurface`、左对齐；输入框复用 OutlinedTextField 规则，发送按钮小圆 40dp GradAI。 |

---

## 5. 页面级重点调整清单（7 页 × 2-4 条）

### 5.1 PROJECTS 项目页（入口，最先做）
1. 顶部标题区：`headlineSmall` 提升为 `headlineMedium`，"AI短剧工厂" 用 GradHero 渐变文字 + 下方加一行 slogan（bodyMedium / onSurfaceVariant，如「开源你的梦境 · AI短剧工厂」），与开屏呼应。
2. 新建项目卡片：改 `surfaceContainerHigh` 底 + 顶部 2dp GradHero 渐变条；「创建」按钮改渐变 CTA + 轻微外发光。
3. 项目列表卡：统一 18dp 圆角 + 1dp 描边；每卡缩略图占位区用渐变底（GradHero 低饱和 15% 版）加「📁」水印图标；当前选中项目卡左侧 3dp primary 色条。
4. 空状态：无项目时显示居中渐变圆环图标 + bodyMedium 提示「还没有项目，从新建一个开始」。

### 5.2 EPISODES 剧集页
1. 顶部加当前项目名（titleLarge + onSurfaceVariant 副标），并弱化返回按钮为图标按钮（primary）。
2. 剧集卡：统一卡片规范（18dp 圆角 + 描边）；每集左侧序号徽章（32dp 圆，底 `primaryContainer`、数字 `onPrimaryContainer`、labelLarge）。
3. 剧集进度/状态用 FilterChip 风格小标签：已完成=NeonGreen、进行中=NeonCyan，labelMedium。
4. 场次数、字数等元信息用 bodySmall + onSurfaceVariant 排版对齐。

### 5.3 ASSETS 资产页
1. 资产卡缩略图区：统一 16dp 圆角裁剪，渐变底（按素材类型：文本=紫、图片=青、音频=品红，各用对应色 15% 透明渐变）+ 中央类型图标（onSurfaceVariant）。
2. 顶部工具条 FilterChip 统一选中态规范（primaryContainer + primary 描边）。
3. 文本分析结果（人物/场景/情感）用次级分组卡：组标题 labelLarge + onSurfaceVariant，键值行 labelSmall + onSurfaceVariant。
4. 「继续渲染」主按钮改 GradHero 渐变 CTA，置底固定（不在滚动流内）。

### 5.4 STORYBOARD 分镜页
1. 分镜行/卡：左侧场次徽章（同剧集页规范）+ 时间码 chip（底 `secondaryContainer`、字 `onSecondaryContainer`，NeonCyan 系）。
2. 选中分镜项：底 `primaryContainer` 15% + 左侧 3dp primary 色条 + 描边 primary。
3. 场次间分隔线改用 1dp 渐变细线（`primary` 30% → 透明），保持暗色呼吸感。
4. 顶部标题区沿用 headlineSmall + 副标（当前集名，onSurfaceVariant）。

### 5.5 QUEUE 渲染页
1. 渲染进度区：进度条改为 GradHero 渐变填充 + 底部轨道 `surfaceVariant`；进度百分比 labelLarge + primary。
2. 任务状态标签：渲染中=NeonCyan、成功=NeonGreen、失败=error，圆角 6dp 小徽章（labelSmall、对应容器色 15% 底）。
3. 任务卡按状态加左侧 3dp 状态色条（青/绿/红），卡片本体沿用统一卡片规范。
4. 排队中任务卡降低对比：文字 60% onSurfaceVariant、卡片描边 outlineVariant。

### 5.6 LIBRARY 成片页
1. 成片卡缩略图：16dp 圆角裁剪 + 深色渐变底（0xFF0D0A1A → 0xFF1D1630）；右下角时长 chip（半透明黑底 `0x99000000`、白字 labelSmall）。
2. 悬浮播放按钮：40dp 圆，GradHero 渐变，白色播放图标 + 轻微外发光，居中悬浮于缩略图上。
3. 空态：居中 NeonCyan 图标 + 提示「渲染完成的成片会出现在这里」。

### 5.7 SETTINGS 设置页
1. 分组标题：`labelLarge` + onSurfaceVariant，组与组间距 20dp，组内卡片间距 12dp（弱化「Card 套 Card」感）。
2. 供应商 RadioButton 行：选中行底 `surfaceContainerHighest` + 圆角 12dp + 左侧 3dp primary 色条；未选中行透明；RadioButton 选中色 primary。
3. API Key 输入框：password 显隐开关 + 聚焦 primary 描边；「测试连通」按钮 OutlinedButton（primary 描边），成功提示「✓ 已连通」NeonGreen、失败 error。
4. 保存成功提示统一 NeonGreen ✓（替换现有 `primary` 色保存提示，明确成功语义）。

---

## 6. 落地顺序建议

| 步骤 | 内容 | 影响面 |
|---|---|---|
| 1 | 新建 `ui/theme/`：ColorScheme + Typography + Shapes + 渐变/霓虹常量（本规格 §1-3） | 全局 |
| 2 | 全局组件样式：在 Theme 中覆写 Button/OutlinedTextField/NavigationBar/AlertDialog 默认值，Card 与 Chip 用 Shape 常量（本规格 §4） | 全局 |
| 3 | PROJECTS → EPISODES → ASSETS（创作入口链，用户首屏） | 高 |
| 4 | STORYBOARD → QUEUE → LIBRARY（创作中/产出链） | 中 |
| 5 | SETTINGS（低频页） | 低 |
| 6 | AiAssistantFloating 悬浮球 + 聊天面板（全局覆盖层，放最后统一） | 全局 |
| 7 | 回归：7 页逐页走查 + 深浅两档对比度抽查（暗色为主） | 全局 |

> 排序原则：影响面最大的 Theme 先行；页面按「用户触达频率 × 视觉露出面积」排序；悬浮球虽是全局，但其样式依赖 Theme 与各组件规范，故收尾。

---

## 7. 验收要点（工程师自检清单）

- [ ] 全 App 无硬编码色值，全部取色自 Theme/常量；与开屏渐变 `0xFF1A1030 → 0xFF0D0A1A` 视觉连贯。
- [ ] 正文 ≥14sp；次级文字对比度达标；暗色下无纯黑死区（背景统一 0xFF0D0A1A 系）。
- [ ] 渐变仅用于标题/CTA/进度/悬浮球等 ≤3 处每页，未造成整页霓虹过曝。
- [ ] 7 个页面同一组件（卡片/按钮/输入框/Chip）外观一致。
- [ ] 底部导航 7 项不挤压、选中态清晰；AI 悬浮球在所有页悬浮正常、不与内容重叠遮挡关键操作。
- [ ] 深浅色均可用（本规格按暗色为主，浅色可暂不优化或延后）。
