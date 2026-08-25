// 核心数据模型：TaskNode / TaskDag / TaskResult / MemoryItem —— 架构§3
package com.agentteam.core.model

enum class NodeState { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

/** 任务DAG节点（串行下 dependsOn 通常为0..1个前驱） */
data class TaskNode(
    val nodeId: String,
    val agentId: String,
    val instruction: String,
    val dependsOn: List<String> = emptyList(),
    var state: NodeState = NodeState.PENDING,
)

data class TaskDag(val taskId: String, val nodes: List<TaskNode>)

data class TaskResult(val taskId: String, val output: String, val success: Boolean)

/** 记忆条目（三层记忆通用返回结构） */
data class MemoryItem(
    val memoryId: String,
    val roleOrKey: String,   // L1为role；L3为key
    val content: String,
    val createdAt: Long,
)

/** @点名直达任务（架构§3 parseDirectMention 返回值） */
data class DirectTask(val agentId: String, val instruction: String)
