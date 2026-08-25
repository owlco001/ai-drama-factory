// MessageBus路由 / 三层记忆 / 工具白名单 单元测试
package com.agentteam.core

import com.agentteam.core.bus.DefaultMessageBus
import com.agentteam.core.memory.InMemoryMemoryStore
import com.agentteam.core.message.MessageType
import com.agentteam.core.tools.DefaultToolRegistry
import com.agentteam.core.tools.ToolResult
import com.agentteam.core.tools.impl.CalculatorTool
import com.agentteam.core.tools.impl.ClipboardReadTool
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CoreModulesTest {

    @Test
    fun `消息总线按taskId过滤历史且send返回msg_id`() = runTest {
        val bus = DefaultMessageBus()
        val m = msg(taskId = "t1")
        val id = bus.send(m)
        assertEquals(m.msgId, id)
        assertEquals(1, bus.history("t1").size)
        assertEquals(0, bus.history("t2").size)
    }

    @Test
    fun `消息流广播给多个订阅者`() = runTest {
        val bus = DefaultMessageBus()
        val received = mutableListOf<String>()
        // 两个订阅者都应收到（backgroundScope自动清理，避免UncompletedCoroutinesError）
        backgroundScope.launch { bus.messages.collect { received.add("s1:${it.msgId}") } }
        backgroundScope.launch { bus.messages.collect { received.add("s2:${it.msgId}") } }
        testScheduler.runCurrent()   // 确保订阅者已挂起在collect上再发送
        bus.send(msg())
        testScheduler.runCurrent()
        assertEquals(2, received.size)
    }

    @Test
    fun `L1短期记忆窗口裁剪最旧条目`() = runTest {
        val store = InMemoryMemoryStore(shortTermWindowTokens = 20)
        repeat(10) { store.appendShortTerm("s", "user", "第${it}条消息内容比较长一点") }
        val ctx = store.shortTermContext("s", 20)
        assertTrue(ctx.size < 10)   // 已裁剪
        assertTrue(ctx.last().content.contains("9"))   // 保留最新
    }

    @Test
    fun `L2任务记忆按task隔离`() = runTest {
        val store = InMemoryMemoryStore()
        store.putTaskMemory("t1", "n1", "retrieval", "结果A")
        store.putTaskMemory("t2", "n1", "analysis", "结果B")
        assertEquals(1, store.taskMemory("t1").size)
        assertEquals(1, store.taskMemory("t2").size)
        assertEquals("结果A", store.taskMemory("t1").first().content)
    }

    @Test
    fun `L3长期记忆upsert与检索`() = runTest {
        val store = InMemoryMemoryStore()
        store.upsertLongTerm("pref:style", "用户偏好简洁风格", listOf("pref"))
        store.upsertLongTerm("fact:x", "项目截止周五", listOf("work"))
        val hits = store.searchLongTerm("简洁", 5)
        assertEquals(1, hits.size)
        assertEquals("用户偏好简洁风格", hits.first().content)
    }

    @Test
    fun `工具白名单外调用被拒绝`() = runTest {
        val reg = DefaultToolRegistry()
        reg.register(CalculatorTool, allowedAgents = setOf("tool_exec"))   // 仅工具执行Agent可见
        reg.register(ClipboardReadTool(), allowedAgents = setOf("retrieval"))

        val ok = reg.invoke("tool_exec", "calculator", """{"expr":"(1+2)*3"}""")
        assertTrue(ok.ok); assertEquals("9.0", ok.data)

        val denied = reg.invoke("creation", "calculator", """{"expr":"1+1"}""")   // 白名单外
        assertFalse(denied.ok)
        assertTrue(denied.error!!.startsWith("TOOL_DENIED"))

        val unknown = reg.invoke("coordinator", "nonexistent", "{}")
        assertTrue(unknown.error!!.contains("未注册"))
    }

    @Test
    fun `UUIDv7格式与时间有序性`() {
        val a = com.agentteam.core.util.newUuidV7()
        Thread.sleep(5)
        val b = com.agentteam.core.util.newUuidV7()
        assertTrue(a < b)   // 时间有序（字典序即时间序）
        assertTrue(a.length == 36)
    }

    private fun msg(taskId: String? = null) = com.agentteam.core.message.AgentMessage(
        msgId = com.agentteam.core.util.newUuidV7(),
        from = "user", to = "coordinator",
        type = MessageType.USER_INPUT,
        payload = kotlinx.serialization.json.buildJsonObject { put("text", kotlinx.serialization.json.JsonPrimitive("hi")) },
        timestamp = System.currentTimeMillis(), taskId = taskId,
    )
}
