// 三层记忆 MemoryStore 接口 + 内存实现 —— 架构§3 / §5
// 生产实现在app层用Room+SQLite分表；此为core层的纯内存实现（可测试）
package com.agentteam.core.memory

import com.agentteam.core.model.MemoryItem

interface MemoryStore {
    // L1 会话短期记忆：滑动窗口，超上限自动裁剪
    suspend fun appendShortTerm(sessionId: String, role: String, content: String)
    suspend fun shortTermContext(sessionId: String, maxTokens: Int): List<MemoryItem>
    suspend fun trimShortTerm(sessionId: String)

    // L2 任务级中间结果：task_id隔离。返回由MemoryStore生成的memoryId（供context_refs引用）
    suspend fun putTaskMemory(taskId: String, nodeId: String, agentId: String, content: String): String
    suspend fun taskMemory(taskId: String): List<MemoryItem>

    // L3 全局长期记忆：键值+标签，FTS5全文检索
    suspend fun upsertLongTerm(key: String, content: String, tags: List<String> = emptyList())
    suspend fun searchLongTerm(query: String, limit: Int = 5): List<MemoryItem>
}

/** 简易token估算：中文≈1字1token，英文按4字符1token（仅用于窗口裁剪） */
fun estimateTokens(text: String): Int =
    text.count { it.code > 0x2E7F } + (text.count { it.code <= 0x2E7F } + 3) / 4

/** 纯内存实现。L1窗口默认4096 token（架构§5容量策略） */
class InMemoryMemoryStore(
    private val shortTermWindowTokens: Int = 4096,
    private val longTermMaxEntries: Int = 1000,
) : MemoryStore {

    private val l1 = mutableListOf<MemoryItem>()          // 全局线性表，sessionId编码进roleOrKey前缀
    private val l2 = mutableMapOf<String, MutableList<Triple<String, String, String>>>() // task -> [(memId,nodeId,agentId,content)]
    private val l3 = linkedMapOf<String, Pair<String, Long>>() // key -> (content|tags, updatedAt)，LinkedHashMap保LRU序
    private var seq = 0L

    private fun sid(sessionId: String, item: MemoryItem) = item.roleOrKey.startsWith("$sessionId\u0000")

    override suspend fun appendShortTerm(sessionId: String, role: String, content: String) {
        val memId = "st_${++seq}"
        l1.add(MemoryItem(memId, "$sessionId\u0000$role", content, System.currentTimeMillis()))
        trimShortTerm(sessionId)
    }

    override suspend fun shortTermContext(sessionId: String, maxTokens: Int): List<MemoryItem> {
        var acc = 0
        return l1.filter { sid(sessionId, it) }
            .takeLastWhile { acc += estimateTokens(it.content); acc <= maxTokens }
            .map { it.copy(roleOrKey = it.roleOrKey.substringAfter('\u0000')) }
    }

    /** 超窗口删最旧条目（真实摘要注入由创作Agent生成，此处先直接删除） */
    override suspend fun trimShortTerm(sessionId: String) {
        while (l1.filter { sid(sessionId, it) }.sumOf { estimateTokens(it.content) } > shortTermWindowTokens) {
            val oldest = l1.indexOfFirst { sid(sessionId, it) }
            if (oldest < 0) break
            l1.removeAt(oldest)
        }
    }

    override suspend fun putTaskMemory(taskId: String, nodeId: String, agentId: String, content: String): String {
        val list = l2.getOrPut(taskId) { mutableListOf() }
        val memId = "mem_task_${++seq}_$nodeId"
        list += Triple(memId, nodeId, content)
        return memId
    }

    override suspend fun taskMemory(taskId: String): List<MemoryItem> =
        (l2[taskId] ?: emptyList()).map { MemoryItem(it.first, it.second, it.third, 0L) }

    override suspend fun upsertLongTerm(key: String, content: String, tags: List<String>) {
        // LRU：先删再插保证最新访问在尾部；超上限淘汰最久未更新的
        l3.remove(key)
        l3[key] = "$content\u0001${tags.joinToString(",")}" to System.currentTimeMillis()
        while (l3.size > longTermMaxEntries) l3.remove(l3.keys.first())
    }

    /** 子串匹配模拟FTS5全文检索（MVP）；生产为SQLite FTS5 */
    override suspend fun searchLongTerm(query: String, limit: Int): List<MemoryItem> =
        l3.entries.filter { query.split(Regex("\\s+")).all { w -> it.key.contains(w) || it.value.first.substringBefore('\u0001').contains(w) } }
            .takeLast(limit)
            .map { MemoryItem(it.key, it.key, it.value.first.substringBefore('\u0001'), it.value.second) }
            .reversed()
}
