package com.devstation.android.core.security.policy

import com.devstation.android.core.agent.CommandCategory
import com.devstation.android.core.agent.CommandClassification
import com.devstation.android.core.agent.PermissionScope
import com.devstation.android.core.agent.ToolContext
import com.devstation.android.core.agent.ToolDefinition
import com.devstation.android.core.agent.ToolPermission
import com.devstation.android.core.agent.ToolRiskLevel
import com.devstation.android.core.ai.AIToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Looks up an already-granted permission. Injected so the engine stays free of storage details. */
fun interface SecurityGrantLookup {
    fun hasGrant(
        scope: PermissionScope,
        toolName: String,
        taskId: String?,
        sessionId: String?,
        projectId: String?
    ): Boolean

    companion object {
        /** Used when no grant store is wired (unit tests, dry runs). */
        val None = SecurityGrantLookup { _, _, _, _, _ -> false }
    }
}

/**
 * Phase 7 §2/§3: THE security decision point.
 *
 * Every agent tool call is described as a [SecurityRequest] and must obtain a [SecurityDecision]
 * here before it can run. The engine is pure policy: it never executes anything, never mutates
 * state, and is never influenced by model output beyond the request's declared fields.
 *
 * ```
 * evaluate()   policy only            → ALLOW / ASK / ELEVATED / DENY
 * authorize()  policy + existing grants → may turn ASK into ALLOW
 * revalidate() policy + sandbox again  → must be checked immediately before execution (§49/§50)
 * ```
 */
class SecurityPolicyEngine(
    private val policyProvider: SecurityPolicyProvider = StaticSecurityPolicyProvider(),
    private val grants: SecurityGrantLookup = SecurityGrantLookup.None,
    private val audit: SecurityAuditLogger = SecurityAuditLogger.NoOp,
    private val sandbox: FilesystemSandbox = FilesystemSandbox(),
    private val terminalPolicy: TerminalSecurityPolicy = TerminalSecurityPolicy(),
    private val networkPolicy: NetworkSecurityPolicy = NetworkSecurityPolicy(),
    private val projectSettings: suspend (String?) -> ProjectSecuritySettings? = { null },
    /** True when agent commands run inside the Linux guest (affects path argument checks). */
    private val linuxGuestAvailable: () -> Boolean = { true }
) {

    // ---- decisions ----

    /** Full authorization: policy + grants. */
    suspend fun authorize(request: SecurityRequest, agentToolsEnabled: Boolean): SecurityDecision {
        val decision = evaluate(request, agentToolsEnabled)
        if (decision.type != SecurityOutcomeType.ASK) return decision
        val satisfied = GRANT_SCOPES.any { scope ->
            grants.hasGrant(scope, request.toolName, request.taskId, request.sessionId, request.projectId)
        }
        if (!satisfied) return decision
        return decision.copy(
            type = SecurityOutcomeType.ALLOW,
            reason = "Already granted for this ${if (request.taskId != null) "task" else "session"}.",
            explanation = null
        )
    }

    /** Policy evaluation only — no grant lookup, no state. */
    suspend fun evaluate(request: SecurityRequest, agentToolsEnabled: Boolean): SecurityDecision {
        if (!agentToolsEnabled) {
            return deny(
                request,
                AGENT_DISABLED_MESSAGE,
                SecurityEventType.TOOL_BLOCKED,
                ResourceType.UNKNOWN,
                null
            )
        }

        val policy = policyProvider.policy(request.projectId)

        // §5/§42: every agent action belongs to one project and one task.
        if (request.projectId.isNullOrBlank() || request.projectRoot == null) {
            return deny(
                request,
                "Blocked because no project is selected. The agent can only act inside a project.",
                SecurityEventType.TOOL_BLOCKED,
                request.resourceType,
                null,
                policy.mode
            )
        }
        if (request.taskId.isNullOrBlank()) {
            return deny(
                request,
                "Blocked because the request has no agent task identity.",
                SecurityEventType.TOOL_BLOCKED,
                request.resourceType,
                null,
                policy.mode
            )
        }

        val settings = runCatching { projectSettings(request.projectId) }.getOrNull()
        val projectCategory = request.category()
        if (settings != null && settings.denies(projectCategory)) {
            return deny(
                request,
                "Blocked because ${categoryLabel(projectCategory)} is disabled for this project in its security settings.",
                SecurityEventType.TOOL_BLOCKED,
                request.resourceType,
                null,
                policy.mode,
                projectCategory
            )
        }

        // §12/§62: credential material is never reachable by a tool, in any mode.
        if (request.resourceType == ResourceType.CREDENTIAL) {
            return deny(
                request,
                "Blocked because agent access to credential storage is never permitted.",
                SecurityEventType.SECRET_ACCESS_BLOCKED,
                ResourceType.CREDENTIAL,
                null,
                policy.mode,
                PermissionCategory.CREDENTIALS
            )
        }

        var resourceType = request.resourceType
        var sandboxToken: SandboxToken? = null
        var sensitive = request.sensitiveDeclared
        var terminalAssessment: TerminalSecurityPolicy.Assessment? = null
        var networkIntent = request.networkImpact
        var destination: String? = null

        // §6–§10: filesystem sandbox for anything that names a file.
        val resourceValue = resolveResourceValue(request)
        if (needsSandbox(request)) {
            when (val resolution = sandbox.resolve(request.projectRoot, resourceValue, request.sandboxOperation())) {
                is FilesystemSandbox.Resolution.Rejected -> {
                    val credential = resolution.reason.contains("credential", ignoreCase = true)
                    return deny(
                        request,
                        resolution.reason,
                        if (credential) SecurityEventType.SECRET_ACCESS_BLOCKED else SecurityEventType.PATH_BLOCKED,
                        request.resourceType,
                        null,
                        policy.mode,
                        request.category()
                    )
                }
                is FilesystemSandbox.Resolution.Allowed -> {
                    sandboxToken = resolution.token
                    if (resolution.sensitive) {
                        sensitive = true
                        resourceType = ResourceType.SENSITIVE_FILE
                    }
                }
            }
        }

        // §15–§19: terminal policy.
        if (request.resourceType == ResourceType.TERMINAL || request.resourceType == ResourceType.PROCESS) {
            val assessment = terminalPolicy.assess(
                command = resourceValue.orEmpty(),
                root = request.projectRoot,
                guest = linuxGuestAvailable()
            )
            terminalAssessment = assessment
            if (assessment.blockedReason != null) {
                return deny(
                    request,
                    assessment.blockedReason,
                    if (assessment.sensitiveArgument) SecurityEventType.SECRET_ACCESS_BLOCKED else SecurityEventType.PATH_BLOCKED,
                    request.resourceType,
                    assessment,
                    policy.mode,
                    PermissionCategory.TERMINAL
                )
            }
            if (assessment.sensitiveArgument) {
                sensitive = true
                resourceType = ResourceType.SENSITIVE_FILE
            }
            if (assessment.networkIntent != NetworkIntent.NONE) {
                networkIntent = assessment.networkIntent
                destination = assessment.destination?.display
            }
            val networkPolicyValue = if (assessment.networkIntent != NetworkIntent.NONE) {
                policy.policyFor(PermissionCategory.NETWORK)
            } else {
                null
            }
            if (networkPolicyValue == CategoryPolicy.DENY) {
                return deny(
                    request,
                    "Blocked because network access is disabled by the current security policy.",
                    SecurityEventType.NETWORK_BLOCKED,
                    ResourceType.NETWORK,
                    assessment,
                    policy.mode,
                    PermissionCategory.NETWORK
                )
            }
        }

        val category = categoryOf(resourceType, terminalAssessment)
        val required = requiredPermission(
            request = request,
            policy = policy,
            category = category,
            sensitive = sensitive,
            terminalAssessment = terminalAssessment,
            networkIntent = networkIntent
        )

        val risk = maxOf(
            request.riskLevel,
            terminalAssessment?.risk ?: ToolRiskLevel.LOW,
            if (sensitive) ToolRiskLevel.MEDIUM else ToolRiskLevel.LOW
        )

        val base = SecurityDecision(
            type = SecurityOutcomeType.ALLOW,
            reason = "Allowed by policy.",
            resourceType = resourceType,
            riskLevel = risk,
            requiredPermission = required,
            category = category,
            resolvedResource = sandboxToken?.canonicalPath ?: resourceValue?.take(MAX_RESOURCE_CHARS),
            sandboxToken = sandboxToken,
            networkIntent = networkIntent,
            destination = destination,
            sensitive = sensitive
        )

        return when (required) {
            ToolPermission.DENY -> deny(
                request,
                "Blocked by the current security policy (${categoryLabel(category)}).",
                if (category == PermissionCategory.CREDENTIALS) SecurityEventType.SECRET_ACCESS_BLOCKED
                else SecurityEventType.TOOL_BLOCKED,
                resourceType,
                terminalAssessment,
                policy.mode,
                category
            )
            ToolPermission.ALLOW -> base.copy(
                reason = "Allowed: ${explainAllow(category, policy)}",
                explanation = explainAllow(category, policy)
            )
            ToolPermission.ASK -> base.copy(
                type = SecurityOutcomeType.ASK,
                reason = "Approval required: ${explainAsk(request, terminalAssessment, category, sensitive)}",
                explanation = explainAsk(request, terminalAssessment, category, sensitive)
            )
            ToolPermission.ALWAYS_ASK -> base.copy(
                type = SecurityOutcomeType.ELEVATED,
                reason = "Explicit approval is required every time for this action.",
                explanation = explainElevated(request, terminalAssessment, sensitive)
            )
        }
    }

    /**
     * §49/§50: called immediately before the tool body runs.
     *
     * Two things are re-checked, because a decision made a moment ago may already be stale:
     *
     * 1. the authorization itself — policy *and* grants. A permission revoked, a category tightened
     *    or the global switch turned off while the agent was working must take effect before the
     *    action, not after it. [approvedForThisRequest] is the one exemption: a single-use
     *    "Allow once" is a decision about this exact call, so it does not need a stored grant — but
     *    a hard DENY still cancels it.
     * 2. the sandboxed resource — a file that changed underneath the agent must not be overwritten
     *    or deleted.
     */
    suspend fun revalidate(
        request: SecurityRequest,
        previous: SecurityDecision,
        agentToolsEnabled: Boolean,
        approvedForThisRequest: Boolean = false
    ): SecurityDecision {
        val fresh = evaluate(request, agentToolsEnabled)
        if (fresh.denied) {
            audit.log(
                type = SecurityEventType.PERMISSION_DENIED,
                decision = AuditDecision.BLOCKED,
                request = request,
                riskLevel = fresh.riskLevel,
                summary = "Rechecked before execution and blocked: ${fresh.reason}"
            )
            return fresh.copy(reason = "Blocked before execution: ${fresh.reason}")
        }

        if (!approvedForThisRequest) {
            val authorized = authorize(request, agentToolsEnabled)
            if (!authorized.allowed) {
                val reason = "Blocked before execution because the permission is no longer valid " +
                    "(${authorized.reason})"
                audit.log(
                    type = SecurityEventType.PERMISSION_DENIED,
                    decision = AuditDecision.BLOCKED,
                    request = request,
                    riskLevel = fresh.riskLevel,
                    summary = reason
                )
                return fresh.copy(type = SecurityOutcomeType.DENY, reason = reason)
            }
        }

        val token = previous.sandboxToken ?: fresh.sandboxToken
        if (token != null) {
            val failure = sandbox.revalidate(token)
            if (failure != null) {
                audit.log(
                    type = SecurityEventType.PATH_BLOCKED,
                    decision = AuditDecision.BLOCKED,
                    request = request,
                    riskLevel = ToolRiskLevel.HIGH,
                    summary = failure
                )
                return previous.copy(
                    type = SecurityOutcomeType.DENY,
                    reason = failure,
                    explanation = "The resource changed between the security check and the operation."
                )
            }
        }
        return previous.copy(sandboxToken = token)
    }

    // ---- internals ----

    private fun categoryOf(
        resourceType: ResourceType,
        assessment: TerminalSecurityPolicy.Assessment?
    ): PermissionCategory = when {
        resourceType == ResourceType.CREDENTIAL -> PermissionCategory.CREDENTIALS
        resourceType == ResourceType.SENSITIVE_FILE -> PermissionCategory.SENSITIVE_FILES
        resourceType == ResourceType.PACKAGE_MANAGER -> PermissionCategory.PACKAGES
        assessment?.category == CommandCategory.INSTALL_PACKAGE -> PermissionCategory.PACKAGES
        assessment?.category == CommandCategory.PACKAGE_REMOVE -> PermissionCategory.PACKAGES
        resourceType == ResourceType.NETWORK -> PermissionCategory.NETWORK
        // §20: a command that reaches the network is governed by the network policy, not by the
        // terminal policy — otherwise "network access" could never be restricted.
        assessment?.category == CommandCategory.NETWORK -> PermissionCategory.NETWORK
        assessment?.category == CommandCategory.LOCAL_NETWORK -> PermissionCategory.NETWORK
        resourceType == ResourceType.TERMINAL -> PermissionCategory.TERMINAL
        resourceType == ResourceType.PROCESS -> PermissionCategory.TERMINAL
        resourceType == ResourceType.LINUX_RUNTIME -> PermissionCategory.TERMINAL
        else -> PermissionCategory.FILES
    }

    private fun requiredPermission(
        request: SecurityRequest,
        policy: SecurityPolicy,
        category: PermissionCategory,
        sensitive: Boolean,
        terminalAssessment: TerminalSecurityPolicy.Assessment?,
        networkIntent: NetworkIntent
    ): ToolPermission {
        val declared = request.declaredPermission
        val classification: CommandClassification? = terminalAssessment?.classification ?: request.classification

        var base = when {
            request.classificationDriven -> classification?.defaultPermission() ?: ToolPermission.ALWAYS_ASK
            classification != null -> moreRestrictive(declared, classification.defaultPermission())
            else -> declared
        }

        if (networkIntent != NetworkIntent.NONE) {
            base = moreRestrictive(base, ToolPermission.ASK)
        }
        if (sensitive) {
            base = moreRestrictive(base, ToolPermission.ASK)
        }

        // §18/§12/§30: an unconditional-approval or denied tool, a destructive action and a
        // sensitive delete can never be auto-approved. A category policy is allowed to tighten them
        // further, but a loose category must never loosen them.
        val protected = request.destructive ||
            request.declaredPermission == ToolPermission.ALWAYS_ASK ||
            request.declaredPermission == ToolPermission.DENY ||
            (sensitive && request.action == SecurityAction.DELETE) ||
            category == PermissionCategory.CREDENTIALS

        val policyValue = policy.policyFor(category)
        val adjusted = when (policyValue) {
            CategoryPolicy.DEFAULT -> base
            CategoryPolicy.ALLOW -> if (protected) base else ToolPermission.ALLOW
            CategoryPolicy.ASK -> moreRestrictive(base, ToolPermission.ASK)
            CategoryPolicy.ALWAYS_ASK -> ToolPermission.ALWAYS_ASK
            CategoryPolicy.DENY -> ToolPermission.DENY
        }

        if (category == PermissionCategory.CREDENTIALS) return ToolPermission.DENY
        if (request.declaredPermission == ToolPermission.DENY) return ToolPermission.DENY
        if (request.destructive || (sensitive && request.action == SecurityAction.DELETE)) {
            return ToolPermission.ALWAYS_ASK
        }
        if (request.declaredPermission == ToolPermission.ALWAYS_ASK) return ToolPermission.ALWAYS_ASK
        return adjusted
    }

    private suspend fun deny(
        request: SecurityRequest,
        reason: String,
        eventType: SecurityEventType,
        resourceType: ResourceType,
        assessment: TerminalSecurityPolicy.Assessment?,
        mode: AgentSecurityMode? = null,
        category: PermissionCategory? = null
    ): SecurityDecision {
        audit.log(
            type = eventType,
            decision = AuditDecision.BLOCKED,
            request = request,
            riskLevel = assessment?.risk ?: request.riskLevel,
            summary = "$reason${request.resource?.let { " [$it]" } ?: ""}"
        )
        return SecurityDecision(
            type = SecurityOutcomeType.DENY,
            reason = reason,
            resourceType = resourceType,
            riskLevel = assessment?.risk ?: request.riskLevel,
            requiredPermission = ToolPermission.DENY,
            category = category,
            networkIntent = assessment?.networkIntent ?: NetworkIntent.NONE,
            destination = assessment?.destination?.display,
            explanation = mode?.let { "Security mode: ${it.name}" }
        )
    }

    private fun explainAllow(
        category: PermissionCategory,
        policy: SecurityPolicy
    ): String = when (category) {
        PermissionCategory.FILES ->
            "Read-only project access is allowed in ${policy.mode.name} mode."
        PermissionCategory.TERMINAL ->
            "This command is classified as read-only and allowed by ${policy.mode.name} mode."
        else -> "Allowed by the ${policy.mode.name} policy for ${categoryLabel(category)}."
    }

    private fun explainAsk(
        request: SecurityRequest,
        assessment: TerminalSecurityPolicy.Assessment?,
        category: PermissionCategory,
        sensitive: Boolean
    ): String = buildString {
        when {
            sensitive -> append("This touches a file that normally stores secrets. ")
            request.action == SecurityAction.EXECUTE || assessment != null ->
                append("Commands and project changes need your approval. ")
            else -> append("This changes files inside your project. ")
        }
        if (assessment?.networkIntent == NetworkIntent.INTERNET) {
            append("It can reach the network. ")
        } else if (assessment?.networkIntent == NetworkIntent.LOCAL_NETWORK) {
            append("It uses a local/private network address. ")
        }
        append("The agent is asking to ${request.action.name.lowercase()} ${categoryLabel(category)}.")
    }

    private fun explainElevated(
        request: SecurityRequest,
        assessment: TerminalSecurityPolicy.Assessment?,
        sensitive: Boolean
    ): String = when {
        sensitive && request.action == SecurityAction.DELETE ->
            "Deleting a file that may hold secrets always requires an explicit decision."
        request.destructive || assessment?.category == CommandCategory.DESTRUCTIVE ->
            "This action can destroy data, so it always requires an explicit decision."
        assessment?.category == CommandCategory.PACKAGE_REMOVE ->
            "Removing installed software always requires an explicit decision."
        assessment?.category == CommandCategory.UNKNOWN ->
            "The command could not be classified safely, so it always requires an explicit decision."
        else -> "This action always requires an explicit decision."
    }

    /**
     * Only resources that *are* filesystem paths go through the path sandbox. Terminal commands are
     * not paths: their arguments are validated by [TerminalSecurityPolicy], which knows how to tell
     * a path token from a flag or a package name.
     */
    private fun needsSandbox(request: SecurityRequest): Boolean =
        request.resourceType in SANDBOXED_RESOURCE_TYPES && resolveResourceValue(request) != null

    private fun resolveResourceValue(request: SecurityRequest): String? = request.resource?.trim()?.takeIf { it.isNotEmpty() }

    private fun moreRestrictive(a: ToolPermission, b: ToolPermission): ToolPermission =
        if (rank(a) >= rank(b)) a else b

    private fun rank(permission: ToolPermission): Int = when (permission) {
        ToolPermission.ALLOW -> 0
        ToolPermission.ASK -> 1
        ToolPermission.ALWAYS_ASK -> 2
        ToolPermission.DENY -> 3
    }

    private fun categoryLabel(category: PermissionCategory): String = when (category) {
        PermissionCategory.FILES -> "project files"
        PermissionCategory.TERMINAL -> "terminal commands"
        PermissionCategory.NETWORK -> "network access"
        PermissionCategory.PACKAGES -> "package management"
        PermissionCategory.SENSITIVE_FILES -> "sensitive files"
        PermissionCategory.CREDENTIALS -> "credential storage"
    }

    companion object {
        const val AGENT_DISABLED_MESSAGE =
            "Agent tools are disabled. Enable them in Settings to let the agent act on this project."
        private const val MAX_RESOURCE_CHARS = 300

        private val SANDBOXED_RESOURCE_TYPES = setOf(
            ResourceType.PROJECT_FILE,
            ResourceType.PROJECT_DIRECTORY,
            ResourceType.SENSITIVE_FILE,
            ResourceType.EDITOR
        )

        /** Scopes that can satisfy an ASK decision. ALWAYS_ASK is never satisfied by a grant. */
        private val GRANT_SCOPES = listOf(PermissionScope.PER_TASK, PermissionScope.SESSION, PermissionScope.PROJECT)
    }
}

private fun SecurityRequest.sandboxOperation(): SandboxOperation = when (action) {
    SecurityAction.READ -> SandboxOperation.READ
    SecurityAction.WRITE -> SandboxOperation.WRITE
    SecurityAction.CREATE -> SandboxOperation.CREATE
    SecurityAction.DELETE -> SandboxOperation.DELETE
    SecurityAction.RENAME -> SandboxOperation.RENAME
    SecurityAction.OPEN -> SandboxOperation.OPEN
    SecurityAction.EXECUTE, SecurityAction.NETWORK, SecurityAction.INSTALL, SecurityAction.REMOVE,
    SecurityAction.CONTROL, SecurityAction.UNKNOWN -> SandboxOperation.EXECUTE
}

private fun SecurityRequest.category(): PermissionCategory = when (resourceType) {
    ResourceType.CREDENTIAL -> PermissionCategory.CREDENTIALS
    ResourceType.SENSITIVE_FILE -> PermissionCategory.SENSITIVE_FILES
    ResourceType.NETWORK -> PermissionCategory.NETWORK
    ResourceType.PACKAGE_MANAGER -> PermissionCategory.PACKAGES
    ResourceType.TERMINAL, ResourceType.PROCESS, ResourceType.LINUX_RUNTIME -> PermissionCategory.TERMINAL
    else -> PermissionCategory.FILES
}

/** Builds a [SecurityRequest] from an untrusted tool call. */
object SecurityRequestFactory {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun create(
        definition: ToolDefinition,
        call: AIToolCall,
        args: JsonObject,
        context: ToolContext,
        classification: CommandClassification? = null,
        sessionId: String? = null
    ): SecurityRequest {
        val resource = resourceValue(definition, args)
        return SecurityRequest(
            projectId = context.projectId,
            projectRoot = context.projectRoot,
            taskId = context.taskId,
            sessionId = sessionId,
            agentId = context.agentId,
            toolName = definition.name,
            action = definition.action,
            resourceType = definition.resourceType,
            resource = resource,
            riskLevel = definition.riskLevel,
            declaredPermission = definition.permission,
            classificationDriven = definition.classificationDriven,
            classification = classification,
            networkImpact = definition.networkImpact,
            filesystemImpact = definition.filesystemImpact,
            destructive = definition.destructive,
            sensitiveDeclared = definition.sensitive,
            metadata = mapOf("callId" to call.id)
        )
    }

    /** The argument that names the resource, or null when the tool does not name one. */
    fun resourceValue(definition: ToolDefinition, args: JsonObject): String? {
        definition.resourceArgument?.let { name ->
            return (args[name] as? JsonPrimitive)?.content
        }
        val candidate = when (definition.resourceType) {
            ResourceType.TERMINAL, ResourceType.PROCESS, ResourceType.LINUX_RUNTIME -> "command"
            ResourceType.PROJECT_FILE, ResourceType.PROJECT_DIRECTORY, ResourceType.SENSITIVE_FILE -> "path"
            ResourceType.EDITOR -> "path"
            else -> null
        } ?: return null
        return (args[candidate] as? JsonPrimitive)?.content
    }
}
