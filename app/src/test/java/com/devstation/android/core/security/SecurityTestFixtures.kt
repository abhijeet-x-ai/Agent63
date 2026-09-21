package com.devstation.android.core.security

import com.devstation.android.core.agent.PermissionManager
import com.devstation.android.core.database.PermissionGrantDao
import com.devstation.android.core.database.PermissionGrantEntity
import com.devstation.android.core.database.ProjectSecuritySettingsDao
import com.devstation.android.core.database.ProjectSecuritySettingsEntity
import com.devstation.android.core.database.SecurityEventDao
import com.devstation.android.core.database.SecurityEventEntity
import com.devstation.android.core.database.SecuritySettingsDao
import com.devstation.android.core.database.SecuritySettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory Room DAOs so Phase 7 persistence can be tested without Android. */

class FakeSecuritySettingsDao : SecuritySettingsDao {
    private val state = MutableStateFlow<SecuritySettingsEntity?>(null)
    override suspend fun get(): SecuritySettingsEntity? = state.value
    override fun observe(): Flow<SecuritySettingsEntity?> = state.asStateFlow()
    override suspend fun upsert(settings: SecuritySettingsEntity) {
        state.value = settings
    }
}

class FakeProjectSecuritySettingsDao : ProjectSecuritySettingsDao {
    private val state = MutableStateFlow<Map<String, ProjectSecuritySettingsEntity>>(emptyMap())
    override suspend fun get(projectId: String): ProjectSecuritySettingsEntity? = state.value[projectId]
    override fun observeAll(): Flow<List<ProjectSecuritySettingsEntity>> =
        MutableStateFlow(state.value.values.toList()).asStateFlow()

    override suspend fun upsert(settings: ProjectSecuritySettingsEntity) {
        state.value = state.value + (settings.projectId to settings)
    }

    override suspend fun delete(projectId: String) {
        state.value = state.value - projectId
    }
}

class FakeSecurityEventDao : SecurityEventDao {
    private val rows = MutableStateFlow<List<SecurityEventEntity>>(emptyList())

    override suspend fun recent(limit: Int): List<SecurityEventEntity> =
        rows.value.sortedByDescending { it.timestamp }.take(limit)

    override fun observeRecent(limit: Int): Flow<List<SecurityEventEntity>> = MutableStateFlow(
        rows.value.sortedByDescending { it.timestamp }.take(limit)
    ).asStateFlow()

    override suspend fun forTask(taskId: String, limit: Int): List<SecurityEventEntity> =
        recent(limit).filter { it.taskId == taskId }

    override suspend fun insert(event: SecurityEventEntity) {
        rows.value = rows.value + event
    }

    override suspend fun deleteOlderThan(timestamp: Long): Int {
        val before = rows.value.size
        rows.value = rows.value.filterNot { it.timestamp < timestamp }
        return before - rows.value.size
    }

    override suspend fun deleteAll(): Int {
        val before = rows.value.size
        rows.value = emptyList()
        return before
    }

    fun all(): List<SecurityEventEntity> = rows.value
}

class FakePermissionGrantDao : PermissionGrantDao {
    private val rows = MutableStateFlow<List<PermissionGrantEntity>>(emptyList())

    override suspend fun all(): List<PermissionGrantEntity> = rows.value
    override fun observeAll(): Flow<List<PermissionGrantEntity>> = rows.asStateFlow()

    override suspend fun findGrant(
        scope: String,
        toolName: String,
        taskId: String?,
        sessionId: String?,
        projectId: String?,
        now: Long
    ): String? = rows.value.firstOrNull {
        it.scope == scope && it.toolName == toolName &&
            (rowExpiry(it) == null || rowExpiry(it)!! > now) &&
            (taskId == null || it.taskId == taskId) &&
            (sessionId == null || it.sessionId == sessionId) &&
            (projectId == null || it.projectId == projectId)
    }?.toolName

    override suspend fun toolNamesFor(
        scope: String,
        sessionId: String?,
        projectId: String?,
        now: Long
    ): List<String> = rows.value.filter {
        it.scope == scope && (rowExpiry(it) == null || rowExpiry(it)!! > now) &&
            (sessionId == null || it.sessionId == sessionId) &&
            (projectId == null || it.projectId == projectId)
    }.map { it.toolName }

    override suspend fun activeScopedGrants(now: Long): List<PermissionGrantEntity> = rows.value.filter {
        it.scope in setOf("PROJECT", "GLOBAL") && (rowExpiry(it) == null || rowExpiry(it)!! > now)
    }

    private fun rowExpiry(row: PermissionGrantEntity): Long? = row.expiresAt

    override suspend fun upsert(grant: PermissionGrantEntity) {
        rows.value = rows.value.filterNot { it.id == grant.id } + grant
    }

    override suspend fun deleteForTask(taskId: String): Int = remove { it.taskId == taskId }
    override suspend fun deleteForSession(sessionId: String): Int = remove { it.sessionId == sessionId }
    override suspend fun deleteForProject(projectId: String): Int = remove { it.projectId == projectId }
    override suspend fun deleteForScope(scope: String): Int = remove { it.scope == scope }

    override suspend fun deleteExpired(now: Long): Int =
        remove { row -> val expiresAt = row.expiresAt; expiresAt != null && expiresAt <= now }

    override suspend fun deleteAll(): Int {
        val before = rows.value.size
        rows.value = emptyList()
        return before
    }

    private fun remove(predicate: (PermissionGrantEntity) -> Boolean): Int {
        val before = rows.value.size
        rows.value = rows.value.filterNot(predicate)
        return before - rows.value.size
    }
}

/** A grant lookup that records what it was asked, for isolation assertions. */
class RecordingGrantLookup(
    private val granted: MutableSet<String> = mutableSetOf()
) : com.devstation.android.core.security.policy.SecurityGrantLookup {

    fun allow(scope: com.devstation.android.core.agent.PermissionScope, tool: String) {
        granted.add("${scope.name}:$tool")
    }

    override fun hasGrant(
        scope: com.devstation.android.core.agent.PermissionScope,
        toolName: String,
        taskId: String?,
        sessionId: String?,
        projectId: String?
    ): Boolean = granted.contains("${scope.name}:$toolName")
}

/** Convenience: a PermissionManager with no persistence. */
fun testPermissionManager(): PermissionManager =
    PermissionManager(broker = com.devstation.android.core.agent.ApprovalBroker())
