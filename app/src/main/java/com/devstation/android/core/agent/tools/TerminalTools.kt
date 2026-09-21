package com.devstation.android.core.agent.tools

import com.devstation.android.core.agent.AgentLoopLimits
import com.devstation.android.core.agent.CommandClassification
import com.devstation.android.core.agent.CommandClassifier
import com.devstation.android.core.agent.OutputLimiter
import com.devstation.android.core.agent.PathSandbox
import com.devstation.android.core.agent.SecretRedactor
import com.devstation.android.core.agent.Tool
import com.devstation.android.core.agent.ToolContext
import com.devstation.android.core.agent.ToolDefinition
import com.devstation.android.core.agent.ToolPermission
import com.devstation.android.core.agent.ToolResult
import com.devstation.android.core.agent.ToolRiskLevel
import com.devstation.android.core.ai.AIToolCall
import com.devstation.android.core.ai.AIToolParameter
import com.devstation.android.core.ai.AIToolParameterType
import com.devstation.android.future.runtime.LinuxProcessLauncher
import com.devstation.android.future.runtime.LinuxStoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Normalized non-interactive command result. */
data class CommandRunResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val truncated: Boolean,
    val durationMs: Long
)

/**
 * Non-interactive command execution.
 *
 * Agent commands NEVER reuse a user terminal session (Phase 6 §57): each call spawns its own
 * dedicated process owned by the task, so stopping the agent can never disturb the user's shell.
 */
interface CommandRunner {
    val environmentLabel: String

    suspend fun run(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputChars: Int,
        taskId: String,
        isCancelled: () -> Boolean
    ): CommandRunResult

    /** Kill every process owned by [taskId]. Returns how many were terminated. */
    fun terminate(taskId: String): Int
}

/**
 * Ownership registry for agent-spawned processes. Keyed by task id so `STOP AGENT` can never
 * touch anything the user started.
 */
class AgentProcessRegistry {

    private val owned = ConcurrentHashMap<String, MutableSet<Process>>()

    fun register(taskId: String, process: Process) {
        owned.computeIfAbsent(taskId) { ConcurrentHashMap.newKeySet() }.add(process)
    }

    fun unregister(taskId: String, process: Process) {
        owned[taskId]?.remove(process)
        if (owned[taskId]?.isEmpty() == true) owned.remove(taskId)
    }

    fun ownedCount(taskId: String): Int = owned[taskId]?.size ?: 0

    fun terminate(taskId: String): Int {
        val processes = owned.remove(taskId) ?: return 0
        var killed = 0
        processes.forEach { process ->
            runCatching {
                process.destroy()
                process.destroyForcibly()
                killed++
            }
        }
        return killed
    }

    fun terminateAll(): Int = owned.keys.toList().sumOf { terminate(it) }
}

/** Base runner: bounded output, polling timeout, cancellation, owned-process registry. */
abstract class BaseProcessCommandRunner(
    private val registry: AgentProcessRegistry
) : CommandRunner {

    protected abstract fun startProcess(command: String, workingDir: File): Process

    override suspend fun run(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputChars: Int,
        taskId: String,
        isCancelled: () -> Boolean
    ): CommandRunResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val effectiveDir = if (workingDir.exists() && workingDir.isDirectory) workingDir else File(System.getProperty("java.io.tmpdir") ?: ".")

        val process = try {
            startProcess(command, effectiveDir)
        } catch (e: Exception) {
            return@withContext CommandRunResult(
                exitCode = null,
                output = "Failed to start command: ${e.message ?: "unknown error"}",
                timedOut = false,
                cancelled = false,
                truncated = false,
                durationMs = System.currentTimeMillis() - startedAt
            )
        }
        registry.register(taskId, process)

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var truncated = false
        val lock = Any()

        try {
            coroutineScope {
                val outJob = launch { drain(process.inputStream, stdout, maxOutputChars, lock) { truncated = true } }
                val errJob = launch { drain(process.errorStream, stderr, maxOutputChars, lock) { truncated = true } }

                val deadline = startedAt + timeoutMs
                var exitCode: Int? = null
                var timedOut = false
                var cancelled = false

                while (true) {
                    if (isCancelled()) {
                        cancelled = true
                        terminateProcess(process)
                        break
                    }
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) {
                        timedOut = true
                        terminateProcess(process)
                        break
                    }
                    // A blocking waitFor() cannot be interrupted by a coroutine timeout, so wait
                    // with a real deadline and poll for cancellation between waits (§47).
                    val waitMs = minOf(POLL_INTERVAL_MS, remaining).coerceAtLeast(1L)
                    if (process.waitFor(waitMs, TimeUnit.MILLISECONDS)) {
                        exitCode = process.exitValue()
                        break
                    }
                }

                // Give readers a brief moment to flush what was already produced.
                withTimeoutOrNull(500) {
                    outJob.join()
                    errJob.join()
                }
                outJob.cancel()
                errJob.cancel()

                val combined = buildString {
                    if (stdout.isNotEmpty()) append(stdout)
                    if (stderr.isNotEmpty()) {
                        if (isNotEmpty() && !endsWith("\n")) append('\n')
                        append(stderr)
                    }
                }
                val bounded = OutputLimiter.truncate(combined, maxOutputChars)
                val body = when {
                    cancelled -> missingRunnerMessage(bounded, "Command cancelled.")
                    timedOut -> missingRunnerMessage(bounded, "Command exceeded the ${timeoutMs}ms timeout and was terminated.")
                    else -> bounded
                }

                CommandRunResult(
                    exitCode = exitCode,
                    output = body,
                    timedOut = timedOut,
                    cancelled = cancelled,
                    truncated = truncated || bounded != combined,
                    durationMs = System.currentTimeMillis() - startedAt
                )
            }
        } finally {
            registry.unregister(taskId, process)
            if (process.isAlive) terminateProcess(process)
        }
    }

    override fun terminate(taskId: String): Int = registry.terminate(taskId)

    private fun missingRunnerMessage(body: String, note: String): String =
        if (body.isBlank()) note else "$body\n$note"

    private fun terminateProcess(process: Process) {
        runCatching {
            process.destroy()
            process.destroyForcibly()
        }
    }

    private suspend fun drain(
        stream: InputStream,
        sink: StringBuilder,
        maxChars: Int,
        lock: Any,
        onTruncate: () -> Unit
    ) {
        try {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    synchronized(lock) {
                        val room = maxChars - sink.length
                        if (room > 0) {
                            sink.append(buffer, 0, minOf(room, read))
                        }
                        if (read > room) onTruncate()
                    }
                }
            }
        } catch (_: Exception) {
            // Stream closed by termination; nothing to report.
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 200L
    }
}

/** Runs commands inside the Phase 3 Linux userspace, mapped to the project's `/workspace`. */
class LinuxCommandRunner(
    private val launcher: LinuxProcessLauncher,
    private val storagePaths: LinuxStoragePaths,
    registry: AgentProcessRegistry
) : BaseProcessCommandRunner(registry) {

    override val environmentLabel = "Linux /workspace"

    override fun startProcess(command: String, workingDir: File): Process =
        launcher.launchProcess(
            command = listOf("/bin/sh", "-c", command),
            hostWorkspaceDir = workingDir,
            customEnv = linuxAgentEnvironment()
        )
}

/**
 * Environment passed into the Linux guest. Deliberately minimal: DevStation never forwards its
 * own process environment (which is where API keys live) into an agent command.
 */
internal fun linuxAgentEnvironment(): Map<String, String> = mapOf("HOME" to "/home/devstation")

/**
 * Fallback for devices without the Linux runtime installed. Only used when the user explicitly
 * enables it; commands run in the app's own sandbox with the app's own permissions.
 */
class AndroidShellCommandRunner(
    registry: AgentProcessRegistry,
    private val shellPath: String = "/system/bin/sh"
) : BaseProcessCommandRunner(registry) {

    override val environmentLabel = "Android shell (fallback)"

    override fun startProcess(command: String, workingDir: File): Process {
        val builder = ProcessBuilder(listOf(shellPath, "-c", command))
        builder.directory(workingDir)
        val env = builder.environment()
        // Never inherit DevStation's process environment (credentials live there).
        env.clear()
        env.putAll(sanitizedShellEnvironment(workingDir))
        return builder.start()
    }
}

/** The complete environment the fallback shell receives — nothing else is ever inherited. */
internal fun sanitizedShellEnvironment(workingDir: File): Map<String, String> = mapOf(
    "PATH" to "/system/bin:/system/xbin",
    "HOME" to workingDir.absolutePath
)

/**
 * run_terminal_command — the highest-risk tool.
 *
 * The command is never string-concatenated into a shell invocation built by the agent layer: it
 * is passed as a single argv element to a controlled process interface, and its risk is
 * classified so the permission policy (not a keyword blacklist) decides about approval.
 */
class RunTerminalCommandTool(
    private val linuxRunner: CommandRunner?,
    private val androidRunner: CommandRunner,
    private val linuxAvailable: () -> Boolean,
    private val allowAndroidFallback: () -> Boolean,
    private val registry: AgentProcessRegistry,
    private val limits: AgentLoopLimits
) : Tool {

    override val definition = ToolDefinition(
        name = "run_terminal_command",
        description = "Run a non-interactive development command in the project workspace and " +
            "return its output. Prefer read-only commands. Destructive commands always require " +
            "explicit approval. Commands run in a dedicated process, not in the user's terminal.",
        parameters = listOf(
            AIToolParameter("command", AIToolParameterType.STRING, "The command to run"),
            AIToolParameter("workingDirectory", AIToolParameterType.STRING, "Project-relative working directory (default: project root)", required = false),
            AIToolParameter("timeoutMs", AIToolParameterType.INTEGER, "Timeout in milliseconds", required = false)
        ),
        riskLevel = ToolRiskLevel.HIGH,
        permission = ToolPermission.ASK,
        classificationDriven = true
    )

    override fun summarize(args: JsonObject) = "Run `${commandOf(args)}`"

    /** Classification is computed by the executor and passed to the permission manager. */
    fun classify(args: JsonObject): CommandClassification = CommandClassifier.classify(commandOf(args))

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val command = commandOf(args)
            if (command.isBlank()) return@withContext ToolResult.Error("run_terminal_command", "A 'command' is required.")
            if (command.length > MAX_COMMAND_LENGTH) {
                return@withContext ToolResult.Error("run_terminal_command", "The command is too long.")
            }

            val workingDir = try {
                val rawDir = (args["workingDirectory"] as? JsonPrimitive)?.content
                if (rawDir.isNullOrBlank()) context.workingDirectory
                else PathSandbox.resolve(context.projectRoot, rawDir)
            } catch (e: PathSandbox.PathRejected) {
                return@withContext ToolResult.Error("run_terminal_command", e.message ?: "Working directory rejected.")
            }
            if (!workingDir.exists() || !workingDir.isDirectory) {
                return@withContext ToolResult.Error("run_terminal_command", "Working directory does not exist.")
            }

            val classification = classify(args)
            val (runner, label) = when {
                linuxAvailable() && linuxRunner != null -> linuxRunner to linuxRunner.environmentLabel
                allowAndroidFallback() -> androidRunner to androidRunner.environmentLabel
                else -> return@withContext ToolResult.Error(
                    "run_terminal_command",
                    "The Linux runtime is not installed, and the Android shell fallback is disabled. " +
                        "Install the Linux environment in Linux Environment settings, or enable the " +
                        "fallback in AI Settings."
                )
            }

            val requestedTimeout = (args["timeoutMs"] as? JsonPrimitive)?.intOrNull?.toLong()
            val defaultTimeout = defaultTimeoutFor(classification)
            val timeoutMs = (requestedTimeout ?: defaultTimeout).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

            val result = runner.run(
                command = command,
                workingDir = workingDir,
                timeoutMs = timeoutMs,
                maxOutputChars = limits.maxToolOutputChars,
                taskId = context.taskId,
                isCancelled = { context.isCancelled() }
            )

            if (result.cancelled) {
                return@withContext ToolResult.Cancelled("run_terminal_command", "Command cancelled.")
            }
            if (result.timedOut) {
                return@withContext ToolResult.Timeout("run_terminal_command", timeoutMs)
            }

            val exit = result.exitCode
            val header = buildString {
                append("$command\n")
                append("environment: $label\n")
                append("directory: ${runCatching { workingDir.relativeTo(context.projectRoot).path }.getOrDefault(".")}\n")
                append("exitCode: ${exit ?: "unknown"}\n")
                append("durationMs: ${result.durationMs}\n")
            }
            val output = SecretRedactor.redact(result.output)
            val body = "$header${if (output.isBlank()) "(no output)" else output}"

            if (exit != null && exit != 0) {
                return@withContext ToolResult.Error(
                    "run_terminal_command",
                    "${AgentLabels.TERMINAL_OUTPUT} $body"
                )
            }
            ToolResult.Success(
                "run_terminal_command",
                "${AgentLabels.TERMINAL_OUTPUT} $body",
                mapOf(
                    "exitCode" to (exit?.toString() ?: "unknown"),
                    "truncated" to result.truncated.toString(),
                    "category" to classification.category.name,
                    "risk" to classification.riskLevel.name
                )
            )
        }

    /** Long-running installs/builds get more room, but never an infinite timeout. */
    private fun defaultTimeoutFor(classification: CommandClassification): Long = when (classification.category) {
        com.devstation.android.core.agent.CommandCategory.INSTALL_PACKAGE -> 300_000L
        com.devstation.android.core.agent.CommandCategory.NETWORK -> 180_000L
        com.devstation.android.core.agent.CommandCategory.DESTRUCTIVE -> 60_000L
        com.devstation.android.core.agent.CommandCategory.MODIFY_PROJECT -> 180_000L
        com.devstation.android.core.agent.CommandCategory.READ_ONLY -> 60_000L
    }

    private fun commandOf(args: JsonObject): String =
        (args["command"] as? JsonPrimitive)?.content?.trim().orEmpty()

    private companion object {
        const val MIN_TIMEOUT_MS = 5_000L
        const val MAX_TIMEOUT_MS = 900_000L
        const val MAX_COMMAND_LENGTH = 8_000
    }
}
