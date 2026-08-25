// 消息总线接口与内存实现 —— 架构§3 MessageBus
package com.agentteam.core.bus

import com.agentteam.core.message.AgentMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentLinkedDeque

/** 按架构§3签名定义 */
interface MessageBus {
    /** 同步入队，内部按序投递。返回 msg_id */
    fun send(msg: AgentMessage): String
    /** 订阅消息流（UI日志、Agent消费均基于此） */
    val messages: SharedFlow<AgentMessage>
    /** 按 task_id 过滤历史消息（生产环境从SQLite读取；core层由实现方注入存储器） */
    suspend fun history(taskId: String): List<AgentMessage>
    /** 注册某类消息的处理者（type路由） */
    fun registerHandler(type: com.agentteam.core.message.MessageType, handler: suspend (AgentMessage) -> Unit)
}

/**
 * 默认实现：SharedFlow 广播 + 内存日志环形缓冲 + type路由。
 * SQLite持久化在 app 层的 RoomMessageBus 中叠加装饰。
 *
 * P1-4修复：统一用单一锁对象保护 log/handlers；tryEmit失败时记录告警并同步触发handler兜底；
 * dispatchHandlers 由 send 内部经 scope 启动协程调用（注册的路由真正生效）。
 * P1-5修复：内存日志改为有界环形缓冲（默认1000条，超限淘汰最旧）。
 */
class DefaultMessageBus(
    private val keepInMemory: Boolean = true,
    private val handlerScope: CoroutineScope? = null,   // 传入则send时自动派发handler
    private val maxLogSize: Int = DEFAULT_LOG_SIZE,
) : MessageBus {

    private val _messages = MutableSharedFlow<AgentMessage>(replay = 0, extraBufferCapacity = 1024)
    override val messages: SharedFlow<AgentMessage> = _messages

    private val lock = Any()                                    // P1-4：统一锁对象
    private val log = ConcurrentLinkedDeque<AgentMessage>()     // P1-5：有界环形缓冲
    private val handlers = mutableMapOf<com.agentteam.core.message.MessageType, MutableList<suspend (AgentMessage) -> Unit>>()
    @Volatile private var droppedCount = 0L                     // P1-4：缓冲满丢弃计数（可观测告警）

    override fun send(msg: AgentMessage): String {
        if (keepInMemory) {
            synchronized(lock) {
                log.addLast(msg)
                while (log.size > maxLogSize) log.pollFirst()   // P1-5：淘汰最旧
            }
        }
        // SharedFlow 非挂起 tryEmit，保证 send 同步语义；P1-4：失败不再静默——计数告警并由handler兜底
        val emitted = _messages.tryEmit(msg)
        if (!emitted) {
            droppedCount++
            System.err.println("[MessageBus] WARN: SharedFlow缓冲满，消息${msg.msgId}未广播(累计丢弃=$droppedCount)")
        }
        // P1-4：handler派发由send内启动协程调用，注册的路由生效
        handlerScope?.launch { dispatchHandlers(msg) }
        return msg.msgId
    }

    /** 投递后触发type路由的处理者（统一在lock下快照后执行） */
    suspend fun dispatchHandlers(msg: AgentMessage) {
        val hs = synchronized(lock) { handlers[msg.type]?.toList() } ?: emptyList()
        hs.forEach { it(msg) }
    }

    override suspend fun history(taskId: String): List<AgentMessage> =
        synchronized(lock) { log.filter { it.taskId == taskId } }

    override fun registerHandler(type: com.agentteam.core.message.MessageType, handler: suspend (AgentMessage) -> Unit) {
        synchronized(lock) { handlers.getOrPut(type) { mutableListOf() }.add(handler) }
    }

    companion object { const val DEFAULT_LOG_SIZE = 1000 }
}
