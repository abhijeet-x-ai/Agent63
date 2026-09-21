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
    private val grantSink: suspend (taskId: String, toolName: String) -> Unit = { _, _ -> }
) {

    private val taskGrants = ConcurrentHashMap<String, MutableSet<String>>()

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
        return when (decision.outcome) {
            ApprovalOutcome.ALLOW_ONCE -> PermissionOutcome.Allowed
            ApprovalOutcome.ALLOW_FOR_TASK -> {
                if (required != ToolPermission.ALWAYS_ASK) {
                    grantForTask(context.taskId, tool.definition.name)
                }
                PermissionOutcome.Allowed
            }
            ApprovalOutcome.DENY -> PermissionOutcome.Denied("Action denied by the user.")
        }
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

    fun hasTaskGrant(taskId: String, toolName: String): Boolean =
        taskGrants[taskId]?.contains(toolName) == true

    fun taskGrantedTools(taskId: String): Set<String> = taskGrants[taskId]?.toSet() ?: emptySet()

    suspend fun grantForTask(taskId: String, toolName: String) {
        taskGrants.computeIfAbsent(taskId) { ConcurrentHashMap.newKeySet() }.add(toolName)
        grantSink(taskId, toolName)
    }

    /** Called when a task completes, fails, or is cancelled. */
    suspend fun revokeTaskPermissions(taskId: String) {
        taskGrants.remove(taskId)
    }

    /** Called on app startup and by the emergency stop. */
    suspend fun clearAllPermissions() {
        taskGrants.clear()
    }

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
