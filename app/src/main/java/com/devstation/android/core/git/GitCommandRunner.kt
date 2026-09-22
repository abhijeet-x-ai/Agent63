package com.devstation.android.core.git

import com.devstation.android.core.agent.SecretRedactor
import com.devstation.android.core.security.policy.AuditDecision
import com.devstation.android.core.security.policy.SecurityAuditLogger
import com.devstation.android.core.security.policy.SecurityEventType
import com.devstation.android.future.runtime.LinuxProcessLauncher
import com.devstation.android.future.runtime.LinuxRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Phase 10 §3/§4/§10/§43/§44: Secure Git Command Runner.
 *
 * Invariants:
 * 1. Executes structured argument lists directly (NEVER executes `sh -c "git ..."`).
 * 2. Neutralizes unapproved repository hooks by passing `-c core.hooksPath=/dev/null` by default.
 * 3. Enforces bounded execution (default 30s timeout) and bounded output buffer (max 1MB).
 * 4. Sanitizes process environment to ensure host secrets/tokens are never inherited.
 * 5. Redacts secrets from stdout, stderr, and audit logs.
 */
interface GitCommandRunner {
    fun checkAvailability(): GitAvailability

    suspend fun execute(
        repositoryDir: File,
        args: List<String>,
        environment: Map<String, String> = emptyMap(),
        allowHooks: Boolean = false,
        timeoutMs: Long = 30_000L,
        maxOutputBytes: Long = 1_000_000L
    ): Result<GitCommandResult>
}

class DefaultGitCommandRunner(
    private val linuxRuntimeManager: LinuxRuntimeManager? = null,
    private val linuxLauncher: LinuxProcessLauncher? = null,
    private val securityPolicy: GitSecurityPolicy = GitSecurityPolicy(),
    private val auditLogger: SecurityAuditLogger = SecurityAuditLogger.NoOp,
    /** Optional test launcher override for unit/integration tests without PRoot. */
    private val testProcessLauncher: ((List<String>, File, Map<String, String>) -> Process)? = null
) : GitCommandRunner {

    companion object {
        val SAFE_GIT_ENV = mapOf(
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_ASKPASS" to "/bin/echo",
            "LC_ALL" to "C",
            "LANG" to "C"
        )
    }

    override fun checkAvailability(): GitAvailability {
        // If a test launcher is injected, return available
        if (testProcessLauncher != null) {
            return GitAvailability.Available(version = "git version 2.43.0 (test-runner)", binaryPath = "/usr/bin/git")
        }

        val launcher = linuxLauncher
        if (launcher != null && launcher.isProotAvailable()) {
            val gitBin = File(launcher.findProotBinary()?.parentFile?.parentFile ?: File(""), "rootfs/usr/bin/git")
            if (gitBin.exists()) {
                return GitAvailability.Available(version = "git (Linux PRoot)", binaryPath = "/usr/bin/git")
            }
            return GitAvailability.NotInstalled("apk add git")
        }

        // Host system check (for developer desktop runs or unit test environments)
        val hostGit = findHostGitBinary()
        if (hostGit != null) {
            val versionResult = runCatching {
                val p = ProcessBuilder(hostGit.absolutePath, "--version").start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor(5, TimeUnit.SECONDS)
                out
            }.getOrNull() ?: "git (host)"
            return GitAvailability.Available(version = versionResult, binaryPath = hostGit.absolutePath)
        }

        val runtime = linuxRuntimeManager
        if (runtime != null && !runtime.isInstalled()) {
            return GitAvailability.RuntimeUnavailable
        }

        return GitAvailability.NotInstalled("apk add git")
    }

    private fun findHostGitBinary(): File? {
        val pathEnv = System.getenv("PATH") ?: return null
        val separator = if (System.getProperty("os.name")?.lowercase()?.contains("win") == true) ";" else ":"
        for (dir in pathEnv.split(separator)) {
            val exe = if (System.getProperty("os.name")?.lowercase()?.contains("win") == true) "git.exe" else "git"
            val candidate = File(dir, exe)
            if (candidate.exists() && candidate.canExecute()) return candidate
        }
        return null
    }

    override suspend fun execute(
        repositoryDir: File,
        args: List<String>,
        environment: Map<String, String>,
        allowHooks: Boolean,
        timeoutMs: Long,
        maxOutputBytes: Long
    ): Result<GitCommandResult> = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()

        // Build command with security config overrides (§10/§17)
        val securityOverrides = securityPolicy.buildSecurityConfigOverrides(allowHooks)
        val fullGitArgs = listOf("git") + securityOverrides + args

        auditLogger.log(
            type = SecurityEventType.PROCESS_STARTED,
            decision = AuditDecision.ALLOWED,
            summary = "Git execution: git ${SecretRedactor.redact(args.joinToString(" "))}"
        )

        val cleanEnv = mutableMapOf<String, String>()
        cleanEnv.putAll(SAFE_GIT_ENV)
        cleanEnv["HOME"] = "/home/devstation"
        for ((k, v) in environment) {
            cleanEnv[k] = v
        }

        val process = runCatching {
            when {
                testProcessLauncher != null -> {
                    testProcessLauncher.invoke(fullGitArgs, repositoryDir, cleanEnv)
                }
                linuxLauncher != null && linuxLauncher.isProotAvailable() -> {
                    // Inside PRoot Linux userspace, execute directly without sh -c
                    linuxLauncher.launchProcess(
                        command = fullGitArgs,
                        hostWorkspaceDir = repositoryDir,
                        customEnv = cleanEnv
                    )
                }
                else -> {
                    // Direct host execution fallback
                    val hostGit = findHostGitBinary()?.absolutePath ?: "git"
                    val hostCmd = listOf(hostGit) + securityOverrides + args
                    val pb = ProcessBuilder(hostCmd)
                    pb.directory(repositoryDir)
                    pb.environment().clear()
                    pb.environment().putAll(cleanEnv)
                    pb.start()
                }
            }
        }.getOrElse {
            auditLogger.log(
                type = SecurityEventType.TOOL_BLOCKED,
                decision = AuditDecision.BLOCKED,
                summary = "Git launch failed: ${it.message}"
            )
            return@withContext Result.failure(it)
        }

        val result = withTimeoutOrNull(timeoutMs) {
            val stdoutBuffer = StringBuilder()
            val stderrBuffer = StringBuilder()

            val stdoutThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        val buf = CharArray(1024)
                        var bytesRead: Int
                        var total = 0L
                        while (reader.read(buf).also { bytesRead = it } != -1) {
                            if (total < maxOutputBytes) {
                                val toAppend = minOf(bytesRead.toLong(), maxOutputBytes - total).toInt()
                                stdoutBuffer.append(buf, 0, toAppend)
                                total += toAppend
                            }
                        }
                    }
                }
            }
            val stderrThread = Thread {
                runCatching {
                    process.errorStream.bufferedReader().use { reader ->
                        val buf = CharArray(1024)
                        var bytesRead: Int
                        var total = 0L
                        while (reader.read(buf).also { bytesRead = it } != -1) {
                            if (total < maxOutputBytes) {
                                val toAppend = minOf(bytesRead.toLong(), maxOutputBytes - total).toInt()
                                stderrBuffer.append(buf, 0, toAppend)
                                total += toAppend
                            }
                        }
                    }
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val exitCode = process.waitFor()
            stdoutThread.join(2_000)
            stderrThread.join(2_000)

            val duration = (System.nanoTime() - startNs) / 1_000_000L
            GitCommandResult(
                exitCode = exitCode,
                stdout = SecretRedactor.redact(stdoutBuffer.toString()),
                stderr = SecretRedactor.redact(stderrBuffer.toString()),
                durationMs = duration
            )
        }

        if (result == null) {
            process.destroyForcibly()
            auditLogger.log(
                type = SecurityEventType.TOOL_BLOCKED,
                decision = AuditDecision.BLOCKED,
                summary = "Git execution timed out after ${timeoutMs}ms"
            )
            return@withContext Result.failure(
                IllegalStateException("Git execution timed out after ${timeoutMs / 1000} seconds.")
            )
        }

        Result.success(result)
    }
}
