package com.devstation.android.core.security.policy

import com.devstation.android.core.agent.PermissionManager
import com.devstation.android.core.agent.PermissionScope
import com.devstation.android.core.agent.ToolRiskLevel
import com.devstation.android.core.database.PermissionGrantEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File as JavaFile

/**
 * §3/§32/§38/§39: the user-facing side of the security system.
 *
 * This is the only surface through which policy, project settings and grants can be changed. It is
 * reachable from ViewModels, never from [com.devstation.android.core.agent.ToolExecutor], a tool, or
 * the agent loop — so the model has no path to grant itself anything (§56/§57).
 */
class SecurityManager(
    private val repository: SecurityPolicyRepository,
    private val permissions: PermissionManager,
    private val audit: SecurityAuditLogger,
    private val diagnostics: SecurityDiagnostics,
    /** Identity of this DevStation session; session-scoped grants are bound to it (§60). */
    val sessionId: String
) {

    // ---- observation ----

    fun observePolicy(): Flow<SecurityPolicy> = repository.observePolicy()

    fun observeProjectSettings(): Flow<List<ProjectSecuritySettings>> = repository.observeProjectSettings()

    fun observeAudit(limit: Int = 200): Flow<List<SecurityAuditEvent>> = audit.observe(limit)

    /** §42: the mode currently in force, for the Agent UI badge. */
    fun observeMode(): Flow<AgentSecurityMode> = observePolicy().map { it.mode }

    // ---- policy ----

    /** §40/§43: switching mode replaces the whole category policy; impact is returned for the UI. */
    suspend fun setMode(mode: AgentSecurityMode): List<String> {
        val policy = SecurityPolicy.forMode(mode)
        repository.setPolicy(policy)
        audit.log(
            type = SecurityEventType.SECURITY_POLICY_CHANGED,
            decision = AuditDecision.RECORDED,
            riskLevel = ToolRiskLevel.LOW,
            summary = "Security mode changed to ${mode.name}."
        )
        return policy.impactSummary()
    }

    /** §38: per-category control. Credentials can never be loosened (§56). */
    suspend fun setCategory(category: PermissionCategory, policy: CategoryPolicy): SecurityPolicy {
        val current = repository.policy(null)
        val updated = current.withCategory(category, policy)
        repository.setPolicy(updated)
        audit.log(
            type = SecurityEventType.SECURITY_POLICY_CHANGED,
            decision = AuditDecision.RECORDED,
            riskLevel = ToolRiskLevel.LOW,
            summary = "Policy for ${category.name} set to ${policy.name}."
        )
        return updated
    }

    /** §36: audit retention (7/30/90 days). Applies immediately. */
    suspend fun setRetention(days: Int): Int {
        val current = repository.policy(null)
        val safeDays = days.coerceIn(1, 365)
        repository.setPolicy(current.copy(auditRetentionDays = safeDays))
        return audit.prune(safeDays)
    }

    // ---- project settings ----

    suspend fun projectSettings(projectId: String): ProjectSecuritySettings =
        repository.projectSettings(projectId)

    suspend fun setProjectSettings(settings: ProjectSecuritySettings) {
        repository.setProjectSettings(settings)
        audit.log(
            type = SecurityEventType.SECURITY_POLICY_CHANGED,
            decision = AuditDecision.RECORDED,
            riskLevel = ToolRiskLevel.LOW,
            summary = "Project security settings updated for ${settings.projectId}."
        )
    }

    // ---- overview / revocation ----

    suspend fun overview(
        projectId: String?,
        taskId: String?
    ): SecurityOverview {
        val policy = repository.policy(projectId)
        val settings = projectId?.let { runCatching { repository.projectSettings(it) }.getOrNull() }
        val persisted = runCatching { repository.persistedGrants() }.getOrDefault(emptyList())
        return SecurityOverview(
            policy = policy,
            projectSettings = settings,
            projectId = projectId,
            taskId = taskId,
            sessionId = sessionId,
            taskGrantedTools = taskId?.let { permissions.taskGrantedTools(it) } ?: emptySet(),
            sessionGrantedTools = permissions.sessionGrantedTools(sessionId),
            projectGrantedTools = projectId?.let { permissions.projectGrantedTools(it) } ?: emptySet(),
            persistedGrantCount = persisted.size,
            auditEventCount = runCatching { audit.recent(MAX_OVERVIEW_EVENTS) }.getOrDefault(emptyList()).size
        )
    }

    /** §32/§53: task permissions expire the moment the task ends or the user revokes them. */
    suspend fun revokeTask(taskId: String) {
        permissions.revokeTaskPermissions(taskId)
        repository.revokeTask(taskId)
        audit.log(
            type = SecurityEventType.PERMISSION_REVOKED,
            decision = AuditDecision.RECORDED,
            riskLevel = ToolRiskLevel.LOW,
            summary = "Task permissions revoked for $taskId."
        )
    }

    /** §32/§60: session grants stop applying immediately and are removed from storage. */
    suspend fun revokeSession() {
        permissions.revokeSessionPermissions(sessionId)
        repository.revokeSession(sessionId)
        audit.log(
            type = SecurityEventType.PERMISSION_REVOKED,
            decision = AuditDecision.RECORDED,
            riskLevel = ToolRiskLevel.LOW,
            summary = "Session permissions revoked."
        )
    }

    suspend fun revokeProject(projectId: String) {
        permissions.revokeProjectPermissions(projectId)
        repository.revokeProject(projectId)
        audit.log(
            type = SecurityEventType.PERMISSION_REVOKED,
            decision = AuditDecision.RECORDED,
            riskLevel = ToolRiskLevel.LOW,
            summary = "Project permissions reset for $projectId."
        )
    }

    /** §32: reset everything the agent was ever allowed. */
    suspend fun resetAllPermissions() {
        permissions.clearAllPermissions()
        repository.revokeAll()
        audit.log(
            type = SecurityEventType.PERMISSION_REVOKED,
            decision = AuditDecision.RECORDED,
            riskLevel = ToolRiskLevel.LOW,
            summary = "All agent permissions reset."
        )
    }

    /** §36: explicit, user-confirmed history clear. */
    suspend fun clearHistory(): Int = audit.clear()

    suspend fun pruneHistory(): Int = audit.prune(repository.policy(null).auditRetentionDays)

    // ---- diagnostics ----

    suspend fun runDiagnostics(projectRoot: JavaFile?, taskId: String?): List<DiagnosticCheck> =
        diagnostics.run(projectRoot, taskId)

    // ---- startup ----

    /**
     * §31/§60: a new app process is a new session, so session-scoped grants from a previous run are
     * removed (never rehydrated). Project grants persist as explicit user configuration, and expired
     * grants are dropped.
     */
    suspend fun initializeSessionStore() {
        runCatching { repository.revokeAllSessions() }
        runCatching { repository.pruneExpiredGrants() }
        runCatching { repository.restoreProjectGrants() }.getOrDefault(emptyList()).let { grants ->
            permissions.hydrate(grants.map { it.toPersistedGrant() })
        }
        runCatching { pruneHistory() }
    }

    private fun PermissionGrantEntity.toPersistedGrant(): PermissionManager.PersistedGrant {
        val scope = runCatching { PermissionScope.valueOf(scope) }.getOrDefault(PermissionScope.PROJECT)
        val scopeId = when (scope) {
            PermissionScope.SESSION -> sessionId
            PermissionScope.PROJECT, PermissionScope.GLOBAL -> projectId
            else -> null
        } ?: return PermissionManager.PersistedGrant(PermissionScope.PER_REQUEST, "", toolName)
        return PermissionManager.PersistedGrant(scope, scopeId, toolName)
    }

    private companion object {
        const val MAX_OVERVIEW_EVENTS = 500
    }
}
