// 工具白名单 ToolRegistry —— 架构§3；MVP工具：计算器/剪贴板读/文件只读（决议Q2）
package com.agentteam.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

data class ToolResult(val ok: Boolean, val data: String, val error: String? = null)

interface AgentTool {
    val name: String            // "calculator" / "clipboard_read" / "file_read"
    val description: String
    val argsSchema: String      // JSON Schema字符串
    suspend fun execute(argsJson: String): ToolResult
}

interface ToolRegistry {
    /** 启动时注册内置工具，声明所属Agent可见范围 */
    fun register(tool: AgentTool, allowedAgents: Set<String>)
    /** 校验+执行。白名单外一律拒绝并返回错误结果（不抛异常） */
    suspend fun invoke(callerAgentId: String, toolName: String, argsJson: String): ToolResult
    fun listFor(agentId: String): List<AgentTool>
}

class DefaultToolRegistry : ToolRegistry {
    private val tools = mutableMapOf<String, Pair<AgentTool, Set<String>>>()

    override fun register(tool: AgentTool, allowedAgents: Set<String>) {
        tools[tool.name] = tool to allowedAgents
    }

    /** 白名单外拒绝：返回 ok=false + TOOL_DENIED 语义错误（US4安全要求） */
    override suspend fun invoke(callerAgentId: String, toolName: String, argsJson: String): ToolResult {
        val entry = tools[toolName]
            ?: return ToolResult(false, "", error = "TOOL_DENIED: 未注册工具 $toolName")
        val (tool, allowed) = entry
        if (callerAgentId !in allowed)
            return ToolResult(false, "", error = "TOOL_DENIED: agent=$callerAgentId 无权调用 $toolName")
        return try { tool.execute(argsJson) }
        catch (e: Exception) { ToolResult(false, "", error = "TOOL_ERROR: ${e.message}") }
    }

    override fun listFor(agentId: String): List<AgentTool> =
        tools.values.filter { agentId in it.second }.map { it.first }

    companion object {
        fun arg(argsJson: String, key: String): String? {
            val obj = Json.parseToJsonElement(argsJson) as? JsonObject ?: return null
            return when (val v = obj[key]) {
                is kotlinx.serialization.json.JsonPrimitive -> v.content
                else -> null
            }
        }
    }
}
