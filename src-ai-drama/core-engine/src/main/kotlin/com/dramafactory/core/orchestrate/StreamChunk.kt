package com.dramafactory.core.orchestrate

/**
 * T002：流式对话输出块。
 *
 * AI 助手的回复以流式逐段送达 UI。除自然语言文字外，还允许携带
 * 模块调用结果（如「已为你提取资产」「分镜已生成」），以便 UI 结构化成独立卡片。
 */
sealed class StreamChunk {
    /** 自然语言增量（逐字/逐段） */
    data class TextDelta(val text: String) : StreamChunk()

    /** 一个模块动作执行完毕（结构化卡片）。module 为模块名，result 为结果一句话 */
    data class ActionComplete(val module: String, val result: String) : StreamChunk()

    /** 流结束（携带完整回复，供收尾/落库） */
    data class Done(val fullText: String) : StreamChunk()
}