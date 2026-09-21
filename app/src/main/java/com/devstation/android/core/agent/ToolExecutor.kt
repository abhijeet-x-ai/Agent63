package com.devstation.android.core.agent

import com.devstation.android.core.agent.tools.RunTerminalCommandTool
import com.devstation.android.core.ai.AIToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Runs exactly one tool call through the full safety pipeline:
 *
 * ```
 * unknown tool?      -> error
 * parse arguments    -> error on invalid JSON
 * schema validation  -> error on invalid arguments
 * permission policy  -> ALLOW | ASK + approval UI | DENY
 * execute            -> normalized, bounded, redacted result
 * ```
 *
 * Tool bodies can never be reached without passing through [PermissionManager].
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val permissionManager: PermissionManager,
    private val limits: AgentLoopLimits
) {

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

        emit(AgentEvent.ToolCallRequested(call.name, tool.summarize(args)))

        val approvalRequest = buildApprovalRequest(call.name, tool.summarize(args), classification, context)
        if (permissionManager.willRequireApproval(tool, context, classification, agentToolsEnabled)) {
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
        }

        when (val outcome = permissionManager.authorize(tool, context, approvalRequest, agentToolsEnabled, classification)) {
            is PermissionOutcome.Denied -> {
                emit(AgentEvent.ToolDenied(call.name, outcome.reason))
                return ToolResult.Denied(call.name, outcome.reason)
            }
            PermissionOutcome.Allowed -> Unit
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

    private fun buildApprovalRequest(
        toolName: String,
        target: String,
        classification: CommandClassification?,
        context: ToolContext
    ): ApprovalRequest {
        val risk = classification?.riskLevel ?: riskFor(toolName)
        val detail = buildString {
            classification?.let {
                append(it.reason)
                append(". ")
            }
            when {
                classification != null -> append("Runs in a dedicated agent process with a bounded timeout.")
                toolName == "delete_file" -> append("This permanently removes the path from the project.")
                else -> append("This changes files inside your project.")
            }
        }
        return ApprovalRequest(
            taskId = context.taskId,
            toolName = toolName,
            title = approvalTitle(toolName),
            target = target,
            detail = detail,
            riskLevel = risk
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

    private fun riskFor(toolName: String): ToolRiskLevel = when (toolName) {
        "delete_file", "run_terminal_command" -> ToolRiskLevel.HIGH
        "write_file", "apply_patch", "create_file", "create_directory", "rename_file" -> ToolRiskLevel.MEDIUM
        else -> ToolRiskLevel.LOW
    }
}
