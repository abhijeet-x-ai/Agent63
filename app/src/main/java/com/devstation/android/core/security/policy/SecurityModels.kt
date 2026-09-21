package com.devstation.android.core.security.policy

import com.devstation.android.core.agent.CommandClassification
import com.devstation.android.core.agent.ToolPermission
import com.devstation.android.core.agent.ToolRiskLevel
import java.io.File
import java.util.UUID

/**
 * Phase 7: the vocabulary of the security engine.
 *
 * Every sensitive operation is described as a [SecurityRequest]; the engine answers with a
 * [SecurityDecision]. Nothing in this file can execute anything — deciding and doing are separate.
 */

/** What kind of resource a request wants to touch. Drives category policy and audit reporting. */
enum class ResourceType {
    PROJECT_FILE,
    PROJECT_DIRECTORY,
    EDITOR,
    TERMINAL,
    LINUX_RUNTIME,
    PROCESS,
    NETWORK,
    PACKAGE_MANAGER,
    SENSITIVE_FILE,
    CREDENTIAL,
    UNKNOWN
}

/** What the agent wants to do with the resource. */
enum class SecurityAction {
    READ,
    WRITE,
    CREATE,
    DELETE,
    RENAME,
    OPEN,
    EXECUTE,
    NETWORK,
    INSTALL,
    REMOVE,
    CONTROL,
    UNKNOWN
}

/** How far an action can reach beyond the project. */
enum class ImpactLevel { NONE, PROJECT, SYSTEM }

/** Network intent of a request, independent of the command name that produced it. */
enum class NetworkIntent { NONE, LOCAL_NETWORK, INTERNET }

/** The four answers the engine can give. */
enum class SecurityOutcomeType {
    /** No user interaction required by policy. */
    ALLOW,

    /** A normal approval is required (satisfiable by a task/session grant). */
    ASK,

    /** Approval is required every single time (never satisfied by a grant). */
    ELEVATED,

    /** Refused. No approval can override it. */
    DENY;

    val isDenied: Boolean get() = this == DENY
    val requiresApproval: Boolean get() = this == ASK || this == ELEVATED
}

/** Identity of a file at decision time, used to detect changes before the operation runs (§50). */
data class FileFingerprint(
    val canonicalPath: String,
    val exists: Boolean,
    val size: Long,
    val lastModified: Long,
    /** SHA-256 of the content for small files; null when hashing was skipped (large/missing). */
    val contentHash: String? = null
)

/** Proof that a path was validated inside the project root at decision time. */
data class SandboxToken(
    val canonicalPath: String,
    val fingerprint: FileFingerprint,
    val operation: SandboxOperation,
    val sensitive: Boolean = false
)

enum class SandboxOperation { READ, WRITE, CREATE, DELETE, RENAME, OPEN, EXECUTE }

/**
 * A request for a security decision.
 *
 * [resource] is the raw, untrusted value the model produced (path, command, …). It is never used
 * before the sandbox has resolved it.
 */
data class SecurityRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val projectId: String?,
    val projectRoot: File?,
    val taskId: String?,
    val sessionId: String? = null,
    val agentId: String? = null,
    val toolName: String,
    val action: SecurityAction,
    val resourceType: ResourceType,
    val resource: String? = null,
    val riskLevel: ToolRiskLevel = ToolRiskLevel.LOW,
    val declaredPermission: ToolPermission = ToolPermission.ALLOW,
    val classificationDriven: Boolean = false,
    val classification: CommandClassification? = null,
    /** Tool-declared context: network/filesystem/destructive/sensitive metadata (§44). */
    val networkImpact: NetworkIntent = NetworkIntent.NONE,
    val filesystemImpact: ImpactLevel = ImpactLevel.PROJECT,
    val destructive: Boolean = false,
    val sensitiveDeclared: Boolean = false,
    val metadata: Map<String, String> = emptyMap()
)

/** The engine's answer, with everything the UI needs to explain it. */
data class SecurityDecision(
    val type: SecurityOutcomeType,
    val reason: String,
    val resourceType: ResourceType,
    val riskLevel: ToolRiskLevel,
    val requiredPermission: ToolPermission?,
    val category: PermissionCategory?,
    /** Canonical resource path after sandbox resolution, when applicable. */
    val resolvedResource: String? = null,
    val sandboxToken: SandboxToken? = null,
    val networkIntent: NetworkIntent = NetworkIntent.NONE,
    /** Redacted destination shown to the user when a network approval is required. */
    val destination: String? = null,
    val sensitive: Boolean = false,
    /** Extra explanation shown only when the decision escalates or denies. */
    val explanation: String? = null
) {
    val allowed: Boolean get() = type == SecurityOutcomeType.ALLOW
    val denied: Boolean get() = type.isDenied
    val requiresApproval: Boolean get() = type.requiresApproval
    val elevated: Boolean get() = type == SecurityOutcomeType.ELEVATED
}
