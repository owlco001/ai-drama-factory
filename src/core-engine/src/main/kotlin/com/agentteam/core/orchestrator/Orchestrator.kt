// Orchestrator 串行调度队列实现 —— 架构§6（含§6.2六层保险）
package com.agentteam.core.orchestrator

import com.agentteam.core.agent.SubAgent
import com.agentteam.core.bus.MessageBus
import com.agentteam.core.memory.MemoryStore
import com.agentteam.core.message.AgentMessage
import com.agentteam.core.message.ErrorCode
import com.agentteam.core.message.MessageType
import com.agentteam.core.message.MsgStatus
import com.agentteam.core.model.DirectTask
import com.agentteam.core.model.NodeState
import com.agentteam.core.model.TaskDag
import com.agentteam.core.model.TaskNode
import com.agentteam.core.model.TaskResult
import com.agentteam.core.util.newUuidV7
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 运行预算（架构§6.2：随每次分派递减，任何一项耗尽触发降级汇总） */
data class RunBudget(
    var remainingRounds: Int = MAX_ROUNDS,          // 最大分派轮次 12
    val perAgentCounters: MutableMap<String, Int> = mutableMapOf(), // 单Agent上限4
    val deadline: Long = System.currentTimeMillis() + NODE_TIMEOUT_MS * 100, // 粗粒度总deadline
) {
    companion object {
        const val MAX_ROUNDS = 12               // 最大轮次
        const val PER_AGENT_LIMIT = 4           // 单Agent同task内最大调用次数
        const val NODE_TIMEOUT_MS = 120_000L    // 单节点超时120s
        const val RETRY_PER_NODE = 1            // 失败重试次数（共尝试2次）
        const val MAX_REPLY_DEPTH = 16          // reply链深>16 → ERROR(CANCELLED)
    }
}

class DefaultOrchestrator(
    private val bus: MessageBus,
    private val memory: MemoryStore,
    private val agents: Map<String, SubAgent>,   // id→agent；coordinator必须在内
    private val engines: Map<String, com.agentteam.core.infer.InferenceEngine> = emptyMap(), // id→engine（P1-3超时stop用）
) : com.agentteam.core.orchestrator.Orchestrator {

    private val _dagState = MutableStateFlow(TaskDag("", emptyList()))
    override val dagState: StateFlow<TaskDag> = _dagState

    @Volatile private var cancelled = false
    override fun cancelCurrent() { cancelled = true }

    /** @点名解析（决议Q7：以@开头即视为点名） */
    override fun parseDirectMention(input: String): DirectTask? {
        if (!input.startsWith("@")) return null
        val m = Regex("^@([\\w\\u4e00-\\u9fa5]+)\\s*(.*)", RegexOption.DOT_MATCHES_ALL).find(input) ?: return null
        val name = m.groupValues[1]
        // 中文名映射到agentId
        val agentId = when (name) {
            "协调" -> "coordinator"; "检索" -> "retrieval"; "分析" -> "analysis"
            "创作" -> "creation"; "工具" -> "tool_exec"; "校验" -> "verifier"
            else -> name
        }
        return if (agentId in agents) DirectTask(agentId, m.groupValues[2]) else null
    }

    /** 主入口：生成计划 → 串行驱动队列 → 最终结果。串行=单协程顺序执行（架构§6.1） */
    override suspend fun handleUserInput(input: String): TaskResult {
        val taskId = "task_${newUuidV7()}"   // P1-7：UUIDv7防同毫秒碰撞
        val budget = RunBudget()

        // @点名直达：单节点任务
        val direct = parseDirectMention(input)
        val dag: TaskDag = direct?.let {
            TaskDag(taskId, listOf(TaskNode("n_direct", it.agentId, it.instruction)))
        } ?: planTask(input, taskId)

        _dagState.value = dag
        emitPlan(dag)

        var finalOutput = ""
        val succeeded = mutableSetOf<String>()
        var allOk = true
        var replyDepth = 0   // P1-2：reply链深计数

        // P1-2：分派前拓扑排序验环 + 校验依赖引用
        validateDag(dag)?.let { reason ->
            return TaskResult(taskId, "任务计划非法: $reason", false)
        }

        for (node in dag.nodes) {
            if (cancelled || System.currentTimeMillis() > budget.deadline) {
                node.state = NodeState.SKIPPED; publishDag(dag); allOk = false; continue
            }
            if (budget.remainingRounds <= 0 || (budget.perAgentCounters[node.agentId] ?: 0) >= RunBudget.PER_AGENT_LIMIT ||
                ++replyDepth > RunBudget.MAX_REPLY_DEPTH) {
                node.state = NodeState.SKIPPED; publishDag(dag); continue
            }
            // P1-1：依赖节点未全部成功 → 本节点SKIPPED并短路后继（架构§6.1）
            if (node.dependsOn.any { it !in succeeded }) {
                node.state = NodeState.SKIPPED; publishDag(dag); allOk = false; continue
            }

            budget.remainingRounds--
            node.state = NodeState.RUNNING; publishDag(dag)
            val assign = buildAssignMsg(node, dag.taskId)
            bus.send(assign)

            val result = dispatchWithRetry(node, assign, budget)

            budget.perAgentCounters[node.agentId] = (budget.perAgentCounters[node.agentId] ?: 0) + 1
            // 节点结果落定：OK→SUCCESS，否则FAILED；失败不进succeeded集合，后继依赖节点自动SKIPPED
            node.state = if (result.status == MsgStatus.OK) NodeState.SUCCESS else NodeState.FAILED
            if (result.status == MsgStatus.OK) { succeeded += node.nodeId; finalOutput += "[${node.agentId}] ${summaryOf(result)}\n" }
            else allOk = false
            publishDag(dag)
        }

        // 汇总输出给user（FINAL_OUTPUT是唯一允许to=user的常规类型）
        val outMsg = AgentMessage(
            msgId = newUuidV7(), from = "coordinator", to = "user",
            type = MessageType.FINAL_OUTPUT,
            payload = buildJsonObject {
                put("text", finalOutput.ifBlank { "任务未产出有效结果" })
                putJsonArray("issues") { if (!allOk) add(kotlinx.serialization.json.JsonPrimitive("部分节点未完成或失败")) }
            },
            timestamp = System.currentTimeMillis(), taskId = taskId,
        )
        bus.send(outMsg)
        return TaskResult(taskId, finalOutput, allOk)
    }

    /** 计划生成：MVP用规则模板（检索→分析→创作→校验），LLM润色留接口 */
    private fun planTask(input: String, taskId: String): TaskDag = TaskDag(taskId, listOf(
        TaskNode("n1", "retrieval", "检索与「$input」相关的本地知识库资料"),
        TaskNode("n2", "analysis", "归纳上一步材料的关键要点", dependsOn = listOf("n1")),
        TaskNode("n3", "creation", "基于分析结论撰写最终成果", dependsOn = listOf("n2")),
        TaskNode("n4", "verifier", "核对成果与材料的一致性", dependsOn = listOf("n3")),
    ))

    /** 分派+重试+超时（§6.2：重试1次；单节点超时120s标FAILED；P1-3超时/取消路径调engine.stop()） */
    private suspend fun dispatchWithRetry(node: TaskNode, firstAssign: AgentMessage, budget: RunBudget): AgentMessage {
        val agent = agents[node.agentId] ?: return failMsg(firstAssign, ErrorCode.PARSE_FAILED, "无此Agent")
        val engine = engines[node.agentId]
        var attempt = 0
        var last: AgentMessage? = null
        while (attempt <= RunBudget.RETRY_PER_NODE && !cancelled) {
            val assign = if (attempt == 0) firstAssign else firstAssign.copy(msgId = newUuidV7())
            // 超时控制：withTimeoutOrNull包裹onMessage
            val res = withTimeoutOrNull(RunBudget.NODE_TIMEOUT_MS) {
                try { agent.onMessage(assign) }
                // P1-3：仅外部取消传播；超时CancellationException停止推理并转为TIMEOUT结果
                catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    engine?.stop()
                    failMsg(assign, ErrorCode.TIMEOUT, "节点超时${RunBudget.NODE_TIMEOUT_MS}ms")
                }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }   // P1-3：取消传播
                catch (e: Exception) { failMsg(assign, ErrorCode.MODEL_ERROR, e.message ?: "异常") }
            } ?: run {
                engine?.stop()   // P1-3：超时后停止native推理，不再空转
                failMsg(assign, ErrorCode.TIMEOUT, "节点超时${RunBudget.NODE_TIMEOUT_MS}ms")
            }

            last = res
            bus.send(res)
            if (res.status == MsgStatus.OK) break
            attempt++
        }
        return last ?: run { engine?.stop(); failMsg(firstAssign, ErrorCode.CANCELLED, "已取消") }
    }

    /** P1-2：拓扑排序验环 + 依赖引用合法性校验；返回null表示合法 */
    private fun validateDag(dag: TaskDag): String? {
        val ids = dag.nodes.map { it.nodeId }.toSet()
        dag.nodes.forEach { n ->
            n.dependsOn.forEach { d -> if (d !in ids) return "依赖不存在: ${n.nodeId}→$d" }
        }
        // Kahn拓扑排序：剩余>0即存在环
        val indeg = mutableMapOf<String, Int>()
        dag.nodes.forEach { n -> indeg[n.nodeId] = n.dependsOn.size }
        val queue = ArrayDeque(dag.nodes.filter { (indeg[it.nodeId] ?: 0) == 0 }.map { it.nodeId })
        var visited = 0
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst(); visited++
            dag.nodes.filter { it.dependsOn.contains(cur) }.forEach { nxt ->
                indeg[nxt.nodeId] = (indeg[nxt.nodeId] ?: 1) - 1
                if (indeg[nxt.nodeId] == 0) queue.addLast(nxt.nodeId)
            }
        }
        if (visited < dag.nodes.size) return "DAG存在环"
        return null
    }

    private fun buildAssignMsg(node: TaskNode, taskId: String): AgentMessage {
        // context_refs引用上游节点的L2记忆条目
        val refs = node.dependsOn.map { "mem_${taskId}_$it" }
        return AgentMessage(
            msgId = newUuidV7(), from = "coordinator", to = node.agentId,
            type = MessageType.TASK_ASSIGN,
            payload = buildJsonObject {
                put("node_id", node.nodeId); put("instruction", node.instruction)
                putJsonArray("context_refs") { refs.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            },
            timestamp = System.currentTimeMillis(), taskId = taskId,
        )
    }

    private fun failMsg(assign: AgentMessage, code: ErrorCode, message: String) =
        assign.copy(
            msgId = newUuidV7(), from = assign.to, to = "coordinator",
            type = MessageType.ERROR, status = MsgStatus.FAILED,
            payload = buildJsonObject {
                put("code", code.name); put("message", message)
                // P2-1：结构化取node_id，不依赖字符串截断
                put("node_id", (assign.payload as? kotlinx.serialization.json.JsonObject)?.get("node_id")?.toString()?.trim('"') ?: "")
            },
            replyTo = assign.msgId,
        )

    private fun summaryOf(msg: AgentMessage): String =
        (msg.payload as? kotlinx.serialization.json.JsonObject)?.get("summary")?.toString()?.trim('"') ?: ""

    private fun markSkippedFrom(dag: TaskDag, fromNodeId: String) {
        var skipping = false
        dag.nodes.forEach { n ->
            if (n.nodeId == fromNodeId) skipping = true
            if (skipping) n.state = NodeState.SKIPPED
        }
    }

    private fun emitPlan(dag: TaskDag) = bus.send(AgentMessage(
        msgId = newUuidV7(), from = "coordinator", to = "coordinator",
        type = MessageType.TASK_PLAN,
        payload = Json.parseToJsonElement("""{"nodes":[]}"""),   // UI从dagState流取完整DAG
        timestamp = System.currentTimeMillis(), taskId = dag.taskId,
    ))

    private fun publishDag(dag: TaskDag) { _dagState.value = TaskDag(dag.taskId, dag.nodes.toList()) }
}

/** Orchestrator接口（架构§3签名） */
interface Orchestrator {
    suspend fun handleUserInput(input: String): TaskResult
    fun parseDirectMention(input: String): DirectTask?
    val dagState: StateFlow<TaskDag>
    fun cancelCurrent()
}
