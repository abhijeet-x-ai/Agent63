package com.devstation.android.core.security.policy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phase 7 §3/§40: the policy the engine evaluates. Policy is data — it lives in Room and can only
 * be changed through user-facing UI/ViewModel APIs, never by the agent.
 */

/** Security categories the user can control. */
enum class PermissionCategory { FILES, TERMINAL, NETWORK, PACKAGES, SENSITIVE_FILES, CREDENTIALS }

/**
 * What a category allows.
 *
 * [DEFAULT] keeps the phase 6 behaviour: the tool's own declared permission (plus command
 * classification for terminal commands) decides. The other values override it.
 */
enum class CategoryPolicy { DEFAULT, ALLOW, ASK, ALWAYS_ASK, DENY }

/** §40: three modes, no "unrestricted" mode exists. */
enum class AgentSecurityMode { SAFE, BALANCED, CUSTOM }

/**
 * Enforced resource limits. Every entry here is actually enforced somewhere in the tool layer;
 * things that cannot be enforced on Android are documented in [AgentResourceLimits.notes] instead
 * of being silently pretended.
 */
data class AgentResourceLimits(
    /** Concurrent agent-owned processes (§25). */
    val maxConcurrentProcesses: Int = 3,
    /** Hard ceiling for a single agent command (§28). */
    val maxProcessRuntimeMs: Long = 900_000L,
    /** Characters kept from one tool result (§31). */
    val maxCommandOutputChars: Int = 24_000,
    /** Largest file the agent may read into context (§32). */
    val maxFileSizeBytes: Long = 8L * 1024 * 1024,
    val maxSearchResults: Int = 100,
    val maxToolCalls: Int = 50,
    val maxTaskDurationMs: Long = 600_000L,
    /** Kernel-level memory enforcement is not available to an unrooted Android app. */
    val memoryLimitEnforced: Boolean = false
) {
    init {
        require(maxConcurrentProcesses in 1..16) { "maxConcurrentProcesses out of range" }
        require(maxProcessRuntimeMs in 1_000..3_600_000) { "maxProcessRuntimeMs out of range" }
        require(maxCommandOutputChars in 1_000..2_000_000) { "maxCommandOutputChars out of range" }
        require(maxFileSizeBytes in 64 * 1024..512L * 1024 * 1024) { "maxFileSizeBytes out of range" }
        require(maxSearchResults in 1..1_000) { "maxSearchResults out of range" }
    }

    val notes: String
        get() = if (memoryLimitEnforced) {
            "Process count, runtime, output size, file size and search results are enforced."
        } else {
            "Enforced: process count, process runtime, output size, file size, search results, " +
                "tool calls and task duration. Not enforced: per-process memory (Android provides " +
                "no unprivileged cgroup control)."
        }
}

/**
 * The complete agent security policy.
 *
 * Sensitive-file and credential handling is deliberately not fully expressible by the user:
 * credentials are always [CategoryPolicy.DENY], and sensitive *deletes* are always elevated
 * (see [SecurityPolicyEngine]).
 */
@Serializable
data class SecurityPolicy(
    val mode: AgentSecurityMode = AgentSecurityMode.BALANCED,
    val files: CategoryPolicy = CategoryPolicy.DEFAULT,
    val terminal: CategoryPolicy = CategoryPolicy.DEFAULT,
    val network: CategoryPolicy = CategoryPolicy.DEFAULT,
    val packages: CategoryPolicy = CategoryPolicy.DEFAULT,
    val sensitiveFiles: CategoryPolicy = CategoryPolicy.ASK,
    val credentials: CategoryPolicy = CategoryPolicy.DENY,
    val auditRetentionDays: Int = DEFAULT_RETENTION_DAYS,
    val maxConcurrentProcesses: Int = 3,
    val maxProcessRuntimeMs: Long = 900_000L
) {

    fun policyFor(category: PermissionCategory): CategoryPolicy = when (category) {
        PermissionCategory.FILES -> files
        PermissionCategory.TERMINAL -> terminal
        PermissionCategory.NETWORK -> network
        PermissionCategory.PACKAGES -> packages
        PermissionCategory.SENSITIVE_FILES -> sensitiveFiles
        PermissionCategory.CREDENTIALS -> credentials
    }

    /** Credential policy can never be loosened through this API. */
    fun withCategory(category: PermissionCategory, policy: CategoryPolicy): SecurityPolicy {
        if (category == PermissionCategory.CREDENTIALS) return this
        val updated = when (category) {
            PermissionCategory.FILES -> copy(files = policy)
            PermissionCategory.TERMINAL -> copy(terminal = policy)
            PermissionCategory.NETWORK -> copy(network = policy)
            PermissionCategory.PACKAGES -> copy(packages = policy)
            PermissionCategory.SENSITIVE_FILES -> copy(sensitiveFiles = policy)
            PermissionCategory.CREDENTIALS -> this
        }
        return updated.copy(mode = AgentSecurityMode.CUSTOM)
    }

    val limits: AgentResourceLimits
        get() = AgentResourceLimits(
            maxConcurrentProcesses = maxConcurrentProcesses,
            maxProcessRuntimeMs = maxProcessRuntimeMs
        )

    /** §43: what the user should be told before broadening this policy. */
    fun impactSummary(): List<String> = buildList {
        if (isBroad(terminal)) add("Terminal commands may run with less oversight.")
        if (isBroad(network)) add("Commands may communicate with external servers.")
        if (isBroad(packages)) add("Software packages may be installed or removed.")
        if (isBroad(files)) add("Project files may be modified with less oversight.")
        if (isBroad(sensitiveFiles)) {
            add("Files that usually hold secrets (for example .env) may be read.")
        }
    }

    private fun isBroad(policy: CategoryPolicy): Boolean = policy == CategoryPolicy.ALLOW

    companion object {
        const val DEFAULT_RETENTION_DAYS = 30
        val RETENTION_OPTIONS = listOf(7, 30, 90)

        /** Phase 6-equivalent behaviour: the tool's own permission decides. */
        val DEFAULT = SecurityPolicy()

        fun forMode(mode: AgentSecurityMode): SecurityPolicy = when (mode) {
            AgentSecurityMode.BALANCED -> SecurityPolicy(mode = AgentSecurityMode.BALANCED)
            AgentSecurityMode.SAFE -> SecurityPolicy(
                mode = AgentSecurityMode.SAFE,
                // §41: reads stay automatic (including read-only commands such as `git status`),
                // while anything that changes the project or reaches outward needs a fresh,
                // explicit decision every single time — no grant can cover it.
                files = CategoryPolicy.DEFAULT,
                terminal = CategoryPolicy.DEFAULT,
                network = CategoryPolicy.ALWAYS_ASK,
                packages = CategoryPolicy.ALWAYS_ASK,
                sensitiveFiles = CategoryPolicy.ALWAYS_ASK,
                credentials = CategoryPolicy.DENY
            )
            AgentSecurityMode.CUSTOM -> SecurityPolicy(
                mode = AgentSecurityMode.CUSTOM,
                files = CategoryPolicy.DEFAULT,
                terminal = CategoryPolicy.ASK,
                network = CategoryPolicy.ASK,
                packages = CategoryPolicy.ALWAYS_ASK,
                sensitiveFiles = CategoryPolicy.ALWAYS_ASK,
                credentials = CategoryPolicy.DENY
            )
        }

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Persisted form. Only non-secret policy values are ever written. */
        fun encode(policy: SecurityPolicy): String = json.encodeToString(SecurityPolicy.serializer(), policy)

        fun decode(raw: String?, fallback: SecurityPolicy = DEFAULT): SecurityPolicy =
            if (raw.isNullOrBlank()) {
                fallback
            } else {
                runCatching { json.decodeFromString(SecurityPolicy.serializer(), raw) }.getOrDefault(fallback)
            }
    }
}

/**
 * §39: per-project security settings. Defaults mirror phase 6 behaviour; every switch exists so the
 * user can *tighten* a specific project. A disabled category is a hard DENY for that project.
 */
@Serializable
data class ProjectSecuritySettings(
    val projectId: String,
    val allowFileModification: Boolean = true,
    val allowTerminal: Boolean = true,
    val allowNetwork: Boolean = true,
    val allowPackageInstallation: Boolean = true,
    val allowSensitiveFileAccess: Boolean = true
) {
    fun denies(category: PermissionCategory): Boolean = when (category) {
        PermissionCategory.FILES -> !allowFileModification
        PermissionCategory.TERMINAL -> !allowTerminal
        PermissionCategory.NETWORK -> !allowNetwork
        PermissionCategory.PACKAGES -> !allowPackageInstallation
        PermissionCategory.SENSITIVE_FILES -> !allowSensitiveFileAccess
        // Credentials are never permitted, so project settings cannot loosen them either.
        PermissionCategory.CREDENTIALS -> true
    }

    companion object {
        fun defaults(projectId: String) = ProjectSecuritySettings(projectId = projectId)
    }
}

/** Source of the active policy. Production reads Room; tests use a static provider. */
fun interface SecurityPolicyProvider {
    suspend fun policy(projectId: String?): SecurityPolicy
}

/** Provider used when no persistence is wired (and in unit tests). */
class StaticSecurityPolicyProvider(private val policy: SecurityPolicy = SecurityPolicy.DEFAULT) :
    SecurityPolicyProvider {
    override suspend fun policy(projectId: String?): SecurityPolicy = policy
}
