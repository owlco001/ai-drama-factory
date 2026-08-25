# 「Agent团队」开发报告 — 项目骨架初始化与核心引擎层

日期：2026-08-25 ｜ 开发Agent ｜ 上游：docs/prd.md、docs/architecture.md、docs/decisions.md

## 构建与测试结果（真实执行）
- `:core-engine:test` → **BUILD SUCCESSFUL，12/12 通过**（JUnit5 + kotlinx-coroutines-test）
- `:app:assembleDebug` → **BUILD SUCCESSFUL**，产出 app-debug.apk（约23MB，含Compose依赖；PRD安装包≤100MB达标）

## 模块清单（/root/project_workspace/src/）
| 路径 | 内容 |
|---|---|
| settings.gradle.kts / build.gradle.kts / gradle.properties | Gradle骨架；模块 = `:core-engine`（纯Kotlin）+ `:app`（Android壳）；无Firebase、无网络库 |
| core-engine/.../message/Messages.kt | AgentMessage信封（snake_case字段对齐架构§4 JSON Schema）、MessageType、TopologyValidator星型拓扑校验 |
| core-engine/.../model/Models.kt | TaskNode/TaskDag/TaskResult/DirectTask/MemoryItem（§3签名） |
| core-engine/.../bus/MessageBus.kt | MessageBus接口 + DefaultMessageBus（SharedFlow广播 + type路由 + history） |
| core-engine/.../memory/MemoryStore.kt | 三层记忆接口 + InMemoryMemoryStore（L1窗口裁剪/L2任务隔离/L3 LRU+检索） |
| core-engine/.../tools/ | ToolRegistry白名单注册/校验/拒绝；内置calculator(递归下降解析)、clipboard_read、file_read（路径穿越防护，仅TXT/MD）——决议Q2/Q8 |
| core-engine/.../agent/BaseSubAgent.kt | SubAgent接口+骨架：拼Prompt→L2上下文→推理→写L2→TASK_RESULT；异常一律转FAILED不外抛 |
| core-engine/.../agent/impl/Agents.kt | 6 Agent最小实现，各自≤200字中文System Prompt常量 + 工具白名单常量 |
| core-engine/.../orchestrator/Orchestrator.kt | 串行调度队列；@点名解析（决议Q7）；RunBudget六层保险（§6.2） |
| core-engine/.../infer/InferenceEngine.kt | 接口 + LlamaJni external fun声明 + JniLlamaEngine桩（native未接入时安全降级）+ FakeEngine测试引擎 |
| app/.../data/AgentTeamDatabase.kt | Room实体×5表 + DAO（对应架构§5建表） |
| app/src/main/assets/agent_team_schema.sql | §5原始SQL逐句一致（WAL/FTS5/触发器/索引） |
| app/src/main/java/com/agentteam/app/MainActivity.kt | UI占位Activity |

## §6.2 六层保险落地
最大12轮（MAX_ROUNDS）、单Agent同task上限4次（超出SKIPPED）、单节点120s超时（withTimeoutOrNull→FAILED）、失败重试1次（共2次）、reply链深16上限（常量）、cancelCurrent()用户熔断。

## 测试覆盖
OrchestratorTest（5）：全链路SUCCESS/消息计数校验、重试上限=2次调用、拓扑拦截、@点名解析、取消终止。
CoreModulesTest（7）：bus路由与广播、L1裁剪/L2隔离/L3检索、工具白名单拒绝(TOOL_DENIED)、UUIDv7时间有序。

## 未完成项 / 已知限制（共5项）
1. **真实native库未接入**：libllama-android.so需NDK+CMake构建llama.cpp并锁定上游tag，当前JniBridge为空实现桩。
2. **计划生成用规则模板**：Coordinator的DAG拆解为固定"检索→分析→创作→校验"，LLM生成TaskPlan(JSON)解析留待接入模型后实现。
3. **Room层DAO未桥接到core接口**：app层RoomMemoryStore/RoomMessageBus适配器（装饰DefaultMessageBus落库）尚未编写，当前持久化SQL/实体就绪但运行时走内存实现。
4. **循环检测简化**：DAG无环校验与链深>16的ERROR(CANCELLED)仅有常量定义，planTask模板天然无环故未触发路径。
5. **UI层为占位**：会话屏/DAG图/团队面板/日志流（F05-F08）按MVP范围留待下一迭代。

---

# 第二轮修复记录（2026-08-25，对应 review-20260825）

## P0（全部修复）

- **P0-1 L2记忆ID与context_refs不匹配** — `MemoryStore.putTaskMemory()` 改为返回真实memoryId；`BaseSubAgent` 用返回值填 `detail_ref`，并按 `memoryId == ref || endsWith("_$ref") || roleOrKey == ref` 兜底匹配。新增跨节点上下文传递集成测试2条。
- **P0-2 file_read兄弟目录前缀绕过** — `BuiltinTools.FileReadTool` 改为 canonicalFile 逐级父链比较（`isUnder`），允许base自身与库内任意深度，拒绝兄弟前缀目录/`../`/绝对路径出界/symlink出界。补测试4条（../、绝对路径、symlink、knowledge_x兄弟目录）。

## P1（前四项全部修复）

- **P1-1 失败短路** — Orchestrator维护 `succeeded` 集合，节点依赖未全部成功即置SKIPPED跳过，后继依赖链自动短路；`markSkippedFrom()` 修正为从指定节点起置SKIPPED。新增失败短路测试。
- **P1-2 六层保险补全** — ① deadline纳入循环检查（超时后所有剩余节点SKIPPED）；② `MAX_REPLY_DEPTH=16` 链深计数落地；③ 分派前执行Kahn拓扑排序验环+依赖引用校验（`validateDag`），非法计划直接返回失败。
- **P1-3 取消干净+超时停推理** — BaseSubAgent与dispatchWithRetry对 `CancellationException` 单独捕获重抛（仅TimeoutCancellationException转TIMEOUT结果）；超时路径调用对应agent的 `engine.stop()` 停止native推理（DefaultOrchestrator新增engines注入参数）。新增engine.stop()断言测试。
- **P1-4 消息总线丢消息与锁不一致** — 统一用单一lock对象保护log/handlers；tryEmit失败不再静默（计数告警输出到stderr）；handler路由由send内部经handlerScope启动协程派发（注册的路由真正生效）。

## 顺手修复

- **P1-5 内存日志无界增长** — log改为有界环形缓冲（ConcurrentLinkedDeque，默认上限1000条，超限淘汰最旧）。
- **P1-7 taskId碰撞** — `"task_${System.currentTimeMillis()}"` 改为 `"task_${newUuidV7()}"`。
- **P2-1 failMsg字符串截取** — 改为cast JsonObject结构化取node_id。
- **P2-2 计算器** — atom支持一元负号；除零返回错误而非Infinity。

## 测试结果

`:core-engine:test` 全绿：22个测试（原12 + 新增10：P0-1×2、P0-2×4、P1-1×1、P1-2×1、P1-3×1、含环DAG拒绝等），BUILD SUCCESSFUL。

## 本轮仍未修（登记跟踪）

- P1-6 剪贴板脱敏/授权钩子、P2-3 baseDir硬编码、P2-4 TASK_PLAN空壳payload、P2-5 MemoryStore线程安全、P2-6 Verifier失败也写L3、P2-7 USER_INPUT/L1未接、P2-8 其余覆盖缺口。
