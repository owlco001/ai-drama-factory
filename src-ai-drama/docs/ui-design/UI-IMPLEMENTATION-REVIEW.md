# UI 实现走查 · 资产页（Hermes 编译结果）

> 基于你发来的真机截图（资产页）与 `docs/UI-BEAUTIFY-SPEC.md` + `docs/ui-prototype/index.html` 对比。
> 结论：**整体布局方向对，但 5 个关键视觉点没按规格走**，导致看起来像"另一个 App"。下面给出逐项修复方案。

---

## 一、一眼就能看出的 5 个问题

| # | 问题 | 截图表现 | 规格要求 | 修复优先级 |
|---|---|---|---|---|
| 1 | **选中态颜色用了青绿，不是紫色** | 底栏选中 pill、Chip 选中底都是青/蓝绿色 | 选中态必须用 `primaryContainer` (#4A2E85) + `primary` (#B388FF) | **P0** |
| 2 | **底栏图标完全不对** | 用了 home / play / list / star / info | 应为 folder / image / list / cpu / film | **P0** |
| 3 | **AI 悬浮球货不对板** | 圆角方形 + 聊天气泡图标 + 平紫色 | 应为 56dp 圆形 + 紫→品红渐变 + sparkle 图标 + 外发光 + 脉冲环 | **P0** |
| 4 | **图标按钮文字太艳** | "拍摄图片"等文字是亮紫色 | 图标按钮默认应为 `onSurfaceVariant` (#B8AECF)，只有悬停/按压才用 `primary` | **P1** |
| 5 | **"添加并生成"按钮像禁用态** | 深灰底，看不出是可点主操作 | 若是本节主 CTA，用 `primaryContainer` 或 `GradHero` 渐变；若是次要操作用 `OutlinedButton` | **P1** |

---

## 二、逐项修复代码指引

### 1. 选中态颜色必须是紫色系

M3 的 `NavigationBar` 默认把选中指示条染成 `secondaryContainer`。你必须显式覆盖：

```kotlin
NavigationBar(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    contentColor = MaterialTheme.colorScheme.onSurface,
    // 关键：覆盖选中指示条颜色
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
) { ... }
```

`FilterChip` / `Chip` 同理：

```kotlin
FilterChip(
    selected = selected,
    onClick = { ... },
    label = { Text("角色") },
    colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor   = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
    ),
    border = FilterChipDefaults.filterChipBorder(
        enabled = true,
        selected = selected,
        borderColor = if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outlineVariant
    )
)
```

**千万别用 M3 默认 Chip 颜色，默认走的是 secondary 色系，所以你现在看到的是青绿色。**

---

### 2. 底栏图标映射

| 底栏项 | 当前图标 | 应替换为 |
|---|---|---|
| 项目 | home / 首页 | **folder**（文件夹，代表项目） |
| 资产 | play arrow / 播放 | **image**（图片，代表素材） |
| 分镜 | list / 列表 | **list**（这个可保留） |
| 渲染 | star / 星星 | **cpu / activity / dashboard**（代表运行/队列） |
| 成片 | info / 信息 | **film / movie**（代表成片） |

不要用语义不匹配的默认图标。"星星"和"信息"让用户没法一眼看出这是"渲染"和"成片"。

---

### 3. AI 悬浮球必须按规格实现

当前实现像是把默认 FAB 拉成了圆角矩形 + 换了个图标。请按以下 5 点改：

1. **形状**：`CircleShape`（56dp × 56dp），不是圆角矩形
2. **背景**：`Brush.linearGradient(Primary → Tertiary)`，不是单色
3. **图标**：用 sparkle / magic wand 类图标，不是聊天气泡
4. **阴影**：`GlowShadow`（primary 35% 透明度）+ elevation 8dp
5. **呼吸环**：伪元素 `::after`，品红 20% 透明度，1.5s 周期扩散

Compose 参考骨架：

```kotlin
Box(
    modifier = Modifier
        .size(56.dp)
        .background(
            brush = Brush.linearGradient(listOf(Primary, Tertiary)),
            shape = CircleShape
        )
        .shadow(elevation = 8.dp, spotColor = GlowShadow, shape = CircleShape)
) {
    Icon(
        imageVector = Icons.Default.Sparkle, // 或用自定义 Vector
        contentDescription = "AI 助手",
        tint = OnPrimary,
        modifier = Modifier.align(Alignment.Center)
    )
}
```

脉冲环用 `LaunchedEffect` + `animateFloat` 控制一个外环的 scale/alpha，或者直接用 `InfiniteTransition`。

---

### 4. 图标按钮文字颜色

当前"拍摄图片 / 拍摄视频 / 相册图片 / 相册视频"的 label 是亮紫色。按规格：

```kotlin
iconButtonColors(
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
)
```

只在 `hovered` / `pressed` 时切到 `primary`。默认状态要压暗，否则整屏都是高饱和紫色，显得廉价。

---

### 5. "添加并生成"按钮

这是资产页的核心动作之一。当前深灰底让它看起来禁用或无效。二选一：

**方案 A（推荐，主操作）**：
```kotlin
Button(
    colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor   = MaterialTheme.colorScheme.onPrimary
    )
)
```

**方案 B（次操作，不出强 CTA）**：
```kotlin
OutlinedButton(
    colors = ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.colorScheme.primary
    ),
    border = ButtonDefaults.outlinedButtonBorder.copy(
        brush = SolidColor(MaterialTheme.colorScheme.outline)
    )
)
```

不要让它既不像主按钮也不像次按钮。

---

## 三、两个可以加分但不是致命伤的点

| 问题 | 建议 |
|---|---|
| 页面缺少副标题 | 在"资产库"大标题下方加 `page-sub`："莽途·墨痕初现 · 第 1 集"，用户能快速确认自己在哪个项目/集里 |
| 时代红线卡缺 amber 强调 | 给标题左侧加一个 `shield` 图标 + `badge-warn`（#FFC24D），一眼看出这是约束规则，不是普通说明 |

---

## 四、验收清单（逐页过查时用）

- [ ] 底部导航 5 项，图标与文字语义匹配，选中 pill 为紫色
- [ ] 所有 Chip 选中态 = 紫底 + 紫字 + 紫边框，不是青绿色
- [ ] AI 悬浮球 = 正圆 + 紫→品红渐变 + sparkle 图标 + 脉冲环
- [ ] 图标按钮默认态为 muted 灰紫，不亮紫
- [ ] 主 CTA 按钮一眼可识别（primary / GradHero），禁用态明确 38% 透明度
- [ ] 每页顶部有副标题（项目名 / 集名）
- [ ] 警告/规则卡片有 amber 图标徽章

---

## 五、为什么看起来"差很多"的根本原因

不是布局散了，而是 **"高光点颜色失控 + 图标表意错误 + FAB 货不对板"**。这三样是人们第一眼扫过去就会判定"这不像设计稿"的要素。

建议 Hermes 下一步：**先修 P0 三项**（颜色、图标、FAB），其他 P1 后补。改了之后质感会立刻接近原型。
