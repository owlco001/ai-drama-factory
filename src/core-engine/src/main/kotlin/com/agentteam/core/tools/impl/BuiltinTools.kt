// 内置工具实现（决议Q2）：计算器、剪贴板读取、文件只读
package com.agentteam.core.tools.impl

import com.agentteam.core.tools.AgentTool
import com.agentteam.core.tools.ToolResult

/** 计算器：支持四则运算的安全求值（递归下降解析，不用eval） */
object CalculatorTool : AgentTool {
    override val name = "calculator"
    override val description = "四则运算计算器，输入如 \"(1+2)*3\""
    override val argsSchema = """{"type":"object","required":["expr"],"properties":{"expr":{"type":"string"}}}"""

    override suspend fun execute(argsJson: String): ToolResult {
        val expr = com.agentteam.core.tools.DefaultToolRegistry.arg(argsJson, "expr")
            ?: return ToolResult(false, "", "缺少 expr 参数")
        return try { ToolResult(true, Eval(expr).parse().toString()) }
        catch (e: Exception) { ToolResult(false, "", "表达式非法: ${e.message}") }
    }

    // 递归下降四则运算解析器
    private class Eval(private val s: String) {
        var i = 0
        fun parse(): Double = expr().also { require(i >= s.length) { "多余字符" } }
        fun expr(): Double { var v = term(); while (i < s.length && s[i] in "+-") { val op = s[i++]; v = if (op == '+') v + term() else v - term() }; return v }
        fun term(): Double { var v = atom(); while (i < s.length && s[i] in "*/") { val op = s[i++]; val r = atom(); v = if (op == '*') v * r else { require(r != 0.0) { "除零" }; v / r } }; return v }
        fun atom(): Double {
            if (s[i] == '(') { i++; val v = expr(); require(s[i] == ')'); i++; return v }
            if (s[i] == '-') { i++; return -atom() }   // P2-2：支持一元负号
            val start = i; while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            val v = s.substring(start, i).toDouble()
            require(v.isFinite()) { "非法数字" }
            return v
        }
    }
}

/** 剪贴板读取（core层桩：实际系统剪贴板由app层注入provider） */
class ClipboardReadTool(private val provider: (() -> String?)? = null) : AgentTool {
    override val name = "clipboard_read"
    override val description = "读取系统剪贴板文本内容"
    override val argsSchema = """{"type":"object","properties":{}}"""
    override suspend fun execute(argsJson: String): ToolResult =
        provider?.let { ToolResult(true, it() ?: "") } ?: ToolResult(false, "", "剪贴板服务未接入")
}

/** 文件只读工具：仅允许读取知识库目录下的TXT/MD（决议Q8） */
class FileReadTool(private val baseDir: String = "/data/data/knowledge") : AgentTool {
    override val name = "file_read"
    override val description = "读取本地知识库文件内容（仅TXT/Markdown）"
    override val argsSchema = """{"type":"object","required":["path"],"properties":{"path":{"type":"string"}}}"""

    override suspend fun execute(argsJson: String): ToolResult {
        val path = com.agentteam.core.tools.DefaultToolRegistry.arg(argsJson, "path")
            ?: return ToolResult(false, "", "缺少 path 参数")
        val f = java.io.File(path.removePrefix("knowledge://").let { if (it.startsWith("/")) it else "$baseDir/$it" })
        // 安全校验（P0-2）：canonicalPath已解决 ../ 与symlink；此处用「等于base本身 或 父目录==base」
        // 防兄弟目录前缀绕过（如 knowledge_secrets/）。直接子文件与base自身允许，深层由父链逐级不成立即拒绝。
        val base = java.io.File(baseDir).canonicalFile
        val canon = f.canonicalFile
        val inside = canon.path == base.path || isUnder(canon, base)
        if (!inside) return ToolResult(false, "", "TOOL_DENIED: 越界路径")
        if (!f.exists()) return ToolResult(false, "", "文件不存在")
        if (f.extension.lowercase() !in setOf("txt", "md")) return ToolResult(false, "", "仅支持TXT/MD")
        return ToolResult(true, f.readText(Charsets.UTF_8))
    }

    /** 判断canon是否位于base目录树内（逐级父链比较，防兄弟目录前缀绕过） */
    private fun isUnder(canon: java.io.File, base: java.io.File): Boolean {
        var p: java.io.File? = canon.parentFile
        while (p != null) {
            if (p.canonicalPath == base.path) return true
            p = p.parentFile
        }
        return false
    }
}
