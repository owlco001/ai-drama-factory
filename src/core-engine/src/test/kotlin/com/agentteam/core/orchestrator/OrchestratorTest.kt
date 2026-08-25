// 调度轮次上限与消息路由正确性测试
package com.agentteam.core.orchestrator

import com.agentteam.core.agent.BaseSubAgent
import com.agentteam.core.bus.DefaultMessageBus
import com.agentteam.core.memory.InMemoryMemoryStore
import com.agentteam.core.infer.FakeEngine
import com.agentteam.core.message.MessageType
import com.agentteam.core.message.MsgStatus
import com.agentteam.core.model.NodeState
import com.agentteam.core.tools.DefaultToolRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OrchestratorTest {

    private class RecordingAgent(id: String, m: InMemoryMemoryStore, t: DefaultToolRegistry, e: FakeEngine) :
        BaseSubAgent(id, "test-prompt", emptySet(), m, t, e)

    private fun buildAgents(bus: DefaultMessageBus): Map<String, com.agentteam.core.agent.SubAgent> {
        val store = InMemoryMemoryStore()
        val tools = DefaultToolRegistry()
        val engine = FakeEngine()
        return listOf("coordinator", "retrieval", "analysis", "creation", "tool_exec", "verifier")
            .associateWith { RecordingAgent(it, store, tools, engine) }
    }

    @Test
    fun `正常输入产出FINAL_OUTPUT且四节点全部SUCCESS`() = runTest {
        val bus = DefaultMessageBus()
        val orch = DefaultOrchestrator(bus, InMemoryMemoryStore(), buildAgents(bus))
        val result = orch.handleUserInput("帮我写一封跟进邮件")

        assertTrue(result.success)
        val dag = orch.dagState.value
        assertEquals(4, dag.nodes.size)
        assertEquals(NodeState.SUCCESS, dag.nodes.last().state)
        // 消息日志应含 TASK_PLAN / 4×(ASSIGN+RESULT) / FINAL_OUTPUT
        val hist = bus.history(result.taskId)
        assertEquals(1, hist.count { it.type == MessageType.TASK_PLAN })
        assertEquals(4, hist.count { it.type == MessageType.TASK_ASSIGN })
        assertEquals(1, hist.count { it.type == MessageType.FINAL_OUTPUT && it.to == "user" })
    }

    @Test
    fun `失败节点触发一次重试后仍失败则标FAILED`() = runTest {
        var calls = 0
        val flaky = object : com.agentteam.core.agent.SubAgent {
            override val id = "retrieval"; override val systemPrompt = ""; override val toolWhitelist = emptySet<String>()
            override suspend fun onMessage(msg: com.agentteam.core.message.AgentMessage) =
                msg.copy(msgId = msg.msgId + "-r", from = id, to = "coordinator",
                    type = MessageType.TASK_RESULT, status = MsgStatus.FAILED).also { calls++ }
        }
        val bus = DefaultMessageBus()
        val agents = mapOf("retrieval" to flaky)
        val orch = DefaultOrchestrator(bus, InMemoryMemoryStore(), agents)
        val result = orch.handleUserInput("@检索 找一下会议纪要")   // @点名直达单节点

        assertFalse(result.success)
        assertEquals(RunBudget.RETRY_PER_NODE + 1, calls)   // 共尝试2次（重试上限1）
        assertEquals(NodeState.FAILED, orch.dagState.value.nodes.first().state)
    }

    @Test
    fun `拓扑校验拦截子Agent间直接通信与非法to_user`() {
        val bad = com.agentteam.core.message.AgentMessage(
            msgId = "m1", from = "retrieval", to = "analysis",
            type = MessageType.AGENT_DIRECT,
            payload = kotlinx.serialization.json.JsonObject(emptyMap()),
            timestamp = 0L, taskId = null)
        assertTrue(com.agentteam.core.message.TopologyValidator.validate(bad)?.contains("违反星型拓扑") == true)
        val toUser = bad.copy(from = "coordinator", to = "user", type = MessageType.TASK_RESULT)
        assertNotNull(com.agentteam.core.message.TopologyValidator.validate(toUser))
    }

    @Test
    fun `@点名语法解析正确`() = runTest {
        val bus = DefaultMessageBus()
        val orch = DefaultOrchestrator(bus, InMemoryMemoryStore(), buildAgents(bus))
        assertNull(orch.parseDirectMention("普通输入"))
        val direct = orch.parseDirectMention("@校验 复核上一段")
        assertEquals("verifier", direct?.agentId)
        assertEquals("复核上一段", direct?.instruction)
    }

    @Test
    fun `取消后任务立即终止`() = runTest {
        val bus = DefaultMessageBus()
        val orch = DefaultOrchestrator(bus, InMemoryMemoryStore(), buildAgents(bus))
        orch.cancelCurrent()
        val result = orch.handleUserInput("任意任务")
        assertFalse(result.success)   // 全部SKIPPED，无有效产出
        assertTrue(orch.dagState.value.nodes.all { it.state == NodeState.SKIPPED })
    }
}
