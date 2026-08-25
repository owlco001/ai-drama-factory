// 「Agent团队」核心引擎层 —— 消息信封与消息类型
// 严格按 architecture.md §3 / §4：星型拓扑，子Agent间禁止直接通信
package com.agentteam.core.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 消息类型枚举（架构§3） */
enum class MessageType {
    USER_INPUT, TASK_PLAN, TASK_ASSIGN, TASK_RESULT,
    TOOL_CALL, TOOL_RESULT, AGENT_DIRECT, ERROR, FINAL_OUTPUT
}

/** 消息状态 */
@Serializable
enum class MsgStatus { OK, FAILED, RETRY }

/** 错误码（架构§4.2 ERROR_payload） */
enum class ErrorCode { TOOL_DENIED, MODEL_ERROR, TIMEOUT, PARSE_FAILED, CANCELLED }

/**
 * 结构化消息信封。字段命名保持 snake_case 与架构文档 JSON Schema 一致。
 * msg_id 使用 UUIDv7（时间有序）。
 */
@Serializable
data class AgentMessage(
    @SerialName("msg_id") val msgId: String,
    val from: String,
    val to: String,
    val type: MessageType,
    val payload: JsonElement,
    val timestamp: Long,
    @SerialName("task_id") val taskId: String?,
    @SerialName("reply_to") val replyTo: String? = null,
    val status: MsgStatus = MsgStatus.OK,
)

/** 星型拓扑校验：from/to 为子Agent时另一端必须是 coordinator；to=="user" 仅允许 FINAL_OUTPUT/ERROR */
object TopologyValidator {
    const val COORDINATOR = "coordinator"
    private val SUB_AGENTS = setOf("retrieval", "analysis", "creation", "tool_exec", "verifier")

    /** 返回违规原因；null 表示合法 */
    fun validate(msg: AgentMessage): String? {
        val sub = SUB_AGENTS + COORDINATOR
        if (msg.from !in sub && msg.from != "user") return "非法发送方: ${msg.from}"
        if (msg.to !in sub && msg.to != "user") return "非法接收方: ${msg.to}"
        // 子Agent间直接通信禁止
        if (msg.from in SUB_AGENTS && msg.to in SUB_AGENTS) return "违反星型拓扑: ${msg.from}→${msg.to}"
        if (msg.to == "user" && msg.type !in setOf(MessageType.FINAL_OUTPUT, MessageType.ERROR))
            return "to=user 仅允许 FINAL_OUTPUT/ERROR, 实际=${msg.type}"
        return null
    }
}
