package com.devstation.android.future.mcp

import kotlinx.coroutines.flow.Flow

data class McpServerConfig(
    val id: String,
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap()
)

data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: String
)

/**
 * Extension contract for Phase 8: Model Context Protocol (MCP) Client.
 */
interface MCPClient {
    val connectedServers: Flow<List<McpServerConfig>>
    val availableTools: Flow<List<McpToolDefinition>>

    suspend fun connectServer(config: McpServerConfig): Result<Unit>
    suspend fun disconnectServer(serverId: String): Result<Unit>
    suspend fun callTool(serverName: String, toolName: String, paramsJson: String): Result<String>
}
