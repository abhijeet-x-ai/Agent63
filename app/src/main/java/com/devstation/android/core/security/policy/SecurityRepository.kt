package com.devstation.android.core.security.policy

import com.devstation.android.core.agent.PermissionScope
import com.devstation.android.core.agent.ToolRiskLevel
import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.database.PermissionGrantDao
import com.devstation.android.core.database.PermissionGrantEntity
import com.devstation.android.core.database.ProjectSecuritySettingsDao
import com.devstation.android.core.database.ProjectSecuritySettingsEntity
import com.devstation.android.core.database.SecurityEventDao
import com.devstation.android.core.database.SecurityEventEntity
import com.devstation.android.core.database.SecuritySettingsDao
import com.devstation.android.core.database.SecuritySettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/** Room-backed audit store. [SecurityAuditLogger] guarantees redaction before rows land here. */
class RoomSecurityAuditStore(
    private val dao: SecurityEventDao,
    private val dispatchers: DispatcherProvider
) : SecurityAuditStore {

    override suspend fun append(event: SecurityAuditEvent) = withContext(dispatchers.io) {
        dao.insert(
            SecurityEventEntity(
                id = event.id,
                timestamp = event.timestamp,
                type = event.type.name,
                decision = event.decision.name,
                risk = event.riskLevel.name,
                projectId = event.projectId,
                taskId = event.taskId,
                sessionId = event.sessionId,
                agentId = event.agentId,
                toolName = event.toolName,
                action = event.action.name,
                resourceType = event.resourceType.name,
                summary = event.summary
            )
        )
    }

    override suspend fun recent(limit: Int): List<SecurityAuditEvent> = withContext(dispatchers.io) {
        dao.recent(limit).map { it.toDomain() }
    }

    override fun observe(limit: Int): Flow<List<SecurityAuditEvent>> = dao.observeRecent(limit).map { rows ->
        rows.map { it.toDomain() }
    }

    override suspend fun deleteOlderThan(timestamp: Long): Int =
        withContext(dispatchers.io) { dao.deleteOlderThan(timestamp) }

    override suspend fun clear(): Int = withContext(dispatchers.io) { dao.deleteAll() }

    private fun SecurityEventEntity.toDomain() = SecurityAuditEvent(
        id = id,
        timestamp = timestamp,
        type = runCatching { SecurityEventType.valueOf(type) }.getOrDefault(SecurityEventType.TOOL_EXECUTED),
        decision = runCatching { AuditDecision.valueOf(decision) }.getOrDefault(AuditDecision.RECORDED),
        riskLevel = runCatching { ToolRiskLevel.valueOf(risk) }.getOrDefault(ToolRiskLevel.LOW),
        projectId = projectId,
        taskId = taskId,
        sessionId = sessionId,
        agentId = agentId,
        toolName = toolName,
        action = runCatching { SecurityAction.valueOf(action) }.getOrDefault(SecurityAction.UNKNOWN),
        resourceType = runCatching { ResourceType.valueOf(resourceType) }.getOrDefault(ResourceType.UNKNOWN),
        summary = summary
    )
}

/**
 * §3/§31/§39/§61: the persistent side of security policy.
 *
 * The policy itself and every grant are readable by the UI and writable only through these methods —
 * there is no code path from the agent or a tool to any of them.
 */
class SecurityPolicyRepository(
    private val settingsDao: SecuritySettingsDao,
    private val projectDao: ProjectSecuritySettingsDao,
    private val grantDao: PermissionGrantDao,
    private val dispatchers: DispatcherProvider
) : SecurityPolicyProvider {

    override suspend fun policy(projectId: String?): SecurityPolicy = withContext(dispatchers.io) {
        val row = settingsDao.get() ?: return@withContext SecurityPolicy.DEFAULT
        SecurityPolicy.decode(row.policyJson, SecurityPolicy.DEFAULT)
    }

    fun observePolicy(): Flow<SecurityPolicy> = settingsDao.observe().map { row ->
        SecurityPolicy.decode(row?.policyJson, SecurityPolicy.DEFAULT)
    }

    suspend fun setPolicy(policy: SecurityPolicy, now: Long = System.currentTimeMillis()) =
        withContext(dispatchers.io) {
            settingsDao.upsert(SecuritySettingsEntity(policyJson = SecurityPolicy.encode(policy), updatedAt = now))
        }

    suspend fun projectSettings(projectId: String): ProjectSecuritySettings = withContext(dispatchers.io) {
        projectDao.get(projectId)?.toDomain() ?: ProjectSecuritySettings.defaults(projectId)
    }

    fun observeProjectSettings(): Flow<List<ProjectSecuritySettings>> =
        projectDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun setProjectSettings(settings: ProjectSecuritySettings, now: Long = System.currentTimeMillis()) =
        withContext(dispatchers.io) {
            projectDao.upsert(
                ProjectSecuritySettingsEntity(
                    projectId = settings.projectId,
                    allowFileModification = settings.allowFileModification,
                    allowTerminal = settings.allowTerminal,
                    allowNetwork = settings.allowNetwork,
                    allowPackageInstallation = settings.allowPackageInstallation,
                    allowSensitiveFileAccess = settings.allowSensitiveFileAccess,
                    updatedAt = now
                )
            )
        }

    /** Persists a scoped grant. Only SESSION/PROJECT grants are stored; task grants live in Phase 6. */
    suspend fun grant(
        scope: PermissionScope,
        toolName: String,
        projectId: String? = null,
        taskId: String? = null,
        sessionId: String? = null,
        expiresAt: Long? = null,
        now: Long = System.currentTimeMillis()
    ) = withContext(dispatchers.io) {
        grantDao.upsert(
            PermissionGrantEntity(
                id = UUID.randomUUID().toString(),
                scope = scope.name,
                projectId = projectId,
                taskId = taskId,
                sessionId = sessionId,
                toolName = toolName,
                grantedAt = now,
                expiresAt = expiresAt
            )
        )
    }

    suspend fun persistedGrants(): List<PermissionGrantEntity> = withContext(dispatchers.io) { grantDao.all() }

    /** §60: a new app process means a new session, so no session grant may outlive it. */
    suspend fun revokeAllSessions(): Int =
        withContext(dispatchers.io) { grantDao.deleteForScope(PermissionScope.SESSION.name) }

    /** §59: project grants are explicit user configuration and do survive a restart. */
    suspend fun restoreProjectGrants(now: Long = System.currentTimeMillis()): List<PermissionGrantEntity> =
        withContext(dispatchers.io) { grantDao.activeScopedGrants(now) }

    suspend fun revokeTask(taskId: String): Int = withContext(dispatchers.io) { grantDao.deleteForTask(taskId) }

    suspend fun revokeSession(sessionId: String): Int =
        withContext(dispatchers.io) { grantDao.deleteForSession(sessionId) }

    suspend fun revokeProject(projectId: String): Int =
        withContext(dispatchers.io) { grantDao.deleteForProject(projectId) }

    suspend fun revokeAll(): Int = withContext(dispatchers.io) { grantDao.deleteAll() }

    suspend fun pruneExpiredGrants(now: Long = System.currentTimeMillis()): Int =
        withContext(dispatchers.io) { grantDao.deleteExpired(now) }

    private fun ProjectSecuritySettingsEntity.toDomain() = ProjectSecuritySettings(
        projectId = projectId,
        allowFileModification = allowFileModification,
        allowTerminal = allowTerminal,
        allowNetwork = allowNetwork,
        allowPackageInstallation = allowPackageInstallation,
        allowSensitiveFileAccess = allowSensitiveFileAccess
    )
}

/** Everything the permissions UI needs to render, in one immutable snapshot. */
data class SecurityOverview(
    val policy: SecurityPolicy,
    val projectSettings: ProjectSecuritySettings?,
    val projectId: String?,
    val taskId: String?,
    val sessionId: String?,
    val taskGrantedTools: Set<String>,
    val sessionGrantedTools: Set<String>,
    val projectGrantedTools: Set<String>,
    val persistedGrantCount: Int,
    val auditEventCount: Int,
    val lastDiagnosticsAt: Long? = null
)
