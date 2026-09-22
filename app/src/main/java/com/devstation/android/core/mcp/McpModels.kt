package com.devstation.android.core.mcp

import com.devstation.android.core.security.policy.ResourceType
import com.devstation.android.core.security.policy.SecurityAction
import java.util.UUID

/**
 * Phase 8 §2/§3: MCP (Model Context Protocol) client data models.
 *
 * These are the normalized internal representations. Each MCP server connection translates
 * its wire format into these models. Nothing here executes — it is pure data.
 */

/** Transport mechanism for an MCP server connection. */
enum class McpTransportType { STDIO, HTTP }

/** Lifecycle state of an MCP server. */
enum class McpServerState {
    DISABLED,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
    STOPPING
}

/** What kind of MCP capability this is. */
enum class McpCapabilityType { TOOL, RESOURCE, PROMPT }

/**
 * Security classification assigned to an MCP capability by the engine.
 * Unknown capabilities always default to the strictest behavior.
 */
enum class McpSecurityClassification {
    READ_ONLY,
    PROJECT_WRITE,
    NETWORK,
    PACKAGE_INSTALL,
    DESTRUCTIVE,
    SYSTEM,
    UNKNOWN
}

/**
 * Persistent configuration for one MCP server.
 *
 * No raw API keys or secrets are stored here. Credential references use
 * [credentialReferenceId] which maps to the existing SecureCredentialStore.
 */
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val transportType: McpTransportType = McpTransportType.STDIO,
    /** For STDIO transport: the command to launch. */
    val command: String = "",
    /** For STDIO transport: arguments passed to the command. */
    val arguments: List<String> = emptyList(),
    /** Sanitized environment variables. Secret values use credential references. */
    val environment: Map<String, String> = emptyMap(),
    /** Reference to a stored credential (never a raw secret). */
    val credentialReferenceId: String? = null,
    /** For HTTP transport: the endpoint URL. */
    val endpoint: String? = null,
    val enabled: Boolean = true,
    val autoConnect: Boolean = false,
    /** Security mode: BALANCED, SAFE, or CUSTOM. */
    val securityMode: String = "BALANCED",
    /** Project scope: null means available to all projects. */
    val projectScope: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    init {
        require(name.isNotBlank()) { "Server name must not be blank" }
        require(name.length <= 100) { "Server name too long" }
        require(description.length <= 500) { "Description too long" }
        when (transportType) {
            McpTransportType.STDIO -> require(command.isNotBlank()) { "STDIO transport requires a command" }
            McpTransportType.HTTP -> require(!endpoint.isNullOrBlank()) { "HTTP transport requires an endpoint" }
        }
    }

    /** True when this config contains any obviously sensitive environment variable names. */
    fun hasSensitiveEnvironmentKeys(): Boolean =
        environment.keys.any { key -> SENSITIVE_ENV_KEYS.any { key.uppercase().contains(it) } }

    companion object {
        private val SENSITIVE_ENV_KEYS = listOf(
            "API_KEY", "TOKEN", "SECRET", "PASSWORD", "PRIVATE_KEY", "CREDENTIAL",
            "AUTH", "ACCESS_KEY", "SESSION_KEY", "OAUTH"
        )
    }
}

/**
 * A discovered capability from an MCP server.
 *
 * [description] and [inputSchema] are untrusted metadata — they never influence security decisions.
 */
data class McpCapability(
    val id: String = UUID.randomUUID().toString(),
    val serverId: String,
    val capabilityType: McpCapabilityType,
    val name: String,
    val description: String = "",
    val inputSchema: Map<String, Any> = emptyMap(),
    val outputMetadata: Map<String, Any> = emptyMap(),
    val discoveredAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = true,
    /** Assigned by the security engine, never by the server. */
    val securityClassification: McpSecurityClassification = McpSecurityClassification.UNKNOWN
) {
    init {
        require(name.isNotBlank()) { "Capability name must not be blank" }
        require(name.length <= 128) { "Capability name too long" }
    }
}

/** JSON-RPC request for MCP protocol communication. */
data class McpJsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String = UUID.randomUUID().toString(),
    val method: String,
    val params: Map<String, Any> = emptyMap()
)

/** JSON-RPC response from an MCP server. */
data class McpJsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: String,
    val result: Map<String, Any>? = null,
    val error: McpJsonRpcError? = null
) {
    val isSuccess: Boolean get() = error == null
}

data class McpJsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null
)

/** Active connection to an MCP server. */
data class McpConnection(
    val serverId: String,
    val sessionId: String = UUID.randomUUID().toString(),
    val connectedAt: Long = System.currentTimeMillis(),
    val capabilities: List<McpCapability> = emptyList()
)

/** Result of executing an MCP tool. */
sealed class McpToolResult {
    abstract val serverId: String
    abstract val capabilityName: String
    abstract val output: String

    data class Success(
        override val serverId: String,
        override val capabilityName: String,
        override val output: String,
        val metadata: Map<String, Any> = emptyMap()
    ) : McpToolResult()

    data class Error(
        override val serverId: String,
        override val capabilityName: String,
        val message: String
    ) : McpToolResult() {
        override val output: String get() = message
    }

    data class Denied(
        override val serverId: String,
        override val capabilityName: String,
        val reason: String
    ) : McpToolResult() {
        override val output: String get() = reason
    }

    data class Timeout(
        override val serverId: String,
        override val capabilityName: String,
        val timeoutMs: Long
    ) : McpToolResult() {
        override val output: String get() = "MCP request timed out after ${timeoutMs}ms"
    }
}

/** Summary of MCP server status for the UI. */
data class McpServerStatus(
    val serverId: String,
    val state: McpServerState,
    val capabilityCount: Int = 0,
    val toolCount: Int = 0,
    val resourceCount: Int = 0,
    val promptCount: Int = 0,
    val lastError: String? = null,
    val lastConnectedAt: Long? = null
)

/** Security-relevant request metadata for MCP operations. */
data class McpSecurityContext(
    val projectId: String?,
    val projectRoot: java.io.File?,
    val taskId: String?,
    val sessionId: String?,
    val agentId: String?
)
