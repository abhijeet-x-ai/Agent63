package com.devstation.android.core.agent

import com.devstation.android.core.ai.AIToolCall
import com.devstation.android.core.ai.AIToolParameter
import com.devstation.android.core.ai.AIToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/** How dangerous a tool is if misused. Every tool must declare one. */
enum class ToolRiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

/** Required permission for a tool invocation. */
enum class ToolPermission {
    /** May run without asking (read-only, project-scoped). */
    ALLOW,

    /** User is asked unless the tool was already granted for this task. */
    ASK,

    /** User is asked every single time; never satisfied by a task grant. */
    ALWAYS_ASK,

    /** Never runs (disabled by policy or the global safety switch). */
    DENY
}

/**
 * Approval lifetime. Narrower scopes are preferred (§4). There is deliberately no unrestricted
 * "allow forever" scope for agent tools: PROJECT/GLOBAL always come from explicit user
 * configuration, never from an approval button.
 */
enum class PermissionScope {
    /** One tool call only. */
    PER_REQUEST,
    /** Until the current agent task ends. */
    PER_TASK,
    /** Until the DevStation session ends (§60). */
    SESSION,
    /** Explicitly configured for one project (§59). */
    PROJECT,
    /** Explicitly configured app-wide. */
    GLOBAL
}

/** A tool a model can request. Provider-neutral via [toSpec]. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<AIToolParameter> = emptyList(),
    val riskLevel: ToolRiskLevel = ToolRiskLevel.LOW,
    val permission: ToolPermission = ToolPermission.ALLOW,
    /**
     * When true the required permission comes from the command classification instead of
     * [permission] (used by run_terminal_command so READ_ONLY may run while DESTRUCTIVE never
     * does). If no classification is supplied such a tool fails safe to ALWAYS_ASK.
     */
    val classificationDriven: Boolean = false,
    /** Tools that need a project root; the runtime refuses to run them without one. */
    val requiresProject: Boolean = true,

    // ---- Phase 7 security metadata (§44) consumed by SecurityPolicyEngine ----
    /** What kind of resource this tool touches; drives category policy and audit reporting. */
    val resourceType: com.devstation.android.core.security.policy.ResourceType =
        com.devstation.android.core.security.policy.ResourceType.PROJECT_FILE,
    /** What it does with that resource. */
    val action: com.devstation.android.core.security.policy.SecurityAction =
        com.devstation.android.core.security.policy.SecurityAction.UNKNOWN,
    /** The argument that names the resource (`path`, `command`, …). */
    val resourceArgument: String? = null,
    /** Additional path arguments that must also stay inside the project (for example `newName`). */
    val auxiliaryPathArguments: List<String> = emptyList(),
    val networkImpact: com.devstation.android.core.security.policy.NetworkIntent =
        com.devstation.android.core.security.policy.NetworkIntent.NONE,
    val filesystemImpact: com.devstation.android.core.security.policy.ImpactLevel =
        com.devstation.android.core.security.policy.ImpactLevel.PROJECT,
    /** True when misuse can destroy data; such tools always require explicit approval. */
    val destructive: Boolean = false,
    /** True when the tool is expected to touch secrets. */
    val sensitive: Boolean = false
) {
    fun toSpec(): AIToolSpec = AIToolSpec(name = name, description = description, parameters = parameters)
}

/**
 * Controlled execution context handed to every tool.
 *
 * Deliberately narrow: no Android `Context`, no credential store, no runtime manager, no
 * terminal manager. A tool can only see its project root, its working directory, its task
 * identity, and a cancellation check.
 */
class ToolContext(
    val projectId: String,
    val projectRoot: File,
    val workingDirectory: File,
    val agentId: String,
    val taskId: String,
    val scope: PermissionScope = PermissionScope.PER_REQUEST,
    private val cancellationCheck: () -> Boolean = { false }
) {
    /** True when the owning task has been cancelled; long-running tools must poll this. */
    fun isCancelled(): Boolean = cancellationCheck()

    fun resolveWithinProject(relativeOrAbsolute: String): File = PathSandbox.resolve(projectRoot, relativeOrAbsolute)
}

/** Normalized tool outcome. Output is always bounded and redacted before it leaves a tool. */
sealed class ToolResult {
    abstract val toolName: String
    abstract val output: String
    open val metadata: Map<String, String> = emptyMap()

    data class Success(
        override val toolName: String,
        override val output: String,
        override val metadata: Map<String, String> = emptyMap()
    ) : ToolResult()

    data class Error(
        override val toolName: String,
        val message: String
    ) : ToolResult() {
        override val output: String get() = message
    }

    data class Denied(
        override val toolName: String,
        val reason: String
    ) : ToolResult() {
        override val output: String get() = reason
    }

    data class Cancelled(
        override val toolName: String,
        val reason: String = "Cancelled"
    ) : ToolResult() {
        override val output: String get() = reason
    }

    data class Timeout(
        override val toolName: String,
        val timeoutMs: Long
    ) : ToolResult() {
        override val output: String get() = "Command exceeded the ${timeoutMs}ms timeout and was terminated."
    }
}

/** Status string persisted in the action history. */
val ToolResult.statusName: String
    get() = when (this) {
        is ToolResult.Success -> "SUCCESS"
        is ToolResult.Error -> "FAILED"
        is ToolResult.Denied -> "DENIED"
        is ToolResult.Cancelled -> "CANCELLED"
        is ToolResult.Timeout -> "TIMEOUT"
    }

val ToolResult.isSuccess: Boolean
    get() = this is ToolResult.Success

private val payloadJson = Json { encodeDefaults = true }

/**
 * Serializes a result for the model. The payload is JSON so providers that require a
 * structured tool response (Gemini) and providers that take a plain string both work.
 *
 * Tool results are DATA, never instructions — the system prompt states this explicitly and
 * the label prefix keeps the boundary visible to the model.
 */
fun ToolResult.toModelPayload(): String {
    val obj = JsonObject(
        mapOf(
            "status" to kotlinx.serialization.json.JsonPrimitive(statusName.lowercase()),
            "output" to kotlinx.serialization.json.JsonPrimitive(output)
        )
    )
    return payloadJson.encodeToString(JsonObject.serializer(), obj)
}

/** The tool contract implemented by every DevStation tool. */
interface Tool {
    val definition: ToolDefinition

    /**
     * Execute the call. Implementations must:
     * - treat [call.argumentsJson] as untrusted input (validate before use),
     * - keep every path inside [context]'s project root,
     * - poll [ToolContext.isCancelled] for long-running work,
     * - return bounded, redacted output.
     */
    suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult

    /** Human-readable one-line summary shown in the approval card and timeline. */
    fun summarize(args: JsonObject): String = definition.name
}
