package com.devstation.android.core.agent

import com.devstation.android.core.agent.tools.RunTerminalCommandTool
import com.devstation.android.core.ai.AIToolCall
import com.devstation.android.core.security.policy.AuditDecision
import com.devstation.android.core.security.policy.SecurityAuditLogger
import com.devstation.android.core.security.policy.SecurityDecision
import com.devstation.android.core.security.policy.SecurityEventType
import com.devstation.android.core.security.policy.SecurityGrantLookup
import com.devstation.android.core.security.policy.SecurityOutcomeType
import com.devstation.android.core.security.policy.SecurityPolicyEngine
import com.devstation.android.core.security.policy.SecurityRequestFactory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Runs exactly one tool call through the full safety pipeline.
 *
 * Phase 6 shape (unchanged):
 *
 * ```
 * unknown tool?      -> error
 * parse arguments    -> error on invalid JSON
 * schema validation  -> error on invalid arguments
 * permission policy  -> ALLOW | ASK + approval UI | DENY
 * execute            -> normalized, bounded, redacted result
 * ```
 *
 * Phase 7 makes the permission step a centralized decision ([SecurityPolicyEngine]) and adds
 * revalidation immediately before execution, so a revoked permission or a changed file cannot slip
 * through (§49/§50). Tool bodies are still unreachable without passing that check.
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val permissionManager: PermissionManager,
    private val limits: AgentLoopLimits,
    securityEngine: SecurityPolicyEngine? = null,
    private val audit: SecurityAuditLogger = SecurityAuditLogger.NoOp,
    private val sessionId: String? = null
) {

    /**
     * Default engine: policy defaults (phase 6-equivalent BALANCED) plus the live grant store, so
     * behaviour without explicit wiring stays exactly as it was.
     */
    private val engine: SecurityPolicyEngine = securityEngine ?: SecurityPolicyEngine(
        grants = SecurityGrantLookup { scope, toolName, taskId, sessionId, projectId ->
            permissionManager.hasGrant(scope, toolName, taskId, sessionId, projectId)
        },
        audit = audit
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun execute(
        call: AIToolCall,
        context: ToolContext,
        agentToolsEnabled: Boolean,
        emit: suspend (AgentEvent) -> Unit
    ): ToolResult {
        val tool = registry.get(call.name)
            ?: return fail(emit, call.name, "Unknown tool '${call.name}'.")

        val args = try {
            parseArguments(call.argumentsJson)
        } catch (e: Exception) {
            return fail(emit, call.name, "Could not parse tool arguments as JSON.")
        }

        when (val validation = ToolArgumentValidator.validate(tool.definition, args)) {
            is ArgumentValidation.Invalid -> return fail(emit, call.name, validation.message)
            ArgumentValidation.Valid -> Unit
        }

        val classification = (tool as? RunTerminalCommandTool)?.classify(args)
        val request = SecurityRequestFactory.create(
            definition = tool.definition,
            call = call,
            args = args,
            context = context,
            classification = classification,
            sessionId = sessionId
        )

        emit(AgentEvent.ToolCallRequested(call.name, tool.summarize(args)))

        // True when the user made an explicit decision about this exact invocation (§49).
        var decidedNow = false

        val decision = engine.authorize(request, agentToolsEnabled)
        if (decision.denied) {
            emit(AgentEvent.ToolDenied(call.name, decision.reason))
            return ToolResult.Denied(call.name, decision.reason)
        }

        val approvalRequest = buildApprovalRequest(call.name, tool.summarize(args), decision, context)
        if (decision.requiresApproval) {
            emit(
                AgentEvent.ToolApprovalRequired(
                    requestId = approvalRequest.requestId,
                    toolName = call.name,
                    title = approvalRequest.title,
                    target = approvalRequest.target,
                    riskLevel = approvalRequest.riskLevel,
                    detail = approvalRequest.detail
                )
            )
            audit.log(
                type = SecurityEventType.PERMISSION_REQUESTED,
                decision = AuditDecision.ASKED,
                request = request,
                riskLevel = decision.riskLevel,
                summary = "${approvalRequest.title}: ${approvalRequest.target}"
            )
            val resolution = permissionManager.resolveApproval(
                request = approvalRequest,
                requiredPermission = decision.requiredPermission ?: ToolPermission.ASK,
                toolName = call.name,
                taskId = context.taskId,
                sessionId = sessionId,
                projectId = context.projectId
            )
            decidedNow = resolution.decidedNow
            when (val outcome = resolution.outcome) {
                is PermissionOutcome.Denied -> {
                    audit.log(
                        type = SecurityEventType.PERMISSION_DENIED,
                        decision = AuditDecision.DENIED,
                        request = request,
                        riskLevel = decision.riskLevel,
                        summary = "${call.name} denied: ${approvalRequest.target}"
                    )
                    emit(AgentEvent.ToolDenied(call.name, outcome.reason))
                    return ToolResult.Denied(call.name, outcome.reason)
                }
                PermissionOutcome.Allowed -> audit.log(
                    type = SecurityEventType.PERMISSION_GRANTED,
                    decision = AuditDecision.ALLOWED,
                    request = request,
                    riskLevel = decision.riskLevel,
                    summary = "${call.name} approved: ${approvalRequest.target}"
                )
            }
        }

        // §49/§50: the decision is only valid at this instant — re-check the authorization (policy
        // and grants) and the resource before the tool body runs.
        val revalidated = engine.revalidate(
            request = request,
            previous = decision,
            agentToolsEnabled = agentToolsEnabled,
            approvedForThisRequest = decidedNow
        )
        if (revalidated.denied) {
            emit(AgentEvent.ToolDenied(call.name, revalidated.reason))
            return ToolResult.Denied(call.name, revalidated.reason)
        }

        if (context.isCancelled()) {
            emit(AgentEvent.ToolDenied(call.name, "Task cancelled."))
            return ToolResult.Cancelled(call.name, "Task cancelled before the tool ran.")
        }

        emit(AgentEvent.ToolStarted(call.name, tool.summarize(args)))

        val result = try {
            tool.execute(call, args, context)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ToolResult.Error(call.name, "Tool failed: ${e.message ?: "unknown error"}")
        }

        val sanitized = sanitize(result)
        audit.log(
            type = SecurityEventType.TOOL_EXECUTED,
            decision = if (sanitized.isSuccess) AuditDecision.ALLOWED else AuditDecision.RECORDED,
            request = request,
            riskLevel = decision.riskLevel,
            summary = "${call.name} ${tool.summarize(args)} -> ${sanitized.statusName}"
        )
        emitOutput(emit, call.name, sanitized)
        when (sanitized) {
            is ToolResult.Success -> emit(AgentEvent.ToolCompleted(call.name, firstLine(sanitized.output)))
            is ToolResult.Error -> emit(AgentEvent.ToolFailed(call.name, sanitized.message))
            is ToolResult.Denied -> emit(AgentEvent.ToolDenied(call.name, sanitized.reason))
            is ToolResult.Cancelled -> emit(AgentEvent.AgentCancelled(sanitized.reason))
            is ToolResult.Timeout -> emit(AgentEvent.ToolFailed(call.name, sanitized.output))
        }
        return sanitized
    }

    /** Defense in depth: nothing leaves a tool without a final bound and redaction. */
    private fun sanitize(result: ToolResult): ToolResult {
        val bounded = SecretRedactor.redact(OutputLimiter.truncate(result.output, limits.maxToolOutputChars))
        return when (result) {
            is ToolResult.Success -> result.copy(output = bounded)
            is ToolResult.Error -> result.copy(message = bounded)
            is ToolResult.Denied -> result.copy(reason = bounded)
            is ToolResult.Cancelled -> result.copy(reason = bounded)
            is ToolResult.Timeout -> result.copy()
        }
    }

    private suspend fun emitOutput(emit: suspend (AgentEvent) -> Unit, toolName: String, result: ToolResult) {
        val preview = result.output.lineSequence().take(6).joinToString("\n").take(600)
        if (preview.isNotBlank()) emit(AgentEvent.ToolOutput(toolName, preview))
    }

    private suspend fun fail(emit: suspend (AgentEvent) -> Unit, toolName: String, message: String): ToolResult {
        emit(AgentEvent.ToolFailed(toolName, message))
        return ToolResult.Error(toolName, message)
    }

    private fun parseArguments(raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject(emptyMap())
        val element = json.parseToJsonElement(raw)
        return element as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: "Completed"

    /**
     * The approval card must tell the user exactly what will happen: the tool, the target, the
     * risk, the reason, the destination when a command reaches the network, and whether this is an
     * always-ask action that no grant can satisfy (§27/§43).
     */
    private fun buildApprovalRequest(
        toolName: String,
        target: String,
        decision: SecurityDecision,
        context: ToolContext
    ): ApprovalRequest {
        val detail = buildString {
            decision.explanation?.let { append(it) }
            if (decision.destination != null) {
                if (isNotEmpty()) append(' ')
                append("Destination: ${decision.destination}.")
            }
            when {
                decision.sensitive -> {
                    if (isNotEmpty()) append(' ')
                    append("This file may contain secrets, so its contents are never stored in history.")
                }
                toolName == "run_terminal_command" -> {
                    if (isNotEmpty()) append(' ')
                    append("Runs in a dedicated agent process with a bounded timeout.")
                }
                toolName == "delete_file" -> {
                    if (isNotEmpty()) append(' ')
                    append("This permanently removes the path from the project.")
                }
                else -> if (isNotEmpty()) append(' ')
            }
        }.trim()

        return ApprovalRequest(
            taskId = context.taskId,
            toolName = toolName,
            title = approvalTitle(toolName),
            target = target,
            detail = detail.ifEmpty { "This changes files inside your project." },
            riskLevel = decision.riskLevel,
            scope = if (decision.elevated) PermissionScope.PER_REQUEST else PermissionScope.PER_TASK,
            sessionId = sessionId,
            elevated = decision.elevated,
            category = decision.category,
            resourceType = decision.resourceType,
            networkIntent = decision.networkIntent,
            destination = decision.destination,
            impact = decision.explanation
        )
    }

    private fun approvalTitle(toolName: String): String = when (toolName) {
        "run_terminal_command" -> "Run terminal command"
        "write_file" -> "Modify file"
        "apply_patch" -> "Apply code patch"
        "create_file" -> "Create file"
        "create_directory" -> "Create directory"
        "rename_file" -> "Rename file"
        "delete_file" -> "Delete file"
        else -> "Use tool $toolName"
    }
}
