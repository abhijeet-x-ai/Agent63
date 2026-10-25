package com.devstation.android.core.agent.tools

import com.devstation.android.core.agent.Tool
import com.devstation.android.core.agent.ToolContext
import com.devstation.android.core.agent.ToolDefinition
import com.devstation.android.core.agent.ToolPermission
import com.devstation.android.core.agent.ToolResult
import com.devstation.android.core.agent.ToolRiskLevel
import com.devstation.android.core.ai.AIToolCall
import com.devstation.android.core.ai.AIToolParameter
import com.devstation.android.core.ai.AIToolParameterType
import com.devstation.android.core.remote.RemoteSessionState
import com.devstation.android.core.remote.SshHostRepository
import com.devstation.android.core.remote.SshSessionManager
import com.devstation.android.core.security.policy.NetworkIntent
import com.devstation.android.core.security.policy.ResourceType
import com.devstation.android.core.security.policy.SecurityAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 11: SSH Remote Workspace agent tools.
 *
 * The tools operate on the app's single [SshSessionManager] session — the
 * same session the user drives from the Remote Workspace UI — and ride the
 * standard ToolRegistry → ToolExecutor → SecurityPolicyEngine pipeline.
 * `remote_exec` is ALWAYS_ASK: every remote command needs a fresh user
 * approval, exactly like a destructive local operation.
 */

class RemoteConnectTool(
    private val sessionManager: SshSessionManager,
    private val repository: SshHostRepository
) : Tool {
    override val definition = ToolDefinition(
        name = "remote_connect",
        description = "Connect to a saved SSH host by its host id (see the Remote Workspace screen for ids). Fails when the server host key is not trusted yet — the user must trust it in the UI first.",
        parameters = listOf(
            AIToolParameter("host_id", AIToolParameterType.STRING, "Saved SSH host profile id", required = true)
        ),
        riskLevel = ToolRiskLevel.HIGH,
        permission = ToolPermission.ASK,
        requiresProject = false,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK,
        networkImpact = NetworkIntent.INTERNET
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val hostId = args["host_id"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error(definition.name, "Missing required parameter: host_id")
        val profile = repository.profile(hostId)
            ?: return ToolResult.Error(definition.name, "No saved SSH host with id $hostId")
        return sessionManager.connectProfile(hostId).fold(
            onSuccess = { ToolResult.Success(definition.name, "Connected to ${profile.displayAddress}") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Connection failed") }
        )
    }
}

class RemoteDisconnectTool(
    private val sessionManager: SshSessionManager
) : Tool {
    override val definition = ToolDefinition(
        name = "remote_disconnect",
        description = "Disconnect the active SSH session, if any.",
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        requiresProject = false,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK,
        networkImpact = NetworkIntent.INTERNET
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        sessionManager.disconnect()
        return ToolResult.Success(definition.name, "Disconnected")
    }
}

class RemoteExecTool(
    private val sessionManager: SshSessionManager
) : Tool {
    override val definition = ToolDefinition(
        name = "remote_exec",
        description = "Run one command on the connected SSH server and return its bounded output. Requires an active remote_connect session. Every invocation needs fresh user approval.",
        parameters = listOf(
            AIToolParameter("command", AIToolParameterType.STRING, "Shell command to run on the server", required = true),
            AIToolParameter("timeout_seconds", AIToolParameterType.STRING, "Timeout in seconds (default 120)", required = false)
        ),
        riskLevel = ToolRiskLevel.HIGH,
        permission = ToolPermission.ALWAYS_ASK,
        requiresProject = false,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK,
        networkImpact = NetworkIntent.INTERNET
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        if (sessionManager.sessionState.value != RemoteSessionState.CONNECTED) {
            return ToolResult.Error(definition.name, "Not connected — call remote_connect first")
        }
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error(definition.name, "Missing required parameter: command")
        val timeoutSeconds = args["timeout_seconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 120L
        return sessionManager.exec(command, timeoutMs = timeoutSeconds * 1_000L).fold(
            onSuccess = { result ->
                val sb = StringBuilder()
                sb.appendLine("exit=${result.exitCode ?: "timeout"}")
                if (result.stdout.isNotEmpty()) sb.appendLine(result.stdout.trimEnd())
                if (result.stderr.isNotEmpty()) {
                    sb.appendLine("[stderr]")
                    sb.appendLine(result.stderr.trimEnd())
                }
                if (result.truncated) sb.appendLine("[output truncated]")
                ToolResult.Success(definition.name, sb.toString().trimEnd())
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Remote command failed") }
        )
    }
}

class RemoteSyncPushTool(
    private val sessionManager: SshSessionManager
) : Tool {
    override val definition = ToolDefinition(
        name = "remote_sync_push",
        description = "Sync the local project to a directory on the connected SSH server over SFTP. Only new or changed files are uploaded.",
        parameters = listOf(
            AIToolParameter("remote_path", AIToolParameterType.STRING, "Destination directory on the server (absolute or ~/relative)", required = true),
            AIToolParameter("local_path", AIToolParameterType.STRING, "Local directory to sync (default: the current project root)", required = false)
        ),
        riskLevel = ToolRiskLevel.HIGH,
        permission = ToolPermission.ASK,
        requiresProject = false,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK,
        networkImpact = NetworkIntent.INTERNET
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        if (sessionManager.sessionState.value != RemoteSessionState.CONNECTED) {
            return ToolResult.Error(definition.name, "Not connected — call remote_connect first")
        }
        val remotePath = args["remote_path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error(definition.name, "Missing required parameter: remote_path")
        val localPath = args["local_path"]?.jsonPrimitive?.contentOrNull
            ?: context.projectRoot.absolutePath
        return sessionManager.syncLocalToRemote(localPath, remotePath).fold(
            onSuccess = {
                val progress = sessionManager.syncProgress.value
                val summary = if (progress != null) {
                    "Synced ${progress.completedFiles} file(s) to $remotePath"
                } else {
                    "Sync complete to $remotePath"
                }
                ToolResult.Success(definition.name, summary)
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Sync failed") }
        )
    }
}

/** Phase 11 tool set factory, mirroring GitToolSet. */
object RemoteToolSet {
    fun createTools(
        sessionManager: SshSessionManager,
        repository: SshHostRepository
    ): List<Tool> = listOf(
        RemoteConnectTool(sessionManager, repository),
        RemoteDisconnectTool(sessionManager),
        RemoteExecTool(sessionManager),
        RemoteSyncPushTool(sessionManager)
    )
}
