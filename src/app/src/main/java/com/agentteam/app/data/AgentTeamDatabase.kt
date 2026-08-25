// SQLite建表SQL（严格按architecture.md §5）+ Room实体
// 数据库单文件 agent_team.db，WAL模式；三层记忆分表 + 消息日志 + 任务DAG
package com.agentteam.app.data

import androidx.room.*

/** L1 会话短期记忆：滚动窗口，超上限删最旧 */
@Entity(tableName = "memory_short_term", indices = [Index("session_id", "created_at")])
data class ShortTermEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val session_id: String,        // MVP固定'session_default'
    val role: String,              // 'user'/'agent:<id>'/'system'
    val content: String,
    val tokens: Int = 0,           // 写入时估算，用于窗口裁剪
    val created_at: Long,
)

/** L2 任务级中间结果：task_id隔离，任务结束保留 */
@Entity(tableName = "memory_task", indices = [Index("task_id", "node_id")])
data class TaskMemoryEntity(
    @PrimaryKey val memory_id: String,   // 'mem_' || uuid
    val task_id: String,
    val node_id: String,
    val agent_id: String,
    val content: String,
    val created_at: Long,
)

/** L3 全局长期记忆：键值+标签（FTS5由原始SQL触发器维护） */
@Entity(tableName = "memory_long_term")
data class LongTermEntity(
    @PrimaryKey val key: String,         // 如 'pref:user_style'
    val content: String,
    val tags: String = "",               // 逗号分隔
    val updated_at: Long,
)

/** 消息日志（F07追溯能力持久化底座） */
@Entity(tableName = "message_log", indices = [Index("task_id", "ts")])
data class MessageLogEntity(
    @PrimaryKey val msg_id: String,
    val from_id: String,
    val to_id: String,
    val type: String,
    val payload: String,          // 原始JSON
    val task_id: String?,
    val reply_to: String?,
    val status: String = "OK",
    val ts: Long,
)

/** 任务DAG节点（F05/F11回放） */
@Entity(tableName = "task_dag", primaryKeys = ["task_id", "node_id"])
data class TaskDagEntity(
    val task_id: String,
    val node_id: String,
    val agent_id: String,
    val instruction: String,
    val depends_on: String = "",  // JSON数组字符串
    val state: String = "PENDING",
    val started_at: Long? = null,
    val ended_at: Long? = null,
)

/**
 * 数据库定义。FTS5虚拟表/触发器/WAL等Room注解不支持的部分用 createFromAsset 之外的方式：
 * 通过提供的 agent_team_schema.sql 在首次启动时由 SupportSQLiteOpenHelper.Callback 执行。
 * （见 assets/agent_team_schema.sql —— 与 architecture.md §5 逐句一致）
 */
@Database(
    entities = [
        ShortTermEntity::class, TaskMemoryEntity::class, LongTermEntity::class,
        MessageLogEntity::class, TaskDagEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AgentTeamDatabase : RoomDatabase() {
    abstract fun shortTermDao(): ShortTermDao
    abstract fun taskMemoryDao(): TaskMemoryDao
    abstract fun longTermDao(): LongTermDao
    abstract fun messageLogDao(): MessageLogDao
    abstract fun taskDagDao(): TaskDagDao
}

@Dao interface ShortTermDao {
    @Insert suspend fun insert(e: ShortTermEntity)
    /** 窗口裁剪：删除指定会话中最旧的条目直至总量≤上限 */
    @Query("DELETE FROM memory_short_term WHERE session_id=:sessionId AND id NOT IN (SELECT id FROM memory_short_term WHERE session_id=:sessionId ORDER BY created_at DESC LIMIT :keep)")
    suspend fun trim(sessionId: String, keep: Int)
    @Query("SELECT * FROM memory_short_term WHERE session_id=:sessionId ORDER BY created_at ASC")
    suspend fun all(sessionId: String): List<ShortTermEntity>
}

@Dao interface TaskMemoryDao {
    @Insert suspend fun insert(e: TaskMemoryEntity)
    @Query("SELECT * FROM memory_task WHERE task_id=:taskId ORDER BY created_at ASC")
    suspend fun byTask(taskId: String): List<TaskMemoryEntity>
    /** 容量策略：仅保留最近50个任务的中间结果 */
    @Query("DELETE FROM memory_task WHERE task_id NOT IN (SELECT DISTINCT task_id FROM (SELECT task_id FROM memory_task GROUP BY task_id ORDER BY MAX(created_at) DESC LIMIT :keepTasks))")
    suspend fun evictOld(keepTasks: Int)
}

@Dao interface LongTermDao {
    @Upsert suspend fun upsert(e: LongTermEntity)
    @Query("SELECT * FROM memory_long_term WHERE content LIKE '%'||:query||'%' OR tags LIKE '%'||:query||'%' LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<LongTermEntity>   // FTS5命中走触发器表，此为降级路径
}

@Dao interface MessageLogDao {
    @Insert suspend fun insert(e: MessageLogEntity)
    @Query("SELECT * FROM message_log WHERE task_id=:taskId ORDER BY ts ASC")
    suspend fun byTask(taskId: String): List<MessageLogEntity>
}

@Dao interface TaskDagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: TaskDagEntity)
    @Query("SELECT * FROM task_dag WHERE task_id=:taskId ORDER BY node_id ASC")
    suspend fun byTask(taskId: String): List<TaskDagEntity>
}
