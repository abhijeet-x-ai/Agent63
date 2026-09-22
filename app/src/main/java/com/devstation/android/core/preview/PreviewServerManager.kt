package com.devstation.android.core.preview

import com.devstation.android.core.agent.SecretRedactor
import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.security.policy.FilesystemSandbox
import com.devstation.android.core.security.policy.SandboxOperation
import com.devstation.android.core.security.policy.TerminalSecurityPolicy
import com.devstation.android.core.security.policy.AuditDecision
import com.devstation.android.core.security.policy.SecurityAuditLogger
import com.devstation.android.core.security.policy.SecurityEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 9 §11–§28: preview server lifecycle manager.
 *
 * Security invariants:
 * - Every start command passes the existing [TerminalSecurityPolicy] (§13/§14) — no preview bypass.
 * - The working directory must resolve inside the project via [FilesystemSandbox] (§15).
 * - The process environment is sanitized; secret-looking names are rejected (§16/§17).
 * - Default binding is loopback; external interfaces are refused at this layer (§20).
 * - Ports are allocated and owned through [PreviewPortManager] (§18/§19).
 * - Logs are bounded and redacted (§22).
 * - Restart attempts are bounded; crashes surface as CRASHED, never silent infinite restart (§21/§55).
 * - Cross-project access to stop/status/logs is refused (§38/§53).
 * - Every lifecycle transition is audited (§50).
 */
class PreviewServerManager(
    private val portManager: PreviewPortManager,
    private val audit: SecurityAuditLogger,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    private val terminalPolicy: TerminalSecurityPolicy = TerminalSecurityPolicy(),
    private val sandbox: FilesystemSandbox = FilesystemSandbox(),
    private val startupTimeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS,
    private val maxLogEntries: Int = MAX_LOG_ENTRIES,
    private val maxRestarts: Int = MAX_RESTARTS,
    /** Test seam: launcher override so JVM tests can use real processes without Android paths. */
    private val processLauncher: (argv: List<String>, workingDir: File, env: Map<String, String>) -> Process =
        { argv, workingDir, env ->
            val builder = ProcessBuilder(argv)
            builder.directory(workingDir)
            val e = builder.environment()
            e.clear()
            e.putAll(env)
            builder.start()
        }
) {

    private val _servers = MutableStateFlow<Map<String, PreviewServer>>(emptyMap())
    val servers: StateFlow<Map<String, PreviewServer>> = _servers.asStateFlow()

    private val _logs = MutableStateFlow<Map<String, List<PreviewLogEntry>>>(emptyMap())
    val logs: StateFlow<Map<String, List<PreviewLogEntry>>> = _logs.asStateFlow()

    private val processes = ConcurrentHashMap<String, Process>()
    private val logJobs = ConcurrentHashMap<String, List<Job>>()

    companion object {
        const val DEFAULT_STARTUP_TIMEOUT_MS = 30_000L
        const val MAX_LOG_ENTRIES = 1_000
        const val MAX_RESTARTS = 3
        const val MAX_LOG_LINE_CHARS = 2_000

        const val LOOPBACK_HOST = "127.0.0.1"

        /**
         * §20: default binding is loopback only. External interfaces (0.0.0.0, LAN IPs,
         * hostnames) require an explicit security decision via `allowExternalBind`.
         */
        fun validateBindHost(host: String): Result<String> {
            val normalized = host.trim().lowercase().trim('[', ']')
            return when {
                normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1" ->
                    Result.success(LOOPBACK_HOST)
                else -> Result.failure(
                    SecurityException(
                        "Preview servers may only bind to loopback. '$host' requires an explicit external-bind decision."
                    )
                )
            }
        }

        val SENSITIVE_ENV_SUBSTRINGS = listOf(
            "API_KEY", "TOKEN", "SECRET", "PASSWORD", "PRIVATE_KEY", "CREDENTIAL",
            "AUTH", "ACCESS_KEY", "SESSION_KEY", "OAUTH"
        )

        /**
         * §16/§17: pure environment classifier — returns null when a variable looks like a
         * secret; otherwise the safe allow-listed subset. Usable without an instance.
         */
        fun sanitizeEnvironment(custom: Map<String, String>): Map<String, String>? {
            val env = mutableMapOf(
                "PATH" to "/system/bin:/system/xbin",
                "HOME" to "/home/devstation"
            )
            for ((key, value) in custom) {
                val upper = key.uppercase()
                if (SENSITIVE_ENV_SUBSTRINGS.any { upper.contains(it) }) return null
                if (SAFE_ENV_PATTERN.matches(key) && value.length <= 2_000) {
                    env[key] = value
                }
                // Unknown variable names are dropped, not passed — allow-list, not block-list.
            }
            return env
        }

        /** The complete safe dev-variable allow-list shape (§17). */
        val SAFE_ENV_PATTERN = Regex("""(PORT|HOST|NODE_ENV|PUBLIC_[A-Za-z0-9_]+|BROWSER|CI)""")

        /**
         * §15: system locations that are never acceptable as a preview working directory.
         * Projects legitimately live inside app-private storage (`/data/data/<pkg>/…`), so
         * `/data` itself is NOT listed — only the system/subsystem subtrees where a server
         * process has no legitimate reason to run.
         */
        val SYSTEM_DIR_PREFIXES = listOf(
            "/proc", "/sys", "/dev", "/vendor", "/system", "/apex", "/etc", "/root",
            "/data/misc", "/data/system", "/data/local", "/data/user", "/data/app",
            "/sbin", "/bin", "/usr", "/lib", "/lib64", "/opt", "/boot"
        )

        /** Pure classifier: true when [path] canonicalizes into a system subtree (§15). */
        fun isSystemWorkingDirectory(path: String): Boolean {
            val canonical = runCatching { java.io.File(path).canonicalPath }.getOrDefault(path)
            return SYSTEM_DIR_PREFIXES.any { canonical == it || canonical.startsWith("$it/") }
        }
    }

    // ---- environment (§16/§17) ----

    /** Instance delegate to the pure companion sanitizer. */
    fun sanitizedEnvironment(custom: Map<String, String>): Map<String, String>? =
        sanitizeEnvironment(custom)

    // ---- start (§13/§14) ----

    /**
     * Start a preview server. Returns the server on success. [allowExternalBind] is a
     * user-only escalation flag — agent tools never set it.
     */
    suspend fun start(
        projectId: String,
        projectName: String,
        command: String,
        arguments: List<String> = emptyList(),
        workingDirectory: String,
        requestedPort: Int,
        environment: Map<String, String> = emptyMap(),
        owner: PreviewOwner = PreviewOwner.USER,
        allowExternalBind: Boolean = false,
        readinessPath: String? = null
    ): Result<PreviewServer> {
        if (_servers.value.values.any { it.projectId == projectId && it.state in ACTIVE_STATES }) {
            return Result.failure(IllegalStateException("This project already has a running preview server."))
        }

        // §14.1: the working directory itself must be a real, writable project location —
        // never a system subtree (the sandbox only enforces containment *relative to* the root).
        if (isSystemWorkingDirectory(workingDirectory)) {
            audit.log(
                type = SecurityEventType.TOOL_BLOCKED,
                decision = AuditDecision.BLOCKED,
                summary = "Preview start blocked: system working directory ${SecretRedactor.redact(workingDirectory)}"
            )
            return Result.failure(
                SecurityException("Preview servers cannot run from a system location: '$workingDirectory'.")
            )
        }

        // §14.1–2: working directory containment through the existing sandbox.
        val root = File(workingDirectory)
        val dirResolution = sandbox.resolve(root, ".", SandboxOperation.READ)
        val resolvedDir = when (dirResolution) {
            is FilesystemSandbox.Resolution.Allowed -> dirResolution.file
            is FilesystemSandbox.Resolution.Rejected ->
                return Result.failure(SecurityException(dirResolution.reason))
        }

        // §14.3–5: terminal policy classification.
        val commandLine = (listOf(command) + arguments).joinToString(" ")
        val assessment = terminalPolicy.assess(commandLine, resolvedDir, guest = true)
        if (assessment.blockedReason != null) {
            audit.log(
                type = SecurityEventType.TOOL_BLOCKED,
                decision = AuditDecision.BLOCKED,
                summary = "Preview start blocked: ${SecretRedactor.redact(commandLine)}"
            )
            return Result.failure(SecurityException(assessment.blockedReason))
        }

        // §14.6: environment.
        val env = sanitizedEnvironment(environment)
            ?: return Result.failure(SecurityException("Preview environment contains secret-looking variable names."))

        // §14.5/§18: port.
        val serverId = java.util.UUID.randomUUID().toString()
        val port = portManager.allocate(projectId, serverId, requestedPort).getOrElse {
            return Result.failure(it)
        }

        // §20: binding — loopback only unless a user explicitly escalated.
        val host = if (allowExternalBind) "0.0.0.0" else LOOPBACK_HOST
        if (allowExternalBind) {
            audit.log(
                type = SecurityEventType.SECURITY_POLICY_CHANGED,
                decision = AuditDecision.RECORDED,
                summary = "Preview server bound to an external interface (user-approved): port $port"
            )
        }

        var server = PreviewServer(
            id = serverId,
            projectId = projectId,
            projectName = projectName,
            command = command,
            arguments = arguments,
            workingDirectory = resolvedDir.absolutePath,
            port = port,
            host = host,
            state = PreviewServerState.STARTING,
            owner = owner,
            readinessUrl = readinessPath?.let { "http://localhost:$port$it" } ?: "http://localhost:$port/"
        )
        _servers.value = _servers.value + (serverId to server)
        setLogs(serverId, emptyList())
        appendLog(serverId, PreviewLogEntry.Stream.SYSTEM, "Starting: ${SecretRedactor.redact(commandLine)}")

        // §14.7–9: launch.
        val process = try {
            processLauncher(listOf(command) + arguments, resolvedDir, env + mapOf("PORT" to port.toString()))
        } catch (e: Exception) {
            portManager.release(serverId)
            fail(server, "Failed to start: ${e.message ?: "unknown error"}")
            return Result.failure(e)
        }
        processes[serverId] = process
        startLogDrain(serverId, process)

        // §26/§27: bounded readiness detection.
        val ready = withTimeoutOrNull(startupTimeoutMs) {
            awaitReadiness(port)
        } == true
        server = if (ready) {
            server.copy(state = PreviewServerState.RUNNING, startedAt = System.currentTimeMillis())
        } else {
            stopInternal(serverId, markCrashed = false)
            fail(
                server.copy(state = PreviewServerState.FAILED),
                "Server did not become ready within ${startupTimeoutMs / 1000}s. Check the console output."
            )
            return Result.failure(IllegalStateException("Preview server failed to become ready."))
        }
        _servers.value = _servers.value + (serverId to server)
        appendLog(serverId, PreviewLogEntry.Stream.SYSTEM, "Ready on http://localhost:$port")

        audit.log(
            type = SecurityEventType.PROCESS_STARTED,
            decision = AuditDecision.ALLOWED,
            summary = "Preview server started (project $projectId, port $port): ${SecretRedactor.redact(commandLine)}"
        )
        return Result.success(server)
    }

    /** §26: poll the port, then probe HTTP. */
    private suspend fun awaitReadiness(port: Int): Boolean {
        var portOpen = false
        while (true) {
            if (!portOpen) {
                portOpen = probeConnect(port)
            }
            if (portOpen) {
                if (httpProbe(port)) return true
            }
            kotlinx.coroutines.delay(500)
        }
    }

    private fun probeConnect(port: Int): Boolean = try {
        java.net.Socket("127.0.0.1", port).use { true }
    } catch (e: Exception) {
        false
    }

    private fun httpProbe(port: Int): Boolean = try {
        val conn = URI("http://127.0.0.1:$port/").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 1_000
        conn.readTimeout = 1_000
        conn.requestMethod = "GET"
        val ok = conn.responseCode in 200..499 // any HTTP response means the server is up
        conn.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    // ---- stop / restart (§21) ----

    suspend fun stop(serverId: String, requesterProjectId: String? = null): Result<Unit> {
        val server = _servers.value[serverId]
            ?: return Result.failure(IllegalStateException("Preview server not found."))
        // §38/§53: only the owning project may stop the server.
        if (requesterProjectId != null && requesterProjectId != server.projectId) {
            audit.log(
                type = SecurityEventType.PERMISSION_DENIED,
                decision = AuditDecision.DENIED,
                summary = "Cross-project preview stop denied (project $requesterProjectId → ${server.projectId})"
            )
            return Result.failure(SecurityException("This preview server belongs to another project."))
        }
        stopInternal(serverId, markCrashed = false)
        audit.log(
            type = SecurityEventType.PROCESS_TERMINATED,
            decision = AuditDecision.RECORDED,
            summary = "Preview server stopped (project ${server.projectId}, port ${server.port})"
        )
        return Result.success(Unit)
    }

    suspend fun restart(serverId: String, requesterProjectId: String? = null): Result<PreviewServer> {
        val server = _servers.value[serverId]
            ?: return Result.failure(IllegalStateException("Preview server not found."))
        if (requesterProjectId != null && requesterProjectId != server.projectId) {
            return Result.failure(SecurityException("This preview server belongs to another project."))
        }
        if (server.restartCount >= maxRestarts) {
            return Result.failure(IllegalStateException("Restart limit reached ($maxRestarts). Start the server manually."))
        }
        stopInternal(serverId, markCrashed = false)
        kotlinx.coroutines.delay(300)
        val result = start(
            projectId = server.projectId,
            projectName = server.projectName,
            command = server.command,
            arguments = server.arguments,
            workingDirectory = server.workingDirectory,
            requestedPort = server.port,
            environment = emptyMap(),
            owner = server.owner,
            allowExternalBind = server.host != LOOPBACK_HOST,
            readinessPath = server.readinessUrl?.substringAfter("/localhost:${server.port}", "")?.ifBlank { null }
        )
        result.onSuccess { restarted ->
            _servers.value = _servers.value + (restarted.id to restarted.copy(restartCount = server.restartCount + 1))
        }
        return result
    }

    private fun stopInternal(serverId: String, markCrashed: Boolean) {
        logJobs.remove(serverId)?.forEach { it.cancel() }
        processes.remove(serverId)?.let { process ->
            runCatching {
                process.destroy()
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            }
        }
        portManager.release(serverId)
        val current = _servers.value[serverId] ?: return
        _servers.value = _servers.value + (serverId to current.copy(
            state = if (markCrashed) PreviewServerState.CRASHED else PreviewServerState.STOPPED
        ))
    }

    private fun fail(server: PreviewServer, message: String) {
        _servers.value = _servers.value + (server.id to server.copy(state = PreviewServerState.FAILED, lastError = message))
        appendLog(server.id, PreviewLogEntry.Stream.SYSTEM, message)
    }

    // ---- log capture (§22) ----

    private fun startLogDrain(serverId: String, process: Process) {
        val jobs = mutableListOf<Job>()
        jobs.add(scope.launch(Dispatchers.IO) { drain(serverId, process.inputStream, PreviewLogEntry.Stream.STDOUT) })
        jobs.add(scope.launch(Dispatchers.IO) { drain(serverId, process.errorStream, PreviewLogEntry.Stream.STDERR) })
        // Crash detection: when the process exits unexpectedly, mark CRASHED (§21/§55).
        jobs.add(scope.launch(Dispatchers.IO) {
            runCatching { process.waitFor() }
            val current = _servers.value[serverId] ?: return@launch
            if (current.state == PreviewServerState.RUNNING || current.state == PreviewServerState.STARTING) {
                _servers.value = _servers.value + (serverId to current.copy(
                    state = PreviewServerState.CRASHED,
                    lastError = "Preview server stopped unexpectedly (exit code ${runCatching { process.exitValue() }.getOrNull()})."
                ))
                appendLog(serverId, PreviewLogEntry.Stream.SYSTEM, "Process exited unexpectedly.")
                audit.log(
                    type = SecurityEventType.PROCESS_TERMINATED,
                    decision = AuditDecision.RECORDED,
                    summary = "Preview server crashed (project ${current.projectId}, port ${current.port})"
                )
            }
        })
        logJobs[serverId] = jobs
    }

    private suspend fun drain(serverId: String, stream: java.io.InputStream, streamKind: PreviewLogEntry.Stream) {
        try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    appendLog(serverId, streamKind, line.take(MAX_LOG_LINE_CHARS))
                    line = reader.readLine()
                }
            }
        } catch (_: Exception) {
            // Stream closed by termination.
        }
    }

    private fun appendLog(serverId: String, stream: PreviewLogEntry.Stream, text: String) {
        val entry = PreviewLogEntry(text = SecretRedactor.redact(text), stream = stream)
        _logs.value = _logs.value + (serverId to run {
            val current = _logs.value[serverId] ?: emptyList()
            (current + entry).takeLast(maxLogEntries)
        })
    }

    private fun setLogs(serverId: String, entries: List<PreviewLogEntry>) {
        _logs.value = _logs.value + (serverId to entries)
    }

    /** §23: clear a server's console. */
    fun clearLogs(serverId: String) = setLogs(serverId, emptyList())

    // ---- queries with ownership checks (§38/§53) ----

    fun serverFor(serverId: String, requesterProjectId: String? = null): Result<PreviewServer> {
        val server = _servers.value[serverId]
            ?: return Result.failure(IllegalStateException("Preview server not found."))
        if (requesterProjectId != null && requesterProjectId != server.projectId) {
            return Result.failure(SecurityException("This preview server belongs to another project."))
        }
        return Result.success(server)
    }

    fun logsFor(serverId: String, requesterProjectId: String? = null): Result<List<PreviewLogEntry>> {
        return serverFor(serverId, requesterProjectId).map { _logs.value[serverId] ?: emptyList() }
    }

    fun serverForProject(projectId: String): PreviewServer? =
        _servers.value.values.firstOrNull { it.projectId == projectId && it.state in ACTIVE_STATES }
            ?: _servers.value.values.lastOrNull { it.projectId == projectId }

    fun runningPort(projectId: String): Int? =
        serverForProject(projectId)?.takeIf { it.state == PreviewServerState.RUNNING }?.port

    /** §56: stop only DevStation-owned preview processes (never the user's terminals). */
    fun shutdownAll() {
        _servers.value.keys.toList().forEach { stopInternal(it, markCrashed = false) }
    }

    private val ACTIVE_STATES = setOf(
        PreviewServerState.STARTING, PreviewServerState.RUNNING, PreviewServerState.STOPPING
    )
}
