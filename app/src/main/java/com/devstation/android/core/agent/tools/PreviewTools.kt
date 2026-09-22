package com.devstation.android.core.agent.tools

import com.devstation.android.core.ai.AIToolCall
import com.devstation.android.core.ai.AIToolParameter
import com.devstation.android.core.ai.AIToolParameterType
import com.devstation.android.core.agent.Tool
import com.devstation.android.core.agent.ToolContext
import com.devstation.android.core.agent.ToolDefinition
import com.devstation.android.core.agent.ToolPermission
import com.devstation.android.core.agent.ToolResult
import com.devstation.android.core.agent.ToolRiskLevel
import com.devstation.android.core.preview.PreviewOwner
import com.devstation.android.core.preview.PreviewServerManager
import com.devstation.android.core.preview.PreviewServerState
import com.devstation.android.core.security.policy.ImpactLevel
import com.devstation.android.core.security.policy.NetworkIntent
import com.devstation.android.core.security.policy.ResourceType
import com.devstation.android.core.security.policy.SecurityAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Phase 9 §31/§32: agent preview tools.
 *
 * Every tool delegates to [PreviewServerManager] — the agent has no direct process access.
 * The manager enforces the terminal policy gate, sandboxed working directory, sanitized
 * environment, loopback-only binding and cross-project isolation, so these tools cannot:
 * start arbitrary system processes, bind external interfaces, touch another project's
 * preview, or bypass the approval chain.
 */
class StartPreviewTool(
    private val previewManager: PreviewServerManager,
    private val projectName: () -> String
) : Tool {

    override val definition = ToolDefinition(
        name = "start_preview",
        description = "Start the project's local development preview server on a localhost port. " +
            "The command must be an ordinary development command (e.g. 'npm run dev'). It is " +
            "classified by the terminal security policy and may require user approval. The server " +
            "binds to 127.0.0.1 only — external interfaces are never allowed from the agent.",
        parameters = listOf(
            AIToolParameter("command", AIToolParameterType.STRING, "Preview command to run"),
            AIToolParameter("arguments", AIToolParameterType.STRING, "Space-separated command arguments", required = false),
            AIToolParameter("port", AIToolParameterType.INTEGER, "Preferred localhost port (default 3000)", required = false),
            AIToolParameter("workingDirectory", AIToolParameterType.STRING, "Project-relative working directory", required = false)
        ),
        riskLevel = ToolRiskLevel.HIGH,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.TERMINAL,
        action = SecurityAction.EXECUTE,
        resourceArgument = "command",
        networkImpact = NetworkIntent.LOCAL_NETWORK,
        filesystemImpact = ImpactLevel.PROJECT
    )

    override fun summarize(args: JsonObject) =
        "Start preview server `${(args["command"] as? JsonPrimitive)?.content ?: ""}`"

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val command = (args["command"] as? JsonPrimitive)?.content
            ?: return ToolResult.Error(definition.name, "A 'command' is required.")
        val arguments = ((args["arguments"] as? JsonPrimitive)?.content ?: "")
            .split(' ').filter { it.isNotBlank() }
        val port = (args["port"] as? JsonPrimitive)?.intOrNull ?: 3000
        val workingDir = ((args["workingDirectory"] as? JsonPrimitive)?.content)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { context.resolveWithinProject(it).absolutePath }.getOrElse { return ToolResult.Error(definition.name, "Invalid working directory.") } }
            ?: context.projectRoot.absolutePath

        val result = previewManager.start(
            projectId = context.projectId,
            projectName = projectName(),
            command = command,
            arguments = arguments,
            workingDirectory = workingDir,
            requestedPort = port,
            owner = PreviewOwner.AGENT,
            allowExternalBind = false // §32: never from the agent
        )
        return result.fold(
            onSuccess = { server ->
                ToolResult.Success(
                    definition.name,
                    "Preview server starting on ${server.url} (state: ${server.state}).",
                    metadata = mapOf("port" to server.port.toString(), "url" to server.url)
                )
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Preview failed to start.") }
        )
    }
}

class StopPreviewTool(
    private val previewManager: PreviewServerManager
) : Tool {

    override val definition = ToolDefinition(
        name = "stop_preview",
        description = "Stop this project's managed preview server. Only the owning project's server can be stopped.",
        parameters = listOf(
            AIToolParameter("serverId", AIToolParameterType.STRING, "Preview server id (omit to stop the project's server)", required = false)
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.PROCESS,
        action = SecurityAction.EXECUTE,
        networkImpact = NetworkIntent.NONE,
        filesystemImpact = ImpactLevel.NONE
    )

    override fun summarize(args: JsonObject) = "Stop preview server"

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val serverId = (args["serverId"] as? JsonPrimitive)?.content
            ?: previewManager.serverForProject(context.projectId)?.id
            ?: return ToolResult.Error(definition.name, "No preview server is running for this project.")
        return previewManager.stop(serverId, requesterProjectId = context.projectId).fold(
            onSuccess = { ToolResult.Success(definition.name, "Preview server stopped.") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Could not stop the preview server.") }
        )
    }
}

class RestartPreviewTool(
    private val previewManager: PreviewServerManager
) : Tool {

    override val definition = ToolDefinition(
        name = "restart_preview",
        description = "Restart this project's managed preview server. Restart attempts are limited.",
        parameters = listOf(
            AIToolParameter("serverId", AIToolParameterType.STRING, "Preview server id (omit for the project's server)", required = false)
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ALWAYS_ASK,
        resourceType = ResourceType.PROCESS,
        action = SecurityAction.EXECUTE,
        networkImpact = NetworkIntent.LOCAL_NETWORK,
        filesystemImpact = ImpactLevel.NONE
    )

    override fun summarize(args: JsonObject) = "Restart preview server"

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val serverId = (args["serverId"] as? JsonPrimitive)?.content
            ?: previewManager.serverForProject(context.projectId)?.id
            ?: return ToolResult.Error(definition.name, "No preview server exists for this project.")
        return previewManager.restart(serverId, requesterProjectId = context.projectId).fold(
            onSuccess = { ToolResult.Success(definition.name, "Preview server restarted on port ${it.port}.") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Could not restart the preview server.") }
        )
    }
}

class GetPreviewStatusTool(
    private val previewManager: PreviewServerManager
) : Tool {

    override val definition = ToolDefinition(
        name = "get_preview_status",
        description = "Get the status of this project's preview server (state, port, URL). Read-only.",
        parameters = listOf(
            AIToolParameter("serverId", AIToolParameterType.STRING, "Preview server id (omit for the project's server)", required = false)
        ),
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        resourceType = ResourceType.PROCESS,
        action = SecurityAction.READ,
        networkImpact = NetworkIntent.NONE,
        filesystemImpact = ImpactLevel.NONE
    )

    override fun summarize(args: JsonObject) = "Check preview status"

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val serverId = (args["serverId"] as? JsonPrimitive)?.content
        val server = if (serverId != null) {
            previewManager.serverFor(serverId, requesterProjectId = context.projectId).getOrNull()
        } else {
            previewManager.serverForProject(context.projectId)
        } ?: return ToolResult.Success(definition.name, "No preview server for this project.")
        return ToolResult.Success(
            definition.name,
            "Preview server '${server.command}': state=${server.state}, port=${server.port}, url=${server.url}" +
                (server.lastError?.let { ", lastError=$it" } ?: "")
        )
    }
}

class GetPreviewLogsTool(
    private val previewManager: PreviewServerManager
) : Tool {

    override val definition = ToolDefinition(
        name = "get_preview_logs",
        description = "Read recent (bounded, redacted) console output from this project's preview server. Read-only.",
        parameters = listOf(
            AIToolParameter("serverId", AIToolParameterType.STRING, "Preview server id (omit for the project's server)", required = false),
            AIToolParameter("lastLines", AIToolParameterType.INTEGER, "How many recent lines to return (max 200)", required = false)
        ),
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        resourceType = ResourceType.PROCESS,
        action = SecurityAction.READ,
        networkImpact = NetworkIntent.NONE,
        filesystemImpact = ImpactLevel.NONE
    )

    override fun summarize(args: JsonObject) = "Read preview logs"

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val serverId = (args["serverId"] as? JsonPrimitive)?.content
            ?: previewManager.serverForProject(context.projectId)?.id
            ?: return ToolResult.Success(definition.name, "No preview server for this project.")
        val lastLines = ((args["lastLines"] as? JsonPrimitive)?.intOrNull ?: 50).coerceIn(1, 200)
        val logs = previewManager.logsFor(serverId, requesterProjectId = context.projectId).getOrElse {
            return ToolResult.Error(definition.name, it.message ?: "Cannot read preview logs.")
        }
        val text = logs.takeLast(lastLines).joinToString("\n") { "[${it.stream.name}] ${it.text}" }
        return ToolResult.Success(
            definition.name,
            "[TERMINAL OUTPUT]\n" + text.ifBlank { "(no output yet)" }
        )
    }
}

/** All five preview tools for the tool factory seam. */
fun previewTools(
    previewManager: PreviewServerManager,
    projectName: () -> String
): List<Tool> = listOf(
    StartPreviewTool(previewManager, projectName),
    StopPreviewTool(previewManager),
    RestartPreviewTool(previewManager),
    GetPreviewStatusTool(previewManager),
    GetPreviewLogsTool(previewManager)
)
