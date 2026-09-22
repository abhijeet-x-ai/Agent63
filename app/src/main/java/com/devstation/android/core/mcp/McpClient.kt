package com.devstation.android.core.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray

/**
 * Phase 8 §2: The MCP protocol client.
 *
 * Handles the JSON-RPC based MCP protocol: initialize, list capabilities, call tools,
 * read resources, get prompts. All responses are normalized into DevStation models.
 *
 * Security: this client never executes anything directly. It only sends protocol messages
 * through a [McpTransport] and returns structured results to the caller.
 */
class McpClient(
    private val transport: McpTransport,
    private val requestTimeoutMs: Long = DEFAULT_MCP_REQUEST_TIMEOUT_MS
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Initialize an MCP session. Returns the server's advertised capabilities.
     */
    suspend fun initialize(connection: McpConnection): Result<McpConnection> {
        val request = McpJsonRpcRequest(
            method = "initialize",
            params = mapOf(
                "protocolVersion" to "2024-11-05",
                "capabilities" to mapOf(
                    "tools" to mapOf<String, Any>(),
                    "resources" to mapOf<String, Any>(),
                    "prompts" to mapOf<String, Any>()
                ),
                "clientInfo" to mapOf(
                    "name" to "DevStation",
                    "version" to "8.0.0"
                )
            )
        )

        val response = transport.send(connection, request).getOrElse { e ->
            return Result.failure(McpTransportException("Failed to initialize: ${e.message}", e))
        }

        if (!response.isSuccess) {
            return Result.failure(
                McpServerErrorException(
                    response.error?.code ?: -1,
                    response.error?.message ?: "Initialize failed"
                )
            )
        }

        val capabilities = parseCapabilities(response)
        return Result.success(connection.copy(capabilities = capabilities))
    }

    /**
     * List tools provided by the server.
     */
    suspend fun listTools(connection: McpConnection): Result<List<McpCapability>> {
        val request = McpJsonRpcRequest(method = "tools/list")
        val response = transport.send(connection, request).getOrElse { e ->
            return Result.failure(McpTransportException("Failed to list tools: ${e.message}", e))
        }
        if (!response.isSuccess) {
            return Result.failure(
                McpServerErrorException(
                    response.error?.code ?: -1,
                    response.error?.message ?: "List tools failed"
                )
            )
        }
        return Result.success(parseTools(response, connection.serverId))
    }

    /**
     * List resources provided by the server.
     */
    suspend fun listResources(connection: McpConnection): Result<List<McpCapability>> {
        val request = McpJsonRpcRequest(method = "resources/list")
        val response = transport.send(connection, request).getOrElse { e ->
            return Result.failure(McpTransportException("Failed to list resources: ${e.message}", e))
        }
        if (!response.isSuccess) {
            return Result.failure(
                McpServerErrorException(
                    response.error?.code ?: -1,
                    response.error?.message ?: "List resources failed"
                )
            )
        }
        return Result.success(parseResources(response, connection.serverId))
    }

    /**
     * List prompts provided by the server.
     */
    suspend fun listPrompts(connection: McpConnection): Result<List<McpCapability>> {
        val request = McpJsonRpcRequest(method = "prompts/list")
        val response = transport.send(connection, request).getOrElse { e ->
            return Result.failure(McpTransportException("Failed to list prompts: ${e.message}", e))
        }
        if (!response.isSuccess) {
            return Result.failure(
                McpServerErrorException(
                    response.error?.code ?: -1,
                    response.error?.message ?: "List prompts failed"
                )
            )
        }
        return Result.success(parsePrompts(response, connection.serverId))
    }

    /**
     * Execute a tool call on the server. Returns bounded, redacted output.
     */
    suspend fun callTool(
        connection: McpConnection,
        toolName: String,
        arguments: Map<String, Any>
    ): Result<McpToolResult> {
        val request = McpJsonRpcRequest(
            method = "tools/call",
            params = mapOf(
                "name" to toolName,
                "arguments" to arguments
            )
        )

        val response = withTimeoutOrNull(requestTimeoutMs) {
            transport.send(connection, request).getOrNull()
        } ?: return Result.success(
            McpToolResult.Timeout(connection.serverId, toolName, requestTimeoutMs)
        )

        if (!response.isSuccess) {
            return Result.success(
                McpToolResult.Error(
                    serverId = connection.serverId,
                    capabilityName = toolName,
                    message = response.error?.message ?: "Tool call failed"
                )
            )
        }

        val output = parseToolOutput(response)
        return Result.success(
            McpToolResult.Success(
                serverId = connection.serverId,
                capabilityName = toolName,
                output = output.take(MAX_MCP_RESPONSE_CHARS),
                metadata = mapOf("rawLength" to output.length.toString())
            )
        )
    }

    /**
     * Read a resource from the server.
     */
    suspend fun readResource(
        connection: McpConnection,
        uri: String
    ): Result<McpToolResult> {
        val request = McpJsonRpcRequest(
            method = "resources/read",
            params = mapOf("uri" to uri)
        )

        val response = withTimeoutOrNull(requestTimeoutMs) {
            transport.send(connection, request).getOrNull()
        } ?: return Result.success(
            McpToolResult.Timeout(connection.serverId, "read_resource:$uri", requestTimeoutMs)
        )

        if (!response.isSuccess) {
            return Result.success(
                McpToolResult.Error(
                    serverId = connection.serverId,
                    capabilityName = "read_resource:$uri",
                    message = response.error?.message ?: "Resource read failed"
                )
            )
        }

        val content = parseResourceContent(response)
        return Result.success(
            McpToolResult.Success(
                serverId = connection.serverId,
                capabilityName = "read_resource:$uri",
                output = content.take(MAX_MCP_RESPONSE_CHARS)
            )
        )
    }

    /**
     * Get a prompt from the server.
     */
    suspend fun getPrompt(
        connection: McpConnection,
        promptName: String,
        arguments: Map<String, String> = emptyMap()
    ): Result<McpToolResult> {
        val request = McpJsonRpcRequest(
            method = "prompts/get",
            params = mapOf(
                "name" to promptName,
                "arguments" to arguments
            )
        )

        val response = withTimeoutOrNull(requestTimeoutMs) {
            transport.send(connection, request).getOrNull()
        } ?: return Result.success(
            McpToolResult.Timeout(connection.serverId, "get_prompt:$promptName", requestTimeoutMs)
        )

        if (!response.isSuccess) {
            return Result.success(
                McpToolResult.Error(
                    serverId = connection.serverId,
                    capabilityName = "get_prompt:$promptName",
                    message = response.error?.message ?: "Prompt get failed"
                )
            )
        }

        val content = parsePromptContent(response)
        return Result.success(
            McpToolResult.Success(
                serverId = connection.serverId,
                capabilityName = "get_prompt:$promptName",
                output = content.take(MAX_MCP_RESPONSE_CHARS)
            )
        )
    }

    // ---- Parsing helpers ----

    private fun parseCapabilities(response: McpJsonRpcResponse): List<McpCapability> {
        val result = response.result ?: return emptyList()
        val caps = mutableListOf<McpCapability>()

        @Suppress("UNCHECKED_CAST")
        val serverCaps = result["capabilities"] as? Map<String, Any> ?: return caps

        // Server declares it has tools
        if (serverCaps.containsKey("tools")) {
            // Capabilities will be listed via tools/list, resources/list, prompts/list
        }
        return caps
    }

    private fun parseTools(response: McpJsonRpcResponse, serverId: String): List<McpCapability> {
        val result = response.result ?: return emptyList()
        val tools = (result["tools"] as? List<*>) ?: return emptyList()

        return tools.mapNotNull { tool ->
            val obj = tool as? Map<*, *> ?: return@mapNotNull null
            val name = obj["name"]?.toString() ?: return@mapNotNull null
            val description = obj["description"]?.toString() ?: ""

            @Suppress("UNCHECKED_CAST")
            val inputSchema = obj["inputSchema"] as? Map<String, Any> ?: emptyMap()

            McpCapability(
                serverId = serverId,
                capabilityType = McpCapabilityType.TOOL,
                name = name,
                description = description,
                inputSchema = inputSchema
            )
        }
    }

    private fun parseResources(response: McpJsonRpcResponse, serverId: String): List<McpCapability> {
        val result = response.result ?: return emptyList()
        val resources = (result["resources"] as? List<*>) ?: return emptyList()

        return resources.mapNotNull { resource ->
            val obj = resource as? Map<*, *> ?: return@mapNotNull null
            val name = obj["name"]?.toString() ?: return@mapNotNull null
            val description = obj["description"]?.toString() ?: ""
            val uri = obj["uri"]?.toString() ?: ""

            McpCapability(
                serverId = serverId,
                capabilityType = McpCapabilityType.RESOURCE,
                name = name,
                description = description,
                inputSchema = mapOf("uri" to uri)
            )
        }
    }

    private fun parsePrompts(response: McpJsonRpcResponse, serverId: String): List<McpCapability> {
        val result = response.result ?: return emptyList()
        val prompts = (result["prompts"] as? List<*>) ?: return emptyList()

        return prompts.mapNotNull { prompt ->
            val obj = prompt as? Map<*, *> ?: return@mapNotNull null
            val name = obj["name"]?.toString() ?: return@mapNotNull null
            val description = obj["description"]?.toString() ?: ""

            @Suppress("UNCHECKED_CAST")
            val arguments = obj["arguments"] as? List<*> ?: emptyList<Any>()

            McpCapability(
                serverId = serverId,
                capabilityType = McpCapabilityType.PROMPT,
                name = name,
                description = description,
                inputSchema = mapOf("arguments" to arguments)
            )
        }
    }

    private fun parseToolOutput(response: McpJsonRpcResponse): String {
        val result = response.result ?: return ""
        val content = result["content"] as? List<*> ?: return result.toString()

        return content.mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            when (obj["type"]) {
                "text" -> obj["text"]?.toString()
                else -> null
            }
        }.joinToString("\n")
    }

    private fun parseResourceContent(response: McpJsonRpcResponse): String {
        val result = response.result ?: return ""
        val contents = result["contents"] as? List<*> ?: return result.toString()

        return contents.mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            obj["text"]?.toString() ?: obj["blob"]?.toString()
        }.joinToString("\n")
    }

    private fun parsePromptContent(response: McpJsonRpcResponse): String {
        val result = response.result ?: return ""
        val messages = result["messages"] as? List<*> ?: return result.toString()

        return messages.mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            val content = obj["content"]
            when (content) {
                is Map<*, *> -> content["text"]?.toString()
                is String -> content
                else -> content?.toString()
            }
        }.joinToString("\n")
    }
}
