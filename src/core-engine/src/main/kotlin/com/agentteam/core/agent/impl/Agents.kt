// 6个Agent最小实现：协调 + 检索/分析/创作/工具执行/校验
// 各带精简版System Prompt常量（各≤200字）
package com.agentteam.core.agent.impl

import com.agentteam.core.agent.BaseSubAgent
import com.agentteam.core.memory.MemoryStore
import com.agentteam.core.message.AgentMessage
import com.agentteam.core.message.MessageType
import com.agentteam.core.message.MsgStatus
import com.agentteam.core.tools.ToolRegistry
import com.agentteam.core.infer.InferenceEngine
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** 协调Agent：拆解任务为DAG计划（MVP：规则化模板+LLM润色） */
class CoordinatorAgent(memory: MemoryStore, tools: ToolRegistry, engine: InferenceEngine) :
    BaseSubAgent("coordinator", SYSTEM_PROMPT, WHITELIST, memory, tools, engine) {
    companion object { const val SYSTEM_PROMPT = "你是任务协调者。将用户指令拆解为2-5个串行子任务，按检索→分析→创作→校验的顺序分派给专业子Agent。只输出节点列表，不执行具体工作。汇总时整合所有结果，注明未完成项。"; val WHITELIST = emptySet<String>() }
}

/** 检索Agent：从本地知识库取料（白名单含file_read） */
class RetrievalAgent(memory: MemoryStore, tools: ToolRegistry, engine: InferenceEngine) :
    BaseSubAgent("retrieval", SYSTEM_PROMPT, WHITELIST, memory, tools, engine) {
    companion object { const val SYSTEM_PROMPT = "你是本地知识库检索员。仅依据用户提供的文件与本地记忆查找资料，逐条摘录原文关键内容并标注来源，不做推理加工。找不到时如实说明，禁止编造内容。"; val WHITELIST = setOf("file_read") }
    override fun confidenceOf(answer: String) = if (answer.isBlank()) 0.1 else 0.85
}

/** 分析Agent：归纳、对比、提炼要点 */
class AnalysisAgent(memory: MemoryStore, tools: ToolRegistry, engine: InferenceEngine) :
    BaseSubAgent("analysis", SYSTEM_PROMPT, WHITELIST, memory, tools, engine) {
    companion object { const val SYSTEM_PROMPT = "你是分析员。基于给定的检索材料做归纳、对比与推理，输出结构化要点（编号列表），区分事实与推断。材料不足时明确列出缺口，不脑补。"; val WHITELIST = emptySet<String>() }
}

/** 创作Agent：文本生成与改写 */
class CreationAgent(memory: MemoryStore, tools: ToolRegistry, engine: InferenceEngine) :
    BaseSubAgent("creation", SYSTEM_PROMPT, WHITELIST, memory, tools, engine) {
    companion object { const val SYSTEM_PROMPT = "你是创作者。依据分析结论撰写或改写文本，风格简洁自然、贴合中文表达习惯，直接给出成品正文，不加解释性开场白。"; val WHITELIST = emptySet<String>() }
}

/** 工具执行Agent：代理调用白名单工具（calculator等） */
class ToolExecAgent(memory: MemoryStore, tools: ToolRegistry, engine: InferenceEngine) :
    BaseSubAgent("tool_exec", SYSTEM_PROMPT, WHITELIST, memory, tools, engine) {

    companion object { const val SYSTEM_PROMPT = "你是工具执行员。从用户指令中提取需要计算或操作的数据，选择合适工具执行并原样报告结果。绝不调用白名单之外的任何工具。"; val WHITELIST = setOf("calculator", "clipboard_read") }

    /** 覆盖infer：先尝试识别并执行工具调用，失败再走LLM兜底 */
    override suspend fun infer(prompt: String): String {
        // 简易意图：检测"计算/calculate"则直接调calculator
        val expr = Regex("""[-(]?\d+(\.\d+)?([+\-*/][-()]?\d+(\.\d+)?)+""").find(prompt)?.value
        if (expr != null) {
            val args = buildJsonObject { put("expr", JsonPrimitive(expr)) }.toString()
            val r = tools.invoke(id, "calculator", args)
            return if (r.ok) "计算结果: $expr = ${r.data}" else "工具拒绝: ${r.error}"
        }
        return super.infer(prompt)
    }
}

/** 校验Agent：核查事实一致性与格式；结果写入L3长期记忆（架构§7 T5） */
class VerifierAgent(memory: MemoryStore, tools: ToolRegistry, engine: InferenceEngine) :
    BaseSubAgent("verifier", SYSTEM_PROMPT, WHITELIST, memory, tools, engine) {

    companion object { const val SYSTEM_PROMPT = "你是校验员。对照原始材料核对创作成果：事实是否一致、格式是否完整、语气是否恰当。输出通过与否及问题清单（issues），并给0-1置信度。发现问题仅标注，不打断流程。"; val WHITELIST = emptySet<String>() }

    override suspend fun onMessage(msg: AgentMessage): AgentMessage {
        val result = super.onMessage(msg)
        // 校验结论沉淀到L3长期记忆
        (result.payload as? kotlinx.serialization.json.JsonObject)?.get("summary")?.let {
            memory.upsertLongTerm("verify_${msg.taskId}", it.toString(), listOf("verification"))
        }
        return result
    }
}
