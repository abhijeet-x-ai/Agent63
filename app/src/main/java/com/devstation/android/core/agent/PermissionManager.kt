package com.devstation.android.core.agent

import java.util.concurrent.ConcurrentHashMap

/** Result of a permission check. Only [Allowed] may proceed to execution. */
sealed class PermissionOutcome {
    object Allowed : PermissionOutcome()
    data class Denied(val reason: String) : PermissionOutcome()
}

/**
 * The single place that decides whether a tool call may run.
 *
 * Responsibilities (Phase 6 §26):
 * - resolve the required permission from the tool's declared policy + command classification,
 * - request real user approval when required (via [ApprovalBroker], which suspends),
 * - remember `Allow for this task` grants for the current task only,
 * - refuse everything when the global agent-tools switch is off,
 * - drop all grants when a task ends or the app restarts.
 *
 * Phase 6 never creates permanent, unrestricted agent permissions.
 */
class PermissionManager(
    private val broker: ApprovalBroker,
    /**
     * Optional sink used to persist a task grant so it survives process death mid-task.
     * Rows are always removed when the task ends (see [revokeTaskPermissions]).
     */
    private val grantSink: suspend (taskId: String, toolName: String) -> Unit = { _, _ -> },
    /**
     * Phase 7 §60: sink for SESSION/PROJECT scoped grants, persisted with an explicit scope id so
     * they can never be mistaken for task grants.
     */
    private val scopedGrantSink: suspend (scope: PermissionScope, scopeId: String, toolName: String) -> Unit =
        { _, _, _ -> }
) {

    private val taskGrants = ConcurrentHashMap<String, MutableSet<String>>()

    /** Grants that expire when the DevStation session ends (§60). Keyed by sessionId. */
    private val sessionGrants = ConcurrentHashMap<String, MutableSet<String>>()

    /** Grants the user configured for one project (§59). Keyed by projectId. */
    private val projectGrants = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * @param agentToolsEnabled global safety switch (Settings → Disable Agent Tools).
     * @param classification for terminal commands; never lets a destructive command inherit a
     *        weaker permission than the command's own category deserves.
     */
    suspend fun authorize(
        tool: Tool,
        context: ToolContext,
        request: ApprovalRequest,
        agentToolsEnabled: Boolean,
        classification: CommandClassification? = null
    ): PermissionOutcome {
        if (!agentToolsEnabled) {
            return PermissionOutcome.Denied(AGENT_DISABLED_MESSAGE)
        }

        val declared = tool.definition.permission
        if (declared == ToolPermission.DENY) {
            return PermissionOutcome.Denied("Tool '${tool.definition.name}' is disabled by policy.")
        }

        val required = when {
            // Classification-driven tools (terminal) take the command's own requirement, and
            // fail safe when the runtime could not classify the command.
            tool.definition.classificationDriven ->
                classification?.defaultPermission() ?: ToolPermission.ALWAYS_ASK
            classification != null -> strongerOf(declared, classification.defaultPermission())
            else -> declared
        }

        if (required == ToolPermission.DENY) {
            return PermissionOutcome.Denied("This action is not permitted.")
        }
        if (required == ToolPermission.ALLOW) {
            return PermissionOutcome.Allowed
        }

        // A task grant satisfies ASK but never ALWAYS_ASK.
        if (required == ToolPermission.ASK && hasTaskGrant(context.taskId, tool.definition.name)) {
            return PermissionOutcome.Allowed
        }

        val decision = broker.request(request)
        return applyDecision(
            decision = decision,
            required = required,
            toolName = tool.definition.name,
            taskId = context.taskId,
            sessionId = request.sessionId?.takeIf { it.isNotBlank() },
            projectId = context.projectId
        )
    }

    /**
     * How an approval was satisfied.
     *
     * [decidedNow] is true when the user made a decision about *this* invocation (Allow once, for
     * this task, for this session). §49 needs that distinction: an invocation that was allowed by a
     * decision made a moment ago is already authorized, while one allowed by an earlier grant must
     * still be valid immediately before the tool runs — a revocation in between must cancel it.
     */
    data class ApprovalResolution(val outcome: PermissionOutcome, val decidedNow: Boolean)

    /**
     * Phase 7 §2/§49: the security engine has already decided that approval is required; this
     * performs the approval interaction and records the grant according to its scope.
     */
    suspend fun resolveApproval(
        request: ApprovalRequest,
        requiredPermission: ToolPermission,
        toolName: String,
        taskId: String?,
        sessionId: String?,
        projectId: String?
    ): ApprovalResolution {
        if (requiredPermission == ToolPermission.ALLOW) {
            return ApprovalResolution(PermissionOutcome.Allowed, decidedNow = false)
        }
        if (requiredPermission == ToolPermission.DENY) {
            return ApprovalResolution(
                PermissionOutcome.Denied("This action is not permitted by the security policy."),
                decidedNow = false
            )
        }
        if (requiredPermission == ToolPermission.ASK &&
            hasGrant(PermissionScope.PER_TASK, toolName, taskId, sessionId, projectId)
        ) {
            // Allowed by a grant from earlier in the task — the engine still re-checks it before the
            // tool runs, so a revocation in the meantime takes effect.
            return ApprovalResolution(PermissionOutcome.Allowed, decidedNow = false)
        }
        val decision = broker.request(request)
        return ApprovalResolution(
            outcome = applyDecision(
                decision = decision,
                required = requiredPermission,
                toolName = toolName,
                taskId = taskId,
                sessionId = sessionId,
                projectId = projectId
            ),
            decidedNow = decision.outcome != ApprovalOutcome.DENY
        )
    }

    private suspend fun applyDecision(
        decision: ApprovalDecision,
        required: ToolPermission,
        toolName: String,
        taskId: String?,
        sessionId: String?,
        projectId: String?
    ): PermissionOutcome = when (decision.outcome) {
        ApprovalOutcome.ALLOW_ONCE -> PermissionOutcome.Allowed
        ApprovalOutcome.ALLOW_FOR_TASK -> {
            if (required != ToolPermission.ALWAYS_ASK && taskId != null) grantForTask(taskId, toolName)
            PermissionOutcome.Allowed
        }
        ApprovalOutcome.ALLOW_FOR_SESSION -> {
            if (required != ToolPermission.ALWAYS_ASK && sessionId != null) grantForSession(sessionId, toolName)
            PermissionOutcome.Allowed
        }
        ApprovalOutcome.DENY -> PermissionOutcome.Denied("Action denied by the user.")
    }

    /** Resolve the permission this invocation requires, without asking anyone. */
    fun requiredPermission(
        tool: Tool,
        classification: CommandClassification?,
        agentToolsEnabled: Boolean
    ): ToolPermission {
        if (!agentToolsEnabled) return ToolPermission.DENY
        val declared = tool.definition.permission
        if (declared == ToolPermission.DENY) return ToolPermission.DENY
        return when {
            tool.definition.classificationDriven ->
                classification?.defaultPermission() ?: ToolPermission.ALWAYS_ASK
            classification != null -> strongerOf(declared, classification.defaultPermission())
            else -> declared
        }
    }

    /** True when this invocation will block on a user decision. */
    fun willRequireApproval(
        tool: Tool,
        context: ToolContext,
        classification: CommandClassification?,
        agentToolsEnabled: Boolean
    ): Boolean {
        val required = requiredPermission(tool, classification, agentToolsEnabled)
        return when (required) {
            ToolPermission.ALLOW, ToolPermission.DENY -> false
            ToolPermission.ALWAYS_ASK -> true
            ToolPermission.ASK -> !hasTaskGrant(context.taskId, tool.definition.name)
        }
    }

    fun hasTaskGrant(taskId: String?, toolName: String): Boolean =
        taskId != null && taskGrants[taskId]?.contains(toolName) == true

    fun hasSessionGrant(sessionId: String?, toolName: String): Boolean =
        sessionId != null && sessionGrants[sessionId]?.contains(toolName) == true

    fun hasProjectGrant(projectId: String?, toolName: String): Boolean =
        projectId != null && projectGrants[projectId]?.contains(toolName) == true

    /** §4/§58–§60: one lookup for every scope, always validated against the current identity. */
    fun hasGrant(
        scope: PermissionScope,
        toolName: String,
        taskId: String?,
        sessionId: String?,
        projectId: String?
    ): Boolean = when (scope) {
        PermissionScope.PER_REQUEST -> false
        PermissionScope.PER_TASK -> hasTaskGrant(taskId, toolName)
        PermissionScope.SESSION -> hasSessionGrant(sessionId, toolName)
        PermissionScope.PROJECT, PermissionScope.GLOBAL -> hasProjectGrant(projectId, toolName)
    }

    fun taskGrantedTools(taskId: String): Set<String> = taskGrants[taskId]?.toSet() ?: emptySet()

    fun sessionGrantedTools(sessionId: String?): Set<String> =
        sessionId?.let { sessionGrants[it]?.toSet() } ?: emptySet()

    fun projectGrantedTools(projectId: String?): Set<String> =
        projectId?.let { projectGrants[it]?.toSet() } ?: emptySet()

    suspend fun grantForTask(taskId: String, toolName: String) {
        taskGrants.computeIfAbsent(taskId) { ConcurrentHashMap.newKeySet() }.add(toolName)
        grantSink(taskId, toolName)
    }

    suspend fun grantForSession(sessionId: String, toolName: String) {
        sessionGrants.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }.add(toolName)
        scopedGrantSink(PermissionScope.SESSION, sessionId, toolName)
    }

    suspend fun grantForProject(projectId: String, toolName: String) {
        projectGrants.computeIfAbsent(projectId) { ConcurrentHashMap.newKeySet() }.add(toolName)
        scopedGrantSink(PermissionScope.PROJECT, projectId, toolName)
    }

    /** Called when a task completes, fails, or is cancelled. */
    suspend fun revokeTaskPermissions(taskId: String) {
        taskGrants.remove(taskId)
    }

    /** §32/§60: the session ended (or the user revoked it) — grants stop applying immediately. */
    suspend fun revokeSessionPermissions(sessionId: String) {
        sessionGrants.remove(sessionId)
    }

    suspend fun revokeProjectPermissions(projectId: String) {
        projectGrants.remove(projectId)
    }

    /** Called on app startup and by the emergency stop. */
    suspend fun clearAllPermissions() {
        taskGrants.clear()
        sessionGrants.clear()
        projectGrants.clear()
    }

    /** Restores persisted session/project grants at startup. Never widens them. */
    fun hydrate(grants: List<PersistedGrant>) {
        grants.forEach { grant ->
            when (grant.scope) {
                PermissionScope.SESSION ->
                    sessionGrants.computeIfAbsent(grant.scopeId) { ConcurrentHashMap.newKeySet() }.add(grant.toolName)
                PermissionScope.PROJECT, PermissionScope.GLOBAL ->
                    projectGrants.computeIfAbsent(grant.scopeId) { ConcurrentHashMap.newKeySet() }.add(grant.toolName)
                else -> Unit
            }
        }
    }

    /** A grant restored from storage. */
    data class PersistedGrant(
        val scope: PermissionScope,
        val scopeId: String,
        val toolName: String
    )

    private fun strongerOf(a: ToolPermission, b: ToolPermission): ToolPermission =
        if (rank(a) >= rank(b)) a else b

    private fun rank(permission: ToolPermission): Int = when (permission) {
        ToolPermission.ALLOW -> 0
        ToolPermission.ASK -> 1
        ToolPermission.ALWAYS_ASK -> 2
        ToolPermission.DENY -> 3
    }

    companion object {
        const val AGENT_DISABLED_MESSAGE =
            "Agent tools are disabled. Enable them in Settings to let the agent act on this project."
    }
}
