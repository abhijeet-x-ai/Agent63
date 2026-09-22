package com.devstation.android.core.mcp

import com.devstation.android.core.security.policy.TerminalSecurityPolicy
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 8.1 §5–§12: production MCP STDIO transport.
 *
 * Security invariants:
 * - The server command is assessed by the existing Phase 7 [TerminalSecurityPolicy] before launch;
 *   a command whose assessment is structurally blocked is refused (fail closed).
 * - The process is started as a direct argv array — never `sh -c <string>` — so shell metacharacter
 *   injection through the configuration cannot occur.
 * - The child process environment is cleared; only safe development variables plus explicitly
 *   approved, non-sensitive config entries are passed (§8). DevStation's own environment — where
 *   API keys live — is never inherited.
 * - stdout/stderr are bounded; request/response correlation is by JSON-RPC id (§21); every wait is
 *   cancellable (§22) and every process is owned by this transport instance (§11) and terminated on
 *   close (§12).
 */
class McpStdioTransport(
    /** The single app-controlled directory MCP server processes may run in. */
    private val workingDir: File,
    private val terminalPolicy: TerminalSecurityPolicy = TerminalSecurityPolicy(),
    private val connectTimeoutMs: Long = DEFAULT_MCP_CONNECT_TIMEOUT_MS,
    private val requestTimeoutMs: Long = DEFAULT_MCP_REQUEST_TIMEOUT_MS,
    private val startupTimeoutMs: Long = STARTUP_TIMEOUT_MS,
    private val maxStreamChars: Long = MAX_STREAM_CHARS
) : McpTransport {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val sessions = ConcurrentHashMap<String, StdioSession>()
    private val registry = McpProcessRegistry()

    override val isAvailable: Boolean = true

    /** Processes currently owned by this transport, per server (§11 diagnostics/audit). */
    fun ownedProcesses(): Map<String, Int> = registry.ownedCounts()

    override suspend fun connect(config: McpServerConfig): Result<McpConnection> {
        if (sessions.containsKey(config.id)) {
            return Result.failure(McpTransportException("A session for this server already exists."))
        }

        // §6: every STDIO server command passes the terminal policy before launch.
        val commandLine = (listOf(config.command) + config.arguments).joinToString(" ")
        val assessment = terminalPolicy.assess(commandLine, workingDir, guest = true)
        if (assessment.blockedReason != null) {
            return Result.failure(McpSecurityRejection(assessment.blockedReason))
        }

        // §8: sanitized environment — no inheritance, no secrets.
        val env = sanitizedEnvironment(config)
        if (env == null) {
            return Result.failure(
                McpSecurityRejection(
                    "The server environment contains sensitive variable names. " +
                        "Use credential references instead."
                )
            )
        }

        val session = try {
            StdioSession.start(
                serverId = config.id,
                argv = listOf(config.command) + config.arguments,
                workingDir = workingDir,
                environment = env,
                registry = registry,
                maxStreamChars = maxStreamChars
            )
        } catch (e: Exception) {
            return Result.failure(McpTransportException("MCP server failed to start: ${e.message}", e))
        }
        sessions[config.id] = session

        // §10: bounded startup — a server that produces no working I/O in time is terminated.
        // A process that exits immediately (or the probe failing) is a clean connect failure,
        // never an exception escaping to the caller.
        val healthy = runCatching {
            withTimeoutOrNull(startupTimeoutMs) {
                session.awaitUsable()
                true
            } == true
        }.getOrElse { false }
        if (!healthy) {
            session.close()
            sessions.remove(config.id)
            return Result.failure(
                McpTransportException(
                    "MCP server did not start within ${startupTimeoutMs}ms " +
                        "or exited during startup."
                )
            )
        }
        return Result.success(McpConnection(serverId = config.id, capabilities = emptyList()))
    }

    override suspend fun send(
        connection: McpConnection,
        request: McpJsonRpcRequest
    ): Result<McpJsonRpcResponse> {
        val session = sessions[connection.serverId]
            ?: return Result.failure(McpTransportException("MCP server is not running."))
        if (!session.isAlive()) {
            return Result.failure(McpTransportException("MCP server process has exited."))
        }

        val response = withTimeoutOrNull(requestTimeoutMs) {
            session.request(request)
        } ?: run {
            session.abandon(request.id)
            return Result.failure(McpTransportException("MCP request timed out after ${requestTimeoutMs}ms."))
        }

        return Result.success(response)
    }

    override suspend fun close(connection: McpConnection) {
        sessions.remove(connection.serverId)?.close()
    }

    /** Terminate every process this transport owns (app shutdown / server removal). */
    fun shutdownAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    // ---- environment isolation (§8) ----

    /**
     * The complete environment a MCP server process receives. Returns null when any configured
     * variable looks like a secret — fail closed rather than guess.
     */
    internal fun sanitizedEnvironment(config: McpServerConfig): Map<String, String>? {
        if (config.hasSensitiveEnvironmentKeys()) return null
        val env = mutableMapOf(
            "PATH" to DEFAULT_PATH,
            "HOME" to workingDir.absolutePath
        )
        config.environment.forEach { (key, value) ->
            if (SENSITIVE_ENV_SUBSTRINGS.any { key.uppercase().contains(it) }) return null
            if (!ENV_KEY_PATTERN.matches(key) || value.length > MAX_ENV_VALUE_CHARS) return null
            env[key] = value
        }
        return env
    }

    private class StdioSession(
        val serverId: String,
        private val process: Process,
        private val stdin: OutputStreamWriter,
        private val stderrSink: StringBuilder,
        private val registry: McpProcessRegistry,
        private val maxStreamChars: Long
    ) {
        private val pending = ConcurrentHashMap<String, PendingRequest>()
        private val closed = AtomicBoolean(false)
        private var sawOutput = false

        class PendingRequest {
            @Volatile var response: McpJsonRpcResponse? = null
            @Volatile var error: Throwable? = null
        }

        fun awaitUsable() {
            // The usable signal is simply "the process is alive"; the protocol initialize
            // handshake in McpClient proves the rest. Bounded by the caller's startup timeout.
            return if (process.isAlive) Unit else throw McpTransportException("Process exited during startup.")
        }

        fun isAlive(): Boolean = process.isAlive && !closed.get()

        suspend fun request(request: McpJsonRpcRequest): McpJsonRpcResponse {
            val pendingEntry = PendingRequest()
            pending[request.id] = pendingEntry
            try {
                writeRequest(request)
                // Cancellable wait (§22): cancellation surfaces from the withTimeoutOrNull in send().
                while (true) {
                    pendingEntry.response?.let { return it }
                    pendingEntry.error?.let { throw it }
                    if (!process.isAlive) {
                        throw McpTransportException("MCP server process exited before responding.")
                    }
                    kotlinx.coroutines.delay(POLL_INTERVAL_MS)
                }
            } finally {
                pending.remove(request.id)
            }
        }

        /** Drop a pending entry after a timeout so a late response cannot complete a dead request. */
        fun abandon(requestId: String) {
            pending.remove(requestId)
        }

        private fun writeRequest(request: McpJsonRpcRequest) {
            val body = buildJsonObject {
                put("jsonrpc", request.jsonrpc)
                put("id", request.id)
                put("method", request.method)
                put("params", serializeParams(request.params))
            }
            synchronized(stdin) {
                stdin.write(body.toString())
                stdin.write("\n")
                stdin.flush()
            }
        }

        private fun serializeParams(params: Map<String, Any>): kotlinx.serialization.json.JsonElement =
            runCatching {
                Json.parseToJsonElement(Json.encodeToString(JsonObject.serializer(), toJsonObject(params)))
            }.getOrDefault(buildJsonObject { })

        private fun toJsonObject(map: Map<String, Any>): JsonObject = buildJsonObject {
            map.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Number -> put(key, value)
                    is Boolean -> put(key, value)
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        put(key, toJsonObject(value as Map<String, Any>))
                    }
                    is List<*> -> put(key, kotlinx.serialization.json.JsonArray(
                        value.map { v ->
                            when (v) {
                                is String -> kotlinx.serialization.json.JsonPrimitive(v)
                                is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                                is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                                else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
                            }
                        }
                    ))
                    else -> put(key, value.toString())
                }
            }
        }

        /** Read responses on a daemon thread; bounded buffers; id-correlated dispatch (§21). */
        private fun startReader() {
            val readerThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                        val buffer = CharArray(4096)
                        val line = StringBuilder()
                        while (!closed.get()) {
                            val read = reader.read(buffer)
                            if (read <= 0) break
                            for (i in 0 until read) {
                                val ch = buffer[i]
                                if (ch == '\n') {
                                    handleLine(line.toString())
                                    line.setLength(0)
                                } else {
                                    if (line.length < maxStreamChars) line.append(ch)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Stream closed by termination.
                }
            }.apply { isDaemon = true; name = "mcp-stdio-reader-$serverId" }
            readerThread.start()

            // Stderr drain: bounded, never delivered to the agent — diagnostics only.
            Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { reader ->
                        val buffer = CharArray(4096)
                        while (!closed.get()) {
                            val read = reader.read(buffer)
                            if (read <= 0) break
                            synchronized(stderrSink) {
                                val room = (maxStreamChars - stderrSink.length).toInt()
                                if (room > 0) stderrSink.append(buffer, 0, minOf(room, read))
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; name = "mcp-stdio-stderr-$serverId" }.start()
        }

        private fun handleLine(line: String) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return
            val parsed = runCatching { Json.parseToJsonElement(trimmed).jsonObjectSafe() }.getOrNull() ?: return
            val id = (parsed["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
            // §21: a response can only complete the pending request with the same id.
            val entry = pending[id] ?: return
            entry.response = runCatching { toResponse(parsed, id) }.getOrElse { e ->
                entry.error = McpTransportException("Malformed MCP response: ${e.message}", e)
                null
            } ?: return
        }

        private fun toResponse(parsed: JsonObject, id: String): McpJsonRpcResponse {
            val errorObj = parsed["error"] as? JsonObject
            val error = errorObj?.let {
                val code = (it["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: -1
                val message = (it["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "Server error"
                McpJsonRpcError(code = code, message = message)
            }
            return McpJsonRpcResponse(
                id = id,
                result = (parsed["result"] as? JsonObject)?.let { obj ->
                    obj.mapValues { (_, v) -> v.toString() }.toMutableMap<String, Any>()
                },
                error = error
            )
        }

        private fun kotlinx.serialization.json.JsonElement.jsonObjectSafe(): JsonObject? =
            this as? JsonObject

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { process.destroy() }
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            if (process.isAlive) runCatching { process.destroyForcibly() }
            registry.unregister(serverId, process)
        }

        companion object {
            const val POLL_INTERVAL_MS = 50L

            fun start(
                serverId: String,
                argv: List<String>,
                workingDir: File,
                environment: Map<String, String>,
                registry: McpProcessRegistry,
                maxStreamChars: Long
            ): StdioSession {
                val builder = ProcessBuilder(argv)
                builder.directory(workingDir)
                val env = builder.environment()
                env.clear()
                env.putAll(environment)
                val process = builder.start()
                registry.register(serverId, process)

                val stderrSink = StringBuilder()
                val session = StdioSession(
                    serverId = serverId,
                    process = process,
                    stdin = OutputStreamWriter(process.outputStream, Charsets.UTF_8),
                    stderrSink = stderrSink,
                    registry = registry,
                    maxStreamChars = maxStreamChars
                )
                session.startReader()
                return session
            }
        }
    }

    companion object {
        const val STARTUP_TIMEOUT_MS = 10_000L
        const val MAX_STREAM_CHARS = 256_000L
        const val MAX_ENV_VALUE_CHARS = 2_000
        const val DEFAULT_PATH = "/system/bin:/system/xbin"

        val ENV_KEY_PATTERN = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

        val SENSITIVE_ENV_SUBSTRINGS = listOf(
            "API_KEY", "TOKEN", "SECRET", "PASSWORD", "PRIVATE_KEY", "CREDENTIAL",
            "AUTH", "ACCESS_KEY", "SESSION_KEY", "OAUTH"
        )
    }
}

/**
 * §11: ownership registry for MCP server processes, keyed by server id. Only the transport that
 * started a process may terminate it; unrelated agents/tools never receive this registry.
 */
class McpProcessRegistry {
    private val owned = ConcurrentHashMap<String, MutableSet<Process>>()

    fun register(serverId: String, process: Process) {
        owned.computeIfAbsent(serverId) { ConcurrentHashMap.newKeySet<Process>() }.add(process)
    }

    fun unregister(serverId: String, process: Process) {
        owned[serverId]?.remove(process)
        if (owned[serverId]?.isEmpty() == true) owned.remove(serverId)
    }

    fun ownedCounts(): Map<String, Int> = owned.mapValues { (_, v) -> v.size }

    fun totalOwned(): Int = owned.values.sumOf { it.size }
}

/** Thrown when the security policy refuses an MCP transport operation (fail closed). */
class McpSecurityRejection(message: String) : McpTransportException(message)
