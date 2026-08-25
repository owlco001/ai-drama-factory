# issues.md — 引擎bug/遗留问题反馈（UI轮）

> 约束：以下问题均**未改动 core-engine 已验收代码**，仅在此登记。

## E1. AgnesProvider 构造签名暴露 io.ktor 类型（轻微）
- 位置：`core-engine/provider/AgnesProvider.kt` 构造参数 `client: HttpClient = HttpClient {...}`
- 影响：app 模块编译期引用 AgnesProvider 时，Kotlin 元数据中的默认参数类型使 `io.ktor.client.HttpClient` 必须出现在 app classpath，否则报 "Cannot access class 'io.ktor.client.HttpClient'"。当前以 app 增加同版本 ktor-client-core 依赖绕过。
- 建议：构造默认 client 改为工厂 lambda（`() -> HttpClient`）或将 ktor 依赖改 `api` 可见性，避免下游模块被迫感知网络实现类型。

## E2. DefaultRenderQueue 快照缺少各镜明细与 POLLING 态（信息项）
- `QueueSnapshot` 只有 total/completed 聚合；UI 需要每镜状态机展示时另行查 Room（已用 shotStateReader 回调解决）。
- 另：状态机无显式 POLLING 态，SUBMITTED 即"生成中"，UI 以 SUBMITTED 标签呈现「已提交·生成中」，与架构§7.2 文案一致，但 PRD 中 POLLING 措辞建议在文档层对齐。

## E3. RenderQueue.pause()/resume() 无按集维度（设计确认项）
- paused/pausedReason 是队列实例内变量；App 层按集多实例后天然按集隔离，但跨集全局暂停（如弱网总闸）需 App 层自行遍历所有实例。建议接口层明确语义。

## E4. BudgetGuard.usage 为全局单值 StateFlow
- 多项目并发时 usage 只反映最后一次 refresh 的 projectId；UI 当前仅单项目渲染可正常显示。多项目并行渲染时需改为 Map<projectId, Usage> 或按集订阅。

## UI侧已知限制
1. QueueViewModel 绑定 "default" 集，分镜生成接线后需按集实例化（viewModel key 已预留）。
2. POST_NOTIFICATIONS 运行时权限请求未做（targetSdk 34 需动态申请），真机验证项。
3. 图片缩略图加载未接（Coil 待引入）；资产卡暂以 URL 文本展示。
4. evaluateGates 仍为引擎桩；评审/六铁律判定已在 UI 层具备数据源（assets.review_state / shots.sb_check），待编排器迭代接线。
