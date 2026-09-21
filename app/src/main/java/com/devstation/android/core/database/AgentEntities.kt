package com.devstation.android.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Phase 6: persisted agent task metadata. Contains NO secrets, NO raw provider payloads,
 * and NO hidden chain-of-thought.
 */
@Entity(tableName = "agent_tasks", indices = [Index("projectId"), Index("conversationId")])
data class AgentTaskEntity(
    @PrimaryKey val taskId: String,
    val projectId: String,
    val conversationId: String? = null,
    val goal: String,
    /** IDLE, PLANNING, WAITING_FOR_APPROVAL, EXECUTING_TOOL, WAITING_FOR_MODEL,
     *  COMPLETED, FAILED, CANCELLED, PAUSED, INTERRUPTED */
    val state: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val iterationCount: Int = 0,
    val toolCallCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val errorMessage: String? = null
)

/** Ordered agent timeline entries for a task (state changes, tool actions, approvals). */
@Entity(tableName = "agent_events", indices = [Index("taskId")])
data class AgentEventEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val type: String,
    val label: String,
    val detail: String? = null,
    /** RUNNING, SUCCESS, FAILED, DENIED, CANCELLED, PENDING */
    val status: String = "RUNNING",
    val createdAt: Long
)

/** Phase 6 action history: what the agent did, so the user can audit it later. */
@Entity(tableName = "agent_action_history", indices = [Index("taskId"), Index("projectId")])
data class AgentActionHistoryEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val projectId: String,
    val toolName: String,
    val actionSummary: String,
    /** SUCCESS, FAILED, DENIED, CANCELLED, TIMEOUT */
    val status: String,
    val createdAt: Long
)

/**
 * Task-scoped tool approvals. Rows exist ONLY while a task is running and are deleted when
 * the task ends or the app restarts — Phase 6 never persists permanent agent permissions.
 */
@Entity(tableName = "agent_task_permissions", primaryKeys = ["taskId", "toolName"])
data class AgentTaskPermissionEntity(
    val taskId: String,
    val toolName: String,
    /** PER_REQUEST or PER_TASK */
    val scope: String,
    val grantedAt: Long
)

@Dao
interface AgentTaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE taskId = :taskId")
    suspend fun getById(taskId: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestForConversation(conversationId: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE state NOT IN ('COMPLETED','FAILED','CANCELLED','INTERRUPTED')")
    suspend fun getActiveTasks(): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getForProject(projectId: String, limit: Int = 50): List<AgentTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: AgentTaskEntity)

    @Query(
        "UPDATE agent_tasks SET state = :state, iterationCount = :iterations, toolCallCount = :toolCalls, updatedAt = :updatedAt WHERE taskId = :taskId"
    )
    suspend fun updateProgress(taskId: String, state: String, iterations: Int, toolCalls: Int, updatedAt: Long)

    @Query("UPDATE agent_tasks SET state = :state, errorMessage = :error, updatedAt = :updatedAt WHERE taskId = :taskId")
    suspend fun updateState(taskId: String, state: String, error: String?, updatedAt: Long)

    @Query("DELETE FROM agent_tasks WHERE taskId = :taskId")
    suspend fun delete(taskId: String)
}

@Dao
interface AgentEventDao {
    @Query("SELECT * FROM agent_events WHERE taskId = :taskId ORDER BY createdAt ASC")
    suspend fun getForTask(taskId: String): List<AgentEventEntity>

    @Query("SELECT * FROM agent_events WHERE taskId = :taskId ORDER BY createdAt ASC")
    fun observeForTask(taskId: String): Flow<List<AgentEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: AgentEventEntity)

    @Query("DELETE FROM agent_events WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)
}

@Dao
interface AgentActionHistoryDao {
    @Query("SELECT * FROM agent_action_history ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 200): List<AgentActionHistoryEntity>

    @Query("SELECT * FROM agent_action_history ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<AgentActionHistoryEntity>>

    @Query("SELECT * FROM agent_action_history WHERE taskId = :taskId ORDER BY createdAt ASC")
    suspend fun getForTask(taskId: String): List<AgentActionHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AgentActionHistoryEntity)

    @Query("DELETE FROM agent_action_history WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)
}

@Dao
interface AgentTaskPermissionDao {
    @Query("SELECT toolName FROM agent_task_permissions WHERE taskId = :taskId AND scope = 'PER_TASK'")
    suspend fun getTaskScopedToolNames(taskId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(permission: AgentTaskPermissionEntity)

    @Query("DELETE FROM agent_task_permissions WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)

    @Query("DELETE FROM agent_task_permissions")
    suspend fun deleteAll()
}
