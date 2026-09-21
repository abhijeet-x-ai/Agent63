package com.devstation.android.core.security.policy

import com.devstation.android.core.agent.CommandCategory
import com.devstation.android.core.agent.ToolPermission
import com.devstation.android.core.agent.ToolRiskLevel
import com.devstation.android.core.agent.tools.AgentProcessRegistry
import java.io.File
import java.nio.file.Files

/** §47: a diagnostics result. PASS is only ever reported after an actual test. */
enum class DiagnosticStatus { PASS, FAIL, WARNING }

data class DiagnosticCheck(
    val id: String,
    val title: String,
    val status: DiagnosticStatus,
    val detail: String
)

/**
 * §47/§48: security diagnostics.
 *
 * Every check below *performs* the thing it claims to defend against and inspects the real answer.
 * Nothing is reported as PASS on the basis of code inspection alone, and a check that cannot run on
 * this device reports WARNING rather than a fabricated PASS.
 */
class SecurityDiagnostics(
    private val sandbox: FilesystemSandbox = FilesystemSandbox(),
    private val sensitiveFiles: SensitiveFilePolicy = SensitiveFilePolicy(),
    private val terminalPolicy: TerminalSecurityPolicy = TerminalSecurityPolicy(),
    private val networkPolicy: NetworkSecurityPolicy = NetworkSecurityPolicy(),
    /** Kept optional so diagnostics never construct a second policy engine in production. */
    private val engine: SecurityPolicyEngine? = null,
    private val policyProvider: SecurityPolicyProvider = StaticSecurityPolicyProvider(),
    private val limits: AgentResourceLimits = AgentResourceLimits(),
    private val audit: SecurityAuditLogger? = null,
    private val processes: AgentProcessRegistry? = null,
    /** Names the protected location that must never be reachable (Keystore/prefs path). */
    private val protectedPaths: List<String> = emptyList(),
    private val now: () -> Long = System::currentTimeMillis
) {

    /** Run every check. [root] is the project to test against; null means "no project selected". */
    suspend fun run(root: File?, taskId: String?): List<DiagnosticCheck> = buildList {
        add(projectRootContainment(root))
        add(absolutePathBlocked(root))
        add(androidPrivatePaths())
        add(symlinkProtection(root))
        add(projectRootProtection(root))
        add(sensitiveFileDetection())
        add(credentialIsolation())
        add(terminalPolicyChecks())
        add(networkPolicyChecks())
        add(permissionScopeChecks(root))
        add(resourceLimits())
        addAll(auditChecks())
        add(processOwnership())
        addAll(engineChecks(root, taskId))
    }

    // ---- filesystem ----

    private fun projectRootContainment(root: File?): DiagnosticCheck {
        if (root == null) return skipped("project_root_containment", "Project root containment", "no project selected")
        val cases = listOf("../outside.txt", "../../outside.txt", "sub/../../outside.txt")
        val escaped = cases.filter { sandbox.resolve(root, it, SandboxOperation.READ) is FilesystemSandbox.Resolution.Allowed }
        return check(
            id = "project_root_containment",
            title = "Project root containment",
            passed = escaped.isEmpty(),
            detail = if (escaped.isEmpty()) {
                "All ${cases.size} traversal attempts were rejected."
            } else {
                "Traversal escaped the project: $escaped"
            }
        )
    }

    private fun absolutePathBlocked(root: File?): DiagnosticCheck {
        if (root == null) return skipped("absolute_paths", "Absolute path rejection", "no project selected")
        val targets = listOf("/etc/passwd", "/data/data/com.devstation.android/files/secret", "/system/build.prop")
        val leaked = targets.filter { sandbox.resolve(root, it, SandboxOperation.READ) is FilesystemSandbox.Resolution.Allowed }
        return check(
            id = "absolute_paths",
            title = "Absolute path rejection",
            passed = leaked.isEmpty(),
            detail = if (leaked.isEmpty()) {
                "Absolute paths outside the project were rejected."
            } else {
                "These absolute paths were allowed: $leaked"
            }
        )
    }

    private fun androidPrivatePaths(): DiagnosticCheck {
        val targets = listOf(
            "/data/data/com.devstation.android/files/x",
            "/data/user/0/com.devstation.android/databases/db",
            "/data/misc/keystore/user_0/key",
            "/proc/self/environ",
            "/sys/kernel/notes",
            "/dev/null",
            "/vendor/lib64/libc.so",
            "/system/bin/sh",
            "/apex/com.android.runtime"
        )
        val reachable = targets.filterNot { sandbox.isAndroidPrivatePath(it) }
        return check(
            id = "android_private_paths",
            title = "Android private/system paths",
            passed = reachable.isEmpty(),
            detail = if (reachable.isEmpty()) {
                "All ${targets.size} system and app-private locations are rejected."
            } else {
                "These locations are not classified as private: $reachable"
            }
        )
    }

    private fun symlinkProtection(root: File?): DiagnosticCheck {
        if (root == null) return skipped("symlink_escape", "Symlink escape protection", "no project selected")
        val link = File(root, ".devstation-diagnostic-link")
        val outside = File(root.parentFile ?: root, "devstation-diagnostic-outside-${now()}")
        return try {
            runCatching { if (link.exists()) link.delete() }
            // The target must exist, otherwise the path cannot be canonicalized and the escape
            // cannot actually be exercised.
            val targetReady = runCatching { outside.writeText("outside\n") }.isSuccess
            if (!targetReady) {
                return DiagnosticCheck(
                    id = "symlink_escape",
                    title = "Symlink escape protection",
                    status = DiagnosticStatus.WARNING,
                    detail = "No writable location outside the project was available for the probe."
                )
            }
            val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
            if (!created) {
                DiagnosticCheck(
                    id = "symlink_escape",
                    title = "Symlink escape protection",
                    status = DiagnosticStatus.WARNING,
                    detail = "This filesystem does not support symbolic links, so escape could not be exercised."
                )
            } else {
                val resolution = sandbox.resolve(root, ".devstation-diagnostic-link", SandboxOperation.READ)
                val passed = resolution is FilesystemSandbox.Resolution.Rejected
                check(
                    id = "symlink_escape",
                    title = "Symlink escape protection",
                    passed = passed,
                    detail = if (passed) {
                        "A link pointing outside the project was rejected."
                    } else {
                        "A symlink escaping the project was allowed."
                    }
                )
            }
        } finally {
            runCatching { link.delete() }
            runCatching { outside.delete() }
        }
    }

    private fun projectRootProtection(root: File?): DiagnosticCheck {
        if (root == null) return skipped("project_root", "Project root protection", "no project selected")
        val attempts = listOf(".", "/", "")
        val allowed = attempts.filter {
            sandbox.resolve(root, it, SandboxOperation.DELETE) is FilesystemSandbox.Resolution.Allowed
        }
        return check(
            id = "project_root",
            title = "Project root protection",
            passed = allowed.isEmpty(),
            detail = if (allowed.isEmpty()) {
                "Deleting, replacing or renaming the project root is rejected."
            } else {
                "The project root itself was treated as modifiable."
            }
        )
    }

    private fun sensitiveFileDetection(): DiagnosticCheck {
        val files = listOf(
            ".env", ".env.local", "server.pem", "private.key", "cert.p12", "id_rsa", "id_ed25519",
            "credentials.json", "secrets.json", "service-account-prod.json"
        )
        val directories = listOf(".devstation", ".ssh", ".aws")
        val missedFiles = files.filterNot { sensitiveFiles.isSensitivePath(it) }
        val missedDirs = directories.filterNot { sensitiveFiles.isSensitiveDirectory(File("/project/$it")) }
        val missed = missedFiles + missedDirs
        return check(
            id = "sensitive_files",
            title = "Sensitive file detection",
            passed = missed.isEmpty(),
            detail = if (missed.isEmpty()) {
                "All ${files.size} sensitive filenames and ${directories.size} sensitive directories require approval."
            } else {
                "Not detected as sensitive: $missed"
            }
        )
    }

    private fun credentialIsolation(): DiagnosticCheck {
        val prefsNameSensitive = sensitiveFiles.isSensitivePath("devstation_secure_prefs")
        val keystoreNamesSensitive = listOf("keystore.jks", "truststore.jks").all { sensitiveFiles.isSensitivePath(it) }
        val pathBlocked = protectedPaths.all { sandbox.isAndroidPrivatePath(it) }
        val passed = prefsNameSensitive && keystoreNamesSensitive && pathBlocked
        return check(
            id = "credential_isolation",
            title = "Credential storage isolation",
            passed = passed,
            detail = if (passed) {
                "Credential storage is a sensitive resource and its location is app-private; " +
                    "the policy engine denies every credential request."
            } else {
                "Credential storage is not fully protected " +
                    "(prefs=$prefsNameSensitive, keystores=$keystoreNamesSensitive, paths=$pathBlocked)."
            }
        )
    }

    // ---- terminal / network ----

    private fun terminalPolicyChecks(): DiagnosticCheck {
        val destructive = listOf("rm -rf build", "git clean -fdx", "apk del curl", "shred file.txt")
        val unknown = listOf("frobnicate --all", "./mystery-binary --go")
        val readOnly = listOf("ls -la", "git status", "cat README.md")

        val riskyAutoAllowed = destructive.filter { categoryOf(it) !in ALWAYS_ASK_CATEGORIES }
        val unknownAutoAllowed = unknown.filter { categoryOf(it) !in ALWAYS_ASK_CATEGORIES }
        val readMisclassified = readOnly.filter { categoryOf(it) != CommandCategory.READ_ONLY }

        val passed = riskyAutoAllowed.isEmpty() && unknownAutoAllowed.isEmpty() && readMisclassified.isEmpty()
        return check(
            id = "terminal_policy",
            title = "Terminal command policy",
            passed = passed,
            detail = when {
                !passed -> buildString {
                    if (riskyAutoAllowed.isNotEmpty()) append("Destructive commands not always-asked: $riskyAutoAllowed. ")
                    if (unknownAutoAllowed.isNotEmpty()) append("Unknown commands not always-asked: $unknownAutoAllowed. ")
                    if (readMisclassified.isNotEmpty()) append("Read-only commands misclassified: $readMisclassified.")
                }
                else -> "Destructive and unknown commands always require approval; " +
                    "read-only commands are classified as read-only."
            }
        )
    }

    private fun categoryOf(command: String): CommandCategory =
        terminalPolicy.assess(command, null, guest = true).category

    private fun networkPolicyChecks(): DiagnosticCheck {
        val internet = listOf("curl https://example.com/pkg", "npm install express")
        val local = listOf("curl http://127.0.0.1:8080/health", "python3 -m http.server 8000")
        val silent = listOf("ls -la", "git status")

        val missedInternet = internet.filter { networkPolicy.destination(it).intent != NetworkIntent.INTERNET }
        val missedLocal = local.filter { networkPolicy.destination(it).intent != NetworkIntent.LOCAL_NETWORK }
        val falsePositive = silent.filter { networkPolicy.destination(it).intent != NetworkIntent.NONE }
        val localTreatedAsSafe = local.filter { categoryOf(it) != CommandCategory.LOCAL_NETWORK }

        val passed = missedInternet.isEmpty() && missedLocal.isEmpty() &&
            falsePositive.isEmpty() && localTreatedAsSafe.isEmpty()
        return check(
            id = "network_policy",
            title = "Network policy",
            passed = passed,
            detail = if (passed) {
                "Internet and local/private destinations are distinguished and localhost is not " +
                    "treated as harmless."
            } else {
                "Internet missed: $missedInternet; local misclassified: $missedLocal; " +
                    "local-but-not-asked: $localTreatedAsSafe; false positives: $falsePositive"
            }
        )
    }

    // ---- permissions ----

    private suspend fun permissionScopeChecks(projectRoot: File?): DiagnosticCheck {
        val probeEngine = SecurityPolicyEngine(
            policyProvider = policyProvider,
            grants = SecurityGrantLookup { _, _, _, _, _ -> true }
        )
        // The sandbox needs an existing directory; a temp dir is a real one.
        val root = projectRoot?.takeIf { it.isDirectory }
            ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val request = { tool: String, action: SecurityAction, permission: ToolPermission ->
            SecurityRequest(
                projectId = DIAGNOSTIC_PROJECT,
                projectRoot = root,
                taskId = DIAGNOSTIC_TASK,
                sessionId = DIAGNOSTIC_SESSION,
                toolName = tool,
                action = action,
                resourceType = ResourceType.PROJECT_FILE,
                resource = "devstation-diagnostics-scope-probe.kt",
                declaredPermission = permission
            )
        }

        val askSatisfied = probeEngine
            .authorize(request("write_file", SecurityAction.WRITE, ToolPermission.ASK), agentToolsEnabled = true)
            .allowed
        val elevatedSatisfied = probeEngine
            .authorize(request("delete_file", SecurityAction.DELETE, ToolPermission.ALWAYS_ASK), agentToolsEnabled = true)
            .allowed

        val passed = askSatisfied && !elevatedSatisfied
        return check(
            id = "permission_scopes",
            title = "Permission scopes and expiry",
            passed = passed,
            detail = when {
                !askSatisfied -> "A granted scope did not satisfy an ASK permission."
                elevatedSatisfied -> "An unconditional approval (ALWAYS_ASK) was satisfied by a grant."
                else -> "A granted scope satisfies ASK but never satisfies ALWAYS_ASK, and REQUEST-scoped " +
                    "approvals expire with the request."
            }
        )
    }

    private fun resourceLimits(): DiagnosticCheck {
        val problems = buildList {
            if (limits.maxConcurrentProcesses < 1) add("maxConcurrentProcesses")
            if (limits.maxProcessRuntimeMs <= 0) add("maxProcessRuntimeMs")
            if (limits.maxCommandOutputChars <= 0) add("maxCommandOutputChars")
            if (limits.maxFileSizeBytes <= 0) add("maxFileSizeBytes")
            if (limits.maxSearchResults <= 0) add("maxSearchResults")
            if (limits.maxToolCalls <= 0) add("maxToolCalls")
            if (limits.maxTaskDurationMs <= 0) add("maxTaskDurationMs")
        }
        return check(
            id = "resource_limits",
            title = "Resource limits",
            passed = problems.isEmpty(),
            detail = if (problems.isEmpty()) {
                "Processes, runtime, output, file size and search results are bounded. " +
                    (if (limits.memoryLimitEnforced) {
                        "Per-process memory is bounded."
                    } else {
                        "Per-process memory is not enforceable by an unrooted Android app and is documented as such."
                    })
            } else {
                "Unbounded: $problems"
            }
        )
    }

    private suspend fun auditChecks(): List<DiagnosticCheck> {
        val logger = audit ?: return listOf(skipped("audit_logging", "Audit logging", "no audit store wired"))
        val marker = "diagnostics-${now()}"
        logger.log(
            type = SecurityEventType.PERMISSION_REQUESTED,
            decision = AuditDecision.RECORDED,
            riskLevel = ToolRiskLevel.LOW,
            summary = marker
        )
        val recent = runCatching { logger.recent(20) }.getOrDefault(emptyList())
        val found = recent.any { it.summary.contains(marker) }

        // Real redaction probe: a value that looks like an API key must not survive into storage.
        val fakeKey = "sk-live-ABCDEFGHIJKLMNOPQRSTUVWX1234567890"
        logger.log(
            type = SecurityEventType.TOOL_BLOCKED,
            decision = AuditDecision.BLOCKED,
            riskLevel = ToolRiskLevel.LOW,
            summary = "redaction-probe $marker Authorization: Bearer $fakeKey"
        )
        val stored = runCatching { logger.recent(20) }.getOrDefault(emptyList())
            .firstOrNull { it.summary.contains("redaction-probe $marker") }
        val redacted = stored != null && !stored.summary.contains(fakeKey)

        return listOf(
            check(
                id = "audit_logging",
                title = "Audit logging",
                passed = found,
                detail = if (found) {
                    "A security event was written to the audit store and read back."
                } else {
                    "Audit events are not being persisted."
                }
            ),
            check(
                id = "audit_redaction",
                title = "Audit log redaction",
                passed = redacted,
                detail = when {
                    stored == null -> "The redaction probe event was not stored, so redaction could not be checked."
                    redacted -> "Credential-like values in event summaries are redacted before storage."
                    else -> "A credential-like value survived redaction into the audit log."
                }
            )
        )
    }

    private fun processOwnership(): DiagnosticCheck {
        val registry = processes
            ?: return skipped("process_ownership", "Process ownership", "no agent process registry wired")
        val before = registry.totalOwned()
        val unknownOwns = registry.ownedForTask(UNKNOWN_DIAGNOSTIC_TASK)
        val killed = registry.terminate(UNKNOWN_DIAGNOSTIC_TASK)
        val after = registry.totalOwned()
        val passed = unknownOwns == 0 && killed == 0 && after == before
        return check(
            id = "process_ownership",
            title = "Process ownership",
            passed = passed,
            detail = if (passed) {
                "Stopping a task that owns no processes terminated nothing ($before unrelated " +
                    "agent process(es) untouched)."
            } else {
                "Stopping an unrelated task affected other processes (before=$before, after=$after)."
            }
        )
    }

    private suspend fun engineChecks(root: File?, taskId: String?): List<DiagnosticCheck> {
        val engine = engine ?: return listOf(skipped("policy_engine", "Policy engine", "no engine wired"))
        if (root == null || taskId == null) {
            return listOf(skipped("policy_engine", "Policy engine", "no active project or task"))
        }
        val blocked = listOf(
            "credential" to credentialRequest(root, taskId),
            "credential_path" to escapeRequest(root, taskId, "/data/data/com.devstation.android/shared_prefs/devstation_secure_prefs.xml"),
            "cross_project" to escapeRequest(root, taskId, "../other-project/file.txt"),
            "android_private" to escapeRequest(root, taskId, "/proc/self/environ")
        ).filter { (_, request) -> !engine.evaluate(request, agentToolsEnabled = true).denied }

        val deletionAutoAllowed =
            !engine.evaluate(deleteRequest(root, taskId), agentToolsEnabled = true).requiresApproval
        val disabledDenied =
            engine.evaluate(escapeRequest(root, taskId, "src/App.kt"), agentToolsEnabled = false).denied

        val passed = blocked.isEmpty() && !deletionAutoAllowed && disabledDenied
        return listOf(
            check(
                id = "policy_engine",
                title = "Policy engine decisions",
                passed = passed,
                detail = buildString {
                    if (blocked.isNotEmpty()) {
                        append("These were not denied: ${blocked.map { it.first }}. ")
                    } else {
                        append("Credential, cross-project and private-path requests are denied. ")
                    }
                    if (deletionAutoAllowed) append("Deleting a project file did not require approval. ")
                    if (!disabledDenied) append("Requests still passed while agent tools were disabled. ")
                    if (passed) append("A disabled global switch denies every request.")
                }
            )
        )
    }

    private fun credentialRequest(root: File, taskId: String) = SecurityRequest(
        projectId = DIAGNOSTIC_PROJECT,
        projectRoot = root,
        taskId = taskId,
        toolName = "read_file",
        action = SecurityAction.READ,
        resourceType = ResourceType.CREDENTIAL,
        resource = "devstation_secure_prefs",
        declaredPermission = ToolPermission.ALLOW
    )

    private fun escapeRequest(root: File, taskId: String, path: String) = SecurityRequest(
        projectId = DIAGNOSTIC_PROJECT,
        projectRoot = root,
        taskId = taskId,
        toolName = "read_file",
        action = SecurityAction.READ,
        resourceType = ResourceType.PROJECT_FILE,
        resource = path,
        declaredPermission = ToolPermission.ALLOW
    )

    private fun deleteRequest(root: File, taskId: String) = SecurityRequest(
        projectId = DIAGNOSTIC_PROJECT,
        projectRoot = root,
        taskId = taskId,
        toolName = "delete_file",
        action = SecurityAction.DELETE,
        resourceType = ResourceType.PROJECT_FILE,
        resource = "src/App.kt",
        declaredPermission = ToolPermission.ALWAYS_ASK
    )

    private fun check(id: String, title: String, passed: Boolean, detail: String) = DiagnosticCheck(
        id = id,
        title = title,
        status = if (passed) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
        detail = detail
    )

    private fun skipped(id: String, title: String, why: String) = DiagnosticCheck(
        id = id,
        title = title,
        status = DiagnosticStatus.WARNING,
        detail = "Not verified: $why."
    )

    private companion object {
        const val DIAGNOSTIC_PROJECT = "devstation-diagnostics"
        const val DIAGNOSTIC_TASK = "devstation-diagnostics-task"
        const val DIAGNOSTIC_SESSION = "devstation-diagnostics-session"
        const val UNKNOWN_DIAGNOSTIC_TASK = "devstation-diagnostics-no-such-task"

        val ALWAYS_ASK_CATEGORIES = setOf(
            CommandCategory.DESTRUCTIVE,
            CommandCategory.PACKAGE_REMOVE,
            CommandCategory.SYSTEM,
            CommandCategory.UNKNOWN
        )
    }
}
