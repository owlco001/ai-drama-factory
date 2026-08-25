// SubAgent接口与公共骨架 BaseSubAgent —— 架构§3
// 骨架流程：拼Prompt(系统提示+记忆上下文+指令) → 调InferenceEngine → 解析输出 → 写L2记忆
package com.agentteam.core.agent

import com.agentteam.core.memory.MemoryStore
import com.agentteam.core.message.AgentMessage
import com.agentteam.core.message.MessageType
import com.agentteam.core.message.MsgStatus
import com.agentteam.core.model.NodeState
import com.agentteam.core.tools.ToolRegistry
import com.agentteam.core.infer.InferenceEngine
import com.agentteam.core.util.newUuidV7
import kotlinx.coroutines.flow.fold
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.doubleOrNull

/** 子Agent公共契约：异常不得抛出到总线外，失败以status=FAILED返回 */
interface SubAgent {
    val id: String
    val systemPrompt: String
    val toolWhitelist: Set<String>
    suspend fun onMessage(msg: AgentMessage): AgentMessage
}

abstract class BaseSubAgent(
    final override val id: String,
    final override val systemPrompt: String,
    final override val toolWhitelist: Set<String> = emptySet(),
    protected val memory: MemoryStore,
    protected val tools: ToolRegistry,
    protected val engine: InferenceEngine,
) : SubAgent {

    /** 默认实现仅处理 TASK_ASSIGN；拼Prompt→推理→包装 TASK_RESULT */
    override suspend fun onMessage(msg: AgentMessage): AgentMessage =
        try {
            val payload = msg.payload as kotlinx.serialization.json.JsonObject
            val nodeId = payload["node_id"]?.toString()?.trim('"') ?: "n?"
            val instruction = payload["instruction"]?.toString()?.trim('"') ?: ""
            val contextRefs = payload["context_refs"]?.let {
                (it as? kotlinx.serialization.json.JsonArray)?.mapNotNull { e -> (e as? kotlinx.serialization.json.JsonPrimitive)?.content }
            } ?: emptyList()

            // 从L2记忆拉取被引用的上游结果作为上下文（P0-1：ref可能为memId或裸node_id，均按node_id后缀兜底匹配）
            val context = contextRefs.mapNotNull { ref ->
                memory.taskMemory(msg.taskId ?: "").firstOrNull {
                    it.memoryId == ref || it.roleOrKey == ref ||
                        it.memoryId.endsWith("_$ref") || it.roleOrKey == ref
                }?.content
            }.joinToString("\n---\n")

            val answer = infer("$systemPrompt\n\n[上下文]\n$context\n\n[任务]\n$instruction")

            // 结果写入L2任务记忆，summary放消息payload，detail_ref使用MemoryStore返回的真实memoryId（P0-1）
            val memId = memory.putTaskMemory(msg.taskId ?: "", nodeId, id, answer)

            msg.copy(
                msgId = newUuidV7(), from = id, to = "coordinator",
                type = MessageType.TASK_RESULT,
                payload = buildJsonObject {
                    put("node_id", nodeId)
                    put("summary", answer.take(200))
                    put("detail_ref", memId)
                    put("confidence", confidenceOf(answer))
                },
                replyTo = msg.msgId, status = MsgStatus.OK,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // P1-3：取消必须传播，保持结构化并发
        } catch (e: Exception) {
            // 兜底：任何异常都转FAILED消息，不外抛
            msg.copy(
                msgId = newUuidV7(), from = id, to = "coordinator",
                type = MessageType.TASK_RESULT, status = MsgStatus.FAILED,
                payload = buildJsonObject {
                    put("error", e.message ?: e.javaClass.simpleName)
                },
            )
        }

    /** 推理：调引擎聚合流式token；子类可覆盖以插入工具调用等逻辑 */
    protected open suspend fun infer(prompt: String): String =
        engine.completion(prompt).fold(StringBuilder()) { sb, t -> sb.append(t) }.toString()

    /** 置信度默认由子类给出（MVP简化） */
    protected open fun confidenceOf(answer: String): Double = 0.8
}
