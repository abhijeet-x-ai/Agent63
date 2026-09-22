package com.devstation.android.core.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 8 §3: Transport abstraction for MCP protocol communication.
 *
 * Each transport implementation must:
 * - use controlled process creation (no unrestricted ProcessBuilder)
 * - sanitize environment variables
 * - apply timeouts
 * - support cancellation
 * - bound output sizes
 */
interface McpTransport {
    /** Establish a connection to the server described by [config]. */
    suspend fun connect(config: McpServerConfig): Result<McpConnection>

    /** Send a JSON-RPC request and wait for the response. */
    suspend fun send(connection: McpConnection, request: McpJsonRpcRequest): Result<McpJsonRpcResponse>

    /** Gracefully close the connection. */
    suspend fun close(connection: McpConnection)

    /** True when this transport type is available on this device. */
    val isAvailable: Boolean
}

/**
 * Maximum response size from any MCP server. Prevents oversized payloads from consuming
 * unbounded memory or flooding the agent context.
 */
const val MAX_MCP_RESPONSE_CHARS = 256_000

/** Default connection timeout. */
const val DEFAULT_MCP_CONNECT_TIMEOUT_MS = 15_000L

/** Default request timeout. */
const val DEFAULT_MCP_REQUEST_TIMEOUT_MS = 30_000L

/**
 * In-memory transport for testing and development. Simulates MCP server responses
 * without launching any external process.
 */
class InMemoryMcpTransport : McpTransport {
    private val responses = mutableMapOf<String, McpJsonRpcResponse>()
    private var shouldFail = false
    private var failureMessage = "Simulated transport failure"

    init {
        // Default responses for standard MCP methods
        responses["initialize"] = McpJsonRpcResponse(
            id = "",
            result = mapOf("protocolVersion" to "2024-11-05", "capabilities" to emptyMap<String, Any>())
        )
        responses["tools/list"] = McpJsonRpcResponse(
            id = "",
            result = mapOf("tools" to emptyList<Any>())
        )
        responses["resources/list"] = McpJsonRpcResponse(
            id = "",
            result = mapOf("resources" to emptyList<Any>())
        )
        responses["prompts/list"] = McpJsonRpcResponse(
            id = "",
            result = mapOf("prompts" to emptyList<Any>())
        )
    }

    override val isAvailable: Boolean = true

    fun addResponse(method: String, response: McpJsonRpcResponse) {
        responses[method] = response
    }

    fun setFailure(message: String = "Simulated transport failure") {
        shouldFail = true
        failureMessage = message
    }

    fun clearFailure() {
        shouldFail = false
    }

    override suspend fun connect(config: McpServerConfig): Result<McpConnection> {
        if (shouldFail) return Result.failure(McpTransportException(failureMessage))
        return Result.success(
            McpConnection(
                serverId = config.id,
                capabilities = emptyList()
            )
        )
    }

    override suspend fun send(
        connection: McpConnection,
        request: McpJsonRpcRequest
    ): Result<McpJsonRpcResponse> {
        if (shouldFail) return Result.failure(McpTransportException(failureMessage))
        val response = responses[request.method]
            ?: McpJsonRpcResponse(
                id = request.id,
                error = McpJsonRpcError(
                    code = -32601,
                    message = "Method not found: ${request.method}"
                )
            )
        return Result.success(response)
    }

    override suspend fun close(connection: McpConnection) {
        // No-op for in-memory transport
    }
}

/** Exception thrown by MCP transport operations. */
open class McpTransportException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** Exception thrown when an MCP server returns an error. */
class McpServerErrorException(
    val code: Int,
    override val message: String,
    val data: Any? = null
) : RuntimeException(message)
