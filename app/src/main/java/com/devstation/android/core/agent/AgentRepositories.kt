package com.devstation.android.core.agent

import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.database.AgentActionHistoryDao
import com.devstation.android.core.database.AgentActionHistoryEntity
import com.devstation.android.core.database.AgentEventDao
import com.devstation.android.core.database.AgentEventEntity
import com.devstation.android.core.database.AgentTaskDao
import com.devstation.android.core.database.AgentTaskEntity
import com.devstation.android.core.database.AgentTaskPermissionDao
import com.devstation.android.core.database.AgentTaskPermissionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Narrow persistence ports the runtime depends on. Keeping them small means the agent loop can
 * be unit-tested with in-memory fakes and no Android/Room dependency.
 */
interface AgentTaskStore {
    suspend fun create(task: AgentTaskEntity)
    suspend fun updateProgress(taskId: String, state: AgentState, iterations: Int, toolCalls: Int)
    suspend fun updateState(taskId: String, state: AgentState, error: String? = null)
    suspend fun get(taskId: String): AgentTaskEntity?
    /** Marks every non-terminal task as INTERRUPTED and returns their ids. */
    suspend fun markInterrupted(): List<String>
}

interface AgentEventStore {
    suspend fun append(taskId: String, type: String, label: String, detail: String? = null, status: AgentStepStatus = AgentStepStatus.RUNNING)
}

/** Phase 6 §49: what the agent actually did, for later inspection by the user. */
interface AgentHistoryStore {
    suspend fun append(taskId: String, projectId: String, toolName: String, summary: String, status: String)
    suspend fun getForTask(taskId: String): List<AgentActionHistoryEntity>
    suspend fun getRecent(limit: Int = 200): List<AgentActionHistoryEntity>
}

interface AgentPermissionStore {
    suspend fun grant(taskId: String, toolName: String)
    suspend fun taskScopedTools(taskId: String): Set<String>
    suspend fun clearTask(taskId: String)
    suspend fun clearAll()
}

/** No-op implementations used when persistence is unavailable (and in unit tests). */
object NoOpAgentStores {
    val taskStore = object : AgentTaskStore {
        override suspend fun create(task: AgentTaskEntity) = Unit
        override suspend fun updateProgress(taskId: String, state: AgentState, iterations: Int, toolCalls: Int) = Unit
        override suspend fun updateState(taskId: String, state: AgentState, error: String?) = Unit
        override suspend fun get(taskId: String): AgentTaskEntity? = null
        override suspend fun markInterrupted(): List<String> = emptyList()
    }
    val eventStore = object : AgentEventStore {
        override suspend fun append(taskId: String, type: String, label: String, detail: String?, status: AgentStepStatus) = Unit
    }
    val historyStore = object : AgentHistoryStore {
        override suspend fun append(taskId: String, projectId: String, toolName: String, summary: String, status: String) = Unit
        override suspend fun getForTask(taskId: String) = emptyList<AgentActionHistoryEntity>()
        override suspend fun getRecent(limit: Int) = emptyList<AgentActionHistoryEntity>()
    }
    val permissionStore = object : AgentPermissionStore {
        override suspend fun grant(taskId: String, toolName: String) = Unit
        override suspend fun taskScopedTools(taskId: String) = emptySet<String>()
        override suspend fun clearTask(taskId: String) = Unit
        override suspend fun clearAll() = Unit
    }
}

class RoomAgentTaskStore(
    private val dao: AgentTaskDao,
    private val dispatchers: DispatcherProvider
) : AgentTaskStore {

    override suspend fun create(task: AgentTaskEntity) = withContext(dispatchers.io) { dao.upsert(task) }

    override suspend fun updateProgress(taskId: String, state: AgentState, iterations: Int, toolCalls: Int) =
        withContext(dispatchers.io) {
            dao.updateProgress(taskId, state.name, iterations, toolCalls, System.currentTimeMillis())
        }

    override suspend fun updateState(taskId: String, state: AgentState, error: String?) =
        withContext(dispatchers.io) {
            dao.updateState(taskId, state.name, error, System.currentTimeMillis())
        }

    override suspend fun get(taskId: String): AgentTaskEntity? = withContext(dispatchers.io) { dao.getById(taskId) }

    override suspend fun markInterrupted(): List<String> = withContext(dispatchers.io) {
        val active = dao.getActiveTasks()
        val now = System.currentTimeMillis()
        active.forEach { task ->
            dao.updateState(
                taskId = task.taskId,
                state = AgentState.INTERRUPTED.name,
                error = "App closed before this task finished.",
                updatedAt = now
            )
        }
        active.map { it.taskId }
    }
}

class RoomAgentEventStore(
    private val dao: AgentEventDao,
    private val dispatchers: DispatcherProvider
) : AgentEventStore {
    override suspend fun append(
        taskId: String,
        type: String,
        label: String,
        detail: String?,
        status: AgentStepStatus
    ) = withContext(dispatchers.io) {
        dao.insert(
            AgentEventEntity(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                type = type,
                label = label,
                detail = detail,
                status = status.name,
                createdAt = System.currentTimeMillis()
            )
        )
    }
}

class RoomAgentHistoryStore(
    private val dao: AgentActionHistoryDao,
    private val dispatchers: DispatcherProvider
) : AgentHistoryStore {
    override suspend fun append(taskId: String, projectId: String, toolName: String, summary: String, status: String) =
        withContext(dispatchers.io) {
            dao.insert(
                AgentActionHistoryEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskId,
                    projectId = projectId,
                    toolName = toolName,
                    actionSummary = summary.take(500),
                    status = status,
                    createdAt = System.currentTimeMillis()
                )
            )
        }

    override suspend fun getForTask(taskId: String): List<AgentActionHistoryEntity> =
        withContext(dispatchers.io) { dao.getForTask(taskId) }

    override suspend fun getRecent(limit: Int): List<AgentActionHistoryEntity> =
        withContext(dispatchers.io) { dao.getRecent(limit) }
}

class RoomAgentPermissionStore(
    private val dao: AgentTaskPermissionDao,
    private val dispatchers: DispatcherProvider
) : AgentPermissionStore {
    override suspend fun grant(taskId: String, toolName: String) = withContext(dispatchers.io) {
        dao.upsert(
            AgentTaskPermissionEntity(
                taskId = taskId,
                toolName = toolName,
                scope = PermissionScope.PER_TASK.name,
                grantedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun taskScopedTools(taskId: String): Set<String> =
        withContext(dispatchers.io) { dao.getTaskScopedToolNames(taskId).toSet() }

    override suspend fun clearTask(taskId: String) = withContext(dispatchers.io) { dao.deleteForTask(taskId) }

    override suspend fun clearAll() = withContext(dispatchers.io) { dao.deleteAll() }
}

/** Read-only access to stored task history for the agent UI. */
class AgentTaskHistoryRepository(
    private val dao: AgentTaskDao,
    private val eventDao: AgentEventDao,
    private val actionDao: AgentActionHistoryDao,
    private val dispatchers: DispatcherProvider
) {
    fun observeRecentTasks(limit: Int = 100): Flow<List<AgentTaskEntity>> = dao.observeRecent(limit)

    suspend fun getTask(taskId: String): AgentTaskEntity? = withContext(dispatchers.io) { dao.getById(taskId) }

    suspend fun getEvents(taskId: String): List<AgentEventEntity> =
        withContext(dispatchers.io) { eventDao.getForTask(taskId) }

    /** Phase 6 §49/§77: the audit trail of what the agent actually did for a task. */
    suspend fun getActions(taskId: String): List<AgentActionHistoryEntity> =
        withContext(dispatchers.io) { actionDao.getForTask(taskId) }
}
