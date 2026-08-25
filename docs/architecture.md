# 「Agent团队」安卓App 系统架构设计文档

| 项 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 日期 | 2026-08-25 |
| 作者 | 架构Agent |
| 状态 | 待评审 |
| 上游文档 | docs/prd.md（已评审通过）、tasks/T001-需求.md |

---

## 1. 技术选型及理由

### 1.1 总体技术栈

| 层 | 选型 | 版本基线 | 理由 |
|---|---|---|---|
| 语言/UI | Kotlin + Jetpack Compose + Material3 | Kotlin 2.0 / AGP 8.x | PRD要求Android 10+；Compose适合DAG图、日志流等动态UI；Kotlin协程天然契合"串行调度队列"建模（Channel/Flow） |
| 架构模式 | MVVM + 单Activity多Screen | — | 官方推荐，配合Hilt做依赖注入，6个Agent作为单例注册到引擎层 |
| 推理引擎 | llama.cpp（C/C++），经 **JNI 直接集成** | b4xxx 系列（锁定tag） | 见1.2结论 |
| 本地存储 | SQLite（Room封装） | Room 2.6 | 三层记忆、消息日志、任务DAG持久化；单文件可备份导出 |
| 异步模型 | Kotlin Coroutines + Flow | — | 推理跑在独立线程池；消息总线用SharedFlow广播给UI |
| DAG展示 | 自绘Canvas（Compose Canvas）或引入开源图库 | — | MVP节点数少（≤20），自绘足够，避免重依赖 |
| JSON | kotlinx.serialization | — | 编译期生成序列化器，无反射开销，适合端侧 |

### 1.2 llama.cpp 集成方式：JNI 还是 llama.rn —— **结论：JNI 直接集成**

| 维度 | JNI直连llama.cpp | llama.rn（React Native绑定） |
|---|---|---|
| 运行时依赖 | 无额外JS运行时 | 需引入整个React Native运行时（APK膨胀30–60MB） |
| 内存控制 | 可直接控制KV cache、mmap加载、线程数、context大小 | 经RN桥接，内存调优手段受限，多一层桥开销 |
| 流式输出 | JNI回调→Kotlin Flow，延迟低（<5ms） | 经Bridge异步转发，首token延迟增加 |
| PRD约束 | 安装包≤100MB（不含模型）、App内存峰值≤2.5GB——JNI方案可控性最好 | RN运行时挤占预算 |
| 团队维护成本 | 需写一层薄JNI wrapper（约500行C++） | 低，但引入RN技术栈与纯Kotlin项目冲突 |
| 社区成熟度 | llama.cpp官方支持Android NDK构建（cmake -DANDROID） | 绑定版本常落后上游 |

**结论：采用 JNI 直接集成。** 项目为纯Kotlin原生应用，引入RN仅为获得一个推理绑定得不偿失。具体做法：
1. 用 Android NDK (r26+) + CMake 将 llama.cpp 编译为 `libllama-android.so`；
2. 编写薄封装层 `LlamaJni.kt`（`external fun` 声明）暴露：`loadModel(path, params)`、`startCompletion(prompt, params)`、`nextToken(): String?`（流式逐token拉取）、`stopCompletion()`、`freeModel()`；
3. C++侧持有全局 `llama_model*/llama_context*` 指针，通过mmap加载GGUF以降低RSS峰值；
4. 锁定llama.cpp上游tag并提交NDK构建脚本进仓库，保证可复现构建。

---

## 2. 模块划分

```
┌─────────────────────────────────────────────────────────┐
│                    UI 层 (Compose)                        │
│  会话屏 │ Agent团队面板 │ 任务DAG图 │ 执行日志流 │ 设置    │
│        （ViewModel: ChatVM / TeamVM / DagVM / LogVM）     │
├───────────────────────────────────────��──────────────────┤
│                 Agent 引擎层                              │
│  ┌──────────────┐   订阅/发布    ┌──────────────────┐    │
│  │ Orchestrator │◄─────────────►│   SubAgent ×5      │    │
│  │ (协调Agent)  │               │ 检索/分析/创作/    │    │
│  └──────┬───────┘               │ 工具执行/校验      │    │
│         │ 串行调度队列           └────────┬─────────┘    │
│         ▼                                ▼              │
│  ┌─────────────────────────────────────────────────┐    │
│  │              MessageBus (消息总线)                │    │
│  └─────────────────────────────────────────────────┘    │
├─────────────────────────────────────────────────────────┤
│  记忆层 MemoryStore        │   工具层 ToolRegistry        │
│  L1会话短期/L2任务级/L3长期 │  白名单工具(计算器/剪贴板/   │
│  (SQLite 分表持久化)        │  本地文件读)，权限校验       │
├─────────────────────────────────────────────────────────┤
│  推理服务层 InferenceEngine（llama.cpp via JNI，单实例）   │
└─────────────────────────────────────────────────────────┘
```

模块职责：

| 模块 | 职责 | 对应PRD功能 |
|---|---|---|
| UI层 | 会话交互、团队面板、DAG渲染、日志流、@点名输入解析入口 | F05/F06/F07/F08 |
| Agent引擎层 | Orchestrator任务拆解与串行分派；5个SubAgent各自执行；每Agent独立System Prompt与记忆命名空间 | F02/F03 |
| MessageBus | 结构化JSON消息的收发、路由（星型拓扑：仅Orchestrator↔SubAgent）、日志落库、向UI广播 | F04/F07 |
| MemoryStore | 三层记忆的读写、淘汰、持久化 | F09 |
| ToolRegistry | 工具注册、白名单校验、调用执行、结果包装 | F10 |
| InferenceEngine | GGUF加载、流式推理、参数管理；全局单例互斥访问 | F01 |

依赖方向规则：上层可依赖下层，禁止反向；Agent之间**不直接互相引用**，一切通信走MessageBus。

---

## 3. 核心接口定义（Kotlin）

```kotlin
// ============ 消息总线 ============
interface MessageBus {
    /** 发送一条结构化消息（同步入队，内部按序投递）。返回 msg_id */
    fun send(msg: AgentMessage): String

    /** 订阅消息流（UI日志、各Agent消费均基于此）。SharedFlow保证多订阅者 */
    val messages: SharedFlow<AgentMessage>

    /** 按 task_id 过滤历史消息（从SQLite读取） */
    suspend fun history(taskId: String): List<AgentMessage>

    /** 注册某类消息的处理者（type路由） */
    fun registerHandler(type: MessageType, handler: suspend (AgentMessage) -> Unit)
}

enum class MessageType {
    USER_INPUT, TASK_PLAN, TASK_ASSIGN, TASK_RESULT,
    TOOL_CALL, TOOL_RESULT, AGENT_DIRECT, ERROR, FINAL_OUTPUT
}

@Serializable
data class AgentMessage(
    val msg_id: String,        // UUIDv7（时间有序）
    val from: String,          // "user" / "coordinator" / "retrieval" ...
    val to: String,
    val type: MessageType,
    val payload: JsonElement,  // 各type的具体载荷，见§4
    val timestamp: Long,       // epoch millis
    val task_id: String?,      // 所属任务；用户原始输入为null
    val reply_to: String? = null,
    val status: MsgStatus = MsgStatus.OK   // OK / FAILED / RETRY
)

// ============ 协调者 ============
interface Orchestrator {
    /** 接收用户指令，生成任务计划(DAG)，串行驱动子Agent直至产出最终结果 */
    suspend fun handleUserInput(input: String): TaskResult

    /** 解析@点名：如 "@校验 复核上一段" → 直达指定Agent的单节点任务 */
    fun parseDirectMention(input: String): DirectTask?

    /** 当前任务的DAG状态流（供UI渲染节点状态流转） */
    val dagState: StateFlow<TaskDag>

    /** 取消当前正在执行的任务 */
    fun cancelCurrent()
}

data class TaskNode(
    val node_id: String,
    val agentId: String,          // 由哪个子Agent执行
    val instruction: String,      // 自然语言子任务描述
    val dependsOn: List<String>,  // 前驱node_id（串行下通常为0..1个）
    var state: NodeState          // PENDING/RUNNING/SUCCESS/FAILED/SKIPPED
)
enum class NodeState { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

data class TaskDag(val taskId: String, val nodes: List<TaskNode>)
data class TaskResult(val taskId: String, val output: String, val success: Boolean)

// ============ 子Agent ============
interface SubAgent {
    val id: String                // "coordinator"/"retrieval"/"analysis"/"creation"/"tool_exec"/"verifier"
    val systemPrompt: String
    val toolWhitelist: Set<String>

    /** 处理一条指派消息，返回结果消息。异常不得抛出到总线外，失败以status=FAILED返回 */
    suspend fun onMessage(msg: AgentMessage): AgentMessage
}

/** 子Agent公共骨架：拼Prompt(系统提示+记忆上下文) → 调InferenceEngine → 解析输出 */
abstract class BaseSubAgent(
    override val id: String,
    private val memory: MemoryStore,
    private val tools: ToolRegistry,
    private val engine: InferenceEngine,
) : SubAgent

// ============ 三层记忆 ============
interface MemoryStore {
    // L1 会话短期记忆（最近N轮对话摘要+原文，容量上限自动裁剪）
    suspend fun appendShortTerm(sessionId: String, role: String, content: String)
    suspend fun shortTermContext(sessionId: String, maxTokens: Int): List<MemoryItem>
    suspend fun trimShortTerm(sessionId: String)

    // L2 任务级中间结果（task_id隔离，任务完成后归档）
    suspend fun putTaskMemory(taskId: String, nodeId: String, agentId: String, content: String)
    suspend fun taskMemory(taskId: String): List<MemoryItem>

    // L3 全局长期记忆（跨任务沉淀的用户偏好、结论摘要；由Verifier写入为主）
    suspend fun upsertLongTerm(key: String, content: String, tags: List<String> = emptyList())
    suspend fun searchLongTerm(query: String, limit: Int = 5): List<MemoryItem>   // FTS5全文检索
}

// ============ 工具白名单 ============
interface ToolRegistry {
    /** 启动时注册内置工具；每个工具声明所属Agent可见范围 */
    fun register(tool: AgentTool, allowedAgents: Set<String>)

    /** 校验+执行。白名单外一律拒绝并返回错误结果（不抛异常） */
    suspend fun invoke(callerAgentId: String, toolName: String, argsJson: String): ToolResult

    fun listFor(agentId: String): List<AgentTool>
}

interface AgentTool {
    val name: String            // "calculator" / "clipboard_read" / "file_read"
    val description: String
    val argsSchema: String      // JSON Schema字符串
    suspend fun execute(argsJson: String): ToolResult
}
data class ToolResult(val ok: Boolean, val data: String, val error: String? = null)

// ============ 推理引擎（JNI薄封装，供对照） ============
object InferenceEngine {          // 全局单例，串行互斥
    fun loadModel(path: String, ctxTokens: Int, threads: Int): Boolean
    fun completion(prompt: String, maxTokens: Int, temperature: Float): Flow<String>  // 流式token
    fun stop()
    fun unload()
}
```

---

## 4. 消息 JSON Schema 完整定义

### 4.1 消息信封（所有消息统一）

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AgentMessage",
  "type": "object",
  "required": ["msg_id", "from", "to", "type", "payload", "timestamp", "task_id"],
  "properties": {
    "msg_id":    {"type": "string", "format": "uuid", "description": "UUIDv7，时间有序"},
    "from":      {"type": "string", "enum": ["user","coordinator","retrieval","analysis","creation","tool_exec","verifier"]},
    "to":        {"type": "string", "enum": ["user","coordinator","retrieval","analysis","creation","tool_exec","verifier"]},
    "type":      {"type": "string",
                  "enum": ["USER_INPUT","TASK_PLAN","TASK_ASSIGN","TASK_RESULT",
                           "TOOL_CALL","TOOL_RESULT","AGENT_DIRECT","ERROR","FINAL_OUTPUT"]},
    "payload":   {"type": "object"},
    "timestamp": {"type": "integer", "description": "epoch毫秒"},
    "task_id":   {"type": ["string", "null"]},
    "reply_to":  {"type": ["string", "null"], "description": "所回应的msg_id"},
    "status":    {"type": "string", "enum": ["OK","FAILED","RETRY"], "default": "OK"}
  }
}
```

拓扑约束（星型）：`from` 或 `to` 为子Agent时，另一端**必须**是 `coordinator`；子Agent间禁止直接通信。`to == "user"` 仅允许 `FINAL_OUTPUT` 与 `ERROR`。

### 4.2 各类型 payload 定义

```json
{
  "definitions": {
    "USER_INPUT_payload": {
      "type": "object", "required": ["text"],
      "properties": {
        "text": {"type": "string"},
        "mention": {"type": ["string","null"], "description": "@点名时的目标agentId"}
      }
    },
    "TASK_PLAN_payload": {
      "type": "object", "required": ["nodes"],
      "properties": {
        "nodes": {"type": "array", "items": {
          "type": "object",
          "required": ["node_id","agentId","instruction","dependsOn","state"],
          "properties": {
            "node_id":    {"type": "string"},
            "agentId":    {"type": "string"},
            "instruction":{"type": "string"},
            "dependsOn":  {"type": "array", "items": {"type": "string"}},
            "state":      {"type": "string", "enum": ["PENDING","RUNNING","SUCCESS","FAILED","SKIPPED"]}
          }
        }}
      }
    },
    "TASK_ASSIGN_payload": {
      "type": "object", "required": ["node_id","instruction","context_refs"],
      "properties": {
        "node_id":      {"type": "string"},
        "instruction":  {"type": "string"},
        "context_refs": {"type": "array", "items": {"type": "string"},
                          "description": "引用的记忆条目memory_id，避免大文本重复传输"}
      }
    },
    "TASK_RESULT_payload": {
      "type": "object", "required": ["node_id","summary","confidence"],
      "properties": {
        "node_id":   {"type": "string"},
        "summary":   {"type": "string"},
        "detail_ref":{"type": ["string","null"], "description": "完整结果在L2记忆中的memory_id"},
        "confidence":{"type": "number", "minimum": 0, "maximum": 1}
      }
    },
    "TOOL_CALL_payload": {
      "type": "object", "required": ["tool","args"],
      "properties": {"tool": {"type": "string"}, "args": {"type": "object"}}
    },
    "TOOL_RESULT_payload": {
      "type": "object", "required": ["ok"],
      "properties": {"ok": {"type":"boolean"}, "data": {"type":["string","object"]}, "error": {"type":["string","null"]}}
    },
    "FINAL_OUTPUT_payload": {
      "type": "object", "required": ["text"],
      "properties": {
        "text": {"type": "string"},
        "verification": {"type": "object", "properties": {
          "passed": {"type":"boolean"}, "issues": {"type":"array","items":{"type":"string"}}}}
      }
    },
    "ERROR_payload": {
      "type": "object", "required": ["code","message"],
      "properties": {
        "code": {"type":"string", "enum":["TOOL_DENIED","MODEL_ERROR","TIMEOUT","PARSE_FAILED","CANCELLED"]},
        "message": {"type":"string"}, "node_id": {"type":["string","null"]}
      }
    }
  }
}
```

### 4.3 完整示例

```json
{
  "msg_id": "018f6a2c-9b3e-7000-8000-3b9ac82e1f44",
  "from": "coordinator",
  "to": "analysis",
  "type": "TASK_ASSIGN",
  "payload": {
    "node_id": "n2",
    "instruction": "对检索到的会议纪要要点进行归纳，提炼3个待办事项",
    "context_refs": ["mem_018f6a2b-11"]
  },
  "timestamp": 1771996800000,
  "task_id": "task_20260825_0007",
  "reply_to": null,
  "status": "OK"
}
```

---

## 5. 三层记忆存储方案（SQLite 分表设计）

数据库单文件 `agent_team.db`，WAL模式开启。三层各自独立表，另附消息日志表与任务表。

```sql
PRAGMA journal_mode=WAL;

-- L1 会话短期记忆：滚动窗口，超上限删最旧
CREATE TABLE memory_short_term (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL,             -- MVP固定'session_default'
  role       TEXT NOT NULL,             -- 'user'/'agent:<id>'/'system'
  content    TEXT NOT NULL,
  tokens     INTEGER NOT NULL DEFAULT 0,-- 写入时估算，用于窗口裁剪
  created_at INTEGER NOT NULL           -- epoch millis
);
CREATE INDEX idx_st_session ON memory_short_term(session_id, created_at DESC);

-- L2 任务级中间结果：按task隔离，任务结束保留（供US5恢复/P1回放）
CREATE TABLE memory_task (
  memory_id  TEXT PRIMARY KEY,          -- 'mem_' || uuid
  task_id    TEXT NOT NULL,
  node_id    TEXT NOT NULL,
  agent_id   TEXT NOT NULL,
  content    TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_mt_task ON memory_task(task_id, node_id);

-- L3 全局长期记忆：键值+标签，FTS5支持检索
CREATE TABLE memory_long_term (
  key        TEXT PRIMARY KEY,          -- 如 'pref:user_style'
  content    TEXT NOT NULL,
  tags       TEXT NOT NULL DEFAULT '',  -- 逗号分隔
  updated_at INTEGER NOT NULL
);
CREATE VIRTUAL TABLE memory_long_fts USING fts5(
  content, tags, content='memory_long_term', content_rowid='rowid'
);
CREATE TRIGGER mem_lt_ai AFTER INSERT ON memory_long_term BEGIN
  INSERT INTO memory_long_fts(rowid, content, tags) VALUES (new.rowid, new.content, new.tags);
END;
CREATE TRIGGER mem_lt_ad AFTER DELETE ON memory_long_term BEGIN
  INSERT INTO memory_long_fts(memory_long_fts, rowid, content, tags)
  VALUES ('delete', old.rowid, old.content, old.tags);
END;
CREATE TRIGGER mem_lt_au AFTER UPDATE ON memory_long_term BEGIN
  INSERT INTO memory_long_fts(memory_long_fts, rowid, content, tags)
  VALUES ('delete', old.rowid, old.content, old.tags);
  INSERT INTO memory_long_fts(rowid, content, tags) VALUES (new.rowid, new.content, new.tags);
END;

-- 消息日志（F07追溯能力的持久化底座）
CREATE TABLE message_log (
  msg_id    TEXT PRIMARY KEY,
  from_id   TEXT NOT NULL,
  to_id     TEXT NOT NULL,
  type      TEXT NOT NULL,
  payload   TEXT NOT NULL,              -- 原始JSON
  task_id   TEXT,
  reply_to  TEXT,
  status    TEXT NOT NULL DEFAULT 'OK',
  ts        INTEGER NOT NULL
);
CREATE INDEX idx_ml_task ON message_log(task_id, ts);

-- 任务DAG（F05/F11回放）
CREATE TABLE task_dag (
  task_id   TEXT NOT NULL,
  node_id   TEXT NOT NULL,
  agent_id  TEXT NOT NULL,
  instruction TEXT NOT NULL,
  depends_on  TEXT NOT NULL DEFAULT '', -- JSON数组字符串
  state     TEXT NOT NULL DEFAULT 'PENDING',
  started_at INTEGER, ended_at INTEGER,
  PRIMARY KEY (task_id, node_id)
);
```

容量与淘汰策略（回应PRD Q1）：
- L1：默认窗口 4096 token，超出即删除最旧条目并在窗口头部注入一行摘要（摘要由创作Agent生成）；
- L2：保留最近 50 个任务的中间结果，更旧的整批清理；
- L3：上限 1000 条，FTS命中率为0且最久未更新的优先清理。

---

## 6. 串行调度队列与最大轮次控制

### 6.1 串行调度模型

```
用户输入
   │
   ▼
Orchestrator.handleUserInput()
   │  ① 让Coordinator推理生成 TaskPlan(JSON)
   ▼
┌─────────────────────────────────────────────┐
│ SerialDispatchQueue  (Channel<TaskNode>,    │
│  capacity=UNLIMITED, 单消费者协程)           │
│                                             │
│ while(node = queue.receive()):              │
│   node.state=RUNNING; 发TASK_ASSIGN         │
│   result = subAgent.onMessage(msg)          │
│   写L2记忆 → 更新node.state → 后继入队       │
│   若FAILED：重试1次 → 仍败则标FAILED并短路   │
│   所有后继SKIPPED                            │
└─────────────────────────────────────────────┘
```

关键点：
- 单消费者协程保证任意时刻只有一个Agent在使用InferenceEngine（llama context非线程安全，天然规避并发问题）；
- DAG虽为图结构，但MVP串行下退化为拓扑序逐节点执行；`dependsOn`仅用于决定失败短路范围与UI展示；
- 消息往返额外开销仅来自JSON序列化+SQLite插入，实测目标≤200ms（PRD 5.1）。

### 6.2 防死循环控制（多层保险）

| 保险层 | 规则 | 默认值 |
|---|---|---|
| 最大轮次 | Coordinator最多发起的分派轮数（一轮=一次TASK_ASSIGN+对应RESULT） | **12轮**，超过强制进入汇总输出 |
| 单Agent最大调用次数 | 同一子Agent在同task内被调用上限 | 4次，超出该Agent后续节点标SKIPPED |
| 单次推理token上限 | completion() maxTokens | 1024（Coordinator计划512） |
| 单节点超时 | onMessage挂起超时 | 120s，超时标FAILED |
| 重试上限 | 单节点失败重试 | 1次（共尝试2次） |
| 循环检测 | TASK_PLAN解析后校验DAG无环；reply链深度计数 | 链深>16直接ERROR(CANCELLED) |
| 用户熔断 | cancelCurrent()随时取消，当前token生成停止 | — |

实现上用一个共享的 `RunBudget`（data class，含 remainingRounds/perAgentCounters/deadline），随每次分派递减，任何一项耗尽即触发降级路径：Coordinator基于已有结果直接产出FINAL_OUTPUT，并注明哪些节点未完成。

---

## 7. 数据流示例：「帮我写一封基于这份会议纪要的跟进邮件」

前提：会议纪要已导入本地知识库（文件 `meeting_notes.txt`）。

```
T0  [user] 输入："帮我写一封基于 meeting_notes.txt 的跟进邮件"
    → msg#1 USER_INPUT(user→coordinator, task_0007)

T1  Coordinator推理 → 生成计划
    → msg#2 TASK_PLAN(coordinator→user/UI)
       n1 retrieval: 从本地知识库取会议纪要
       n2 analysis: 归纳待办与承诺事项 (dep:n1)
       n3 creation: 撰写跟进邮件 (dep:n2)
       n4 verifier: 核对事实与语气 (dep:n3)
    DAG状态全部置PENDING，UI开始渲染。

T2  队列弹出n1 → msg#3 TASK_ASSIGN(coordinator→retrieval)
    RetrievalAgent: 拼Prompt(system+L1近期上下文+指令) → llama.cpp推理
    → 内部调用 file_read("meeting_notes.txt")（白名单内✓）
    → msg#4 TASK_RESULT(retrieval→coordinator, summary="纪要含3项待办…",
                        detail_ref=mem_xxx 已写入L2)   n1=SUCCESS

T3  n2 → TASK_ASSIGN(analysis)；AnalysisAgent从L2读mem_xxx作上下文，
    推理归纳 → TASK_RESULT(summary="待办A/B/C，负责人…")  n2=SUCCESS

T4  n3 → TASK_ASSIGN(creation)；CreationAgent基于n2结果撰写邮件正文
    （若它试图调用 clipboard_write 且不在其白名单 → ToolRegistry拒绝
      → msg ERROR(TOOL_DENIED)，记日志，继续生成）      n3=SUCCESS

T5  n4 → TASK_ASSIGN(verifier)；VerifierAgent核对邮件与纪要事实一致性，
    confidence=0.92, passed=true；将"用户跟进邮件偏好简洁"写入L3  n4=SUCCESS

T6  Coordinator汇聚
    → msg FINAL_OUTPUT(coordinator→user)：邮件全文+校验结论
    L1追加本轮完整对话；L2归档；DAG四节点全绿；日志流全程实时滚动。
```

端到端涉及6次推理调用、8条总线消息，全程零网络请求。

---

## 8. 目录结构与部署形态（附录）

```
app/src/main/
├── java/com/teamagents/
│   ├── ui/            # Compose screens + ViewModels
│   ├── engine/        # orchestrator/, agents/, bus/, budget/
│   ├── memory/        # MemoryStore impl (Room/SQLDelight)
│   ├── tools/         # ToolRegistry + 内置工具
│   ├── inference/     # LlamaJni.kt (external fun), EngineManager
│   └── data/          # 数据库、仓库类
└── cpp/               # llama-jni.cpp, CMakeLists.txt, llama.cpp(submodule, 锁定tag)
```

## 9. 待办与风险承接（回应PRD §7）

| PRD问题 | 本文档决策 |
|---|---|
| Q1三层记忆定义 | 采用会话短期/任务级/长期三分表，淘汰策略见§5 |
| Q2工具白名单 | MVP：calculator + clipboard_read + file_read（只读）；敏感写操作v1.1再评估 |
| Q3模型分发 | App不含下载器，设置页提供导入入口+文档指引 |
| Q5校验失败策略 | MVP仅标注confidence与issues于FINAL_OUTPUT.verification，不自动重试 |
| Q6中断恢复 | 表结构已预留（L2+task_dag持久化），恢复逻辑列P1 |
| Q7 @点名解析 | 以'@'开头即点名，其余为自然语言 |
| Q8知识库格式 | MVP仅TXT/Markdown，PDF列P1 |
