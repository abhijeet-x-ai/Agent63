package com.devstation.android.core.agent.profiles

import com.devstation.android.core.skills.SkillCapability
import java.util.UUID

/**
 * Phase 8 §26: Custom Agent Profile data models.
 *
 * An Agent Profile is a configuration for an AI execution context. It specifies which tools,
 * skills, and MCP servers the agent may use, and what security constraints apply.
 *
 * Agent Profiles are NOT security authorities — they are configuration. The SecurityManager
 * remains the single authority for all permission decisions.
 */

/** Security scope for an agent profile. */
enum class AgentProfileSecurityScope {
    /** Read-only by default; modifications require explicit approval. */
    SAFE,
    /** Low-risk reads automatic; normal edits ask. */
    BALANCED,
    /** User controls each category. */
    CUSTOM
}

/**
 * Permissions declared by an agent profile. These are REQUESTS, not grants.
 * The SecurityManager evaluates them at execution time.
 */
data class AgentProfilePermissions(
    /** Requested file access level. */
    val fileAccess: AgentProfileFileAccess = AgentProfileFileAccess.READ_ONLY,
    /** Whether terminal access is requested. */
    val terminalAccess: Boolean = false,
    /** Whether network access is requested. */
    val networkAccess: Boolean = false,
    /** Whether package management is requested. */
    val packageAccess: Boolean = false,
    /** Whether sensitive file access is requested (always requires approval). */
    val sensitiveFileAccess: Boolean = false
) {
    /** Validate that no illegal security declarations exist. */
    fun isValid(): Boolean = true // All permission values are valid; enforcement is at runtime
}

enum class AgentProfileFileAccess { READ_ONLY, READ_WRITE }

/**
 * A custom agent profile configuration.
 *
 * [systemInstructions] are prepended to the agent's system prompt.
 * [enabledTools] are the DevStation tool names this agent may use.
 * [enabledSkills] are the skill IDs this agent may invoke.
 * [enabledMcpServers] are the MCP server IDs this agent may use.
 */
data class AgentProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val systemInstructions: String = "",
    val providerId: String? = null,
    val modelId: String? = null,
    val enabledTools: List<String> = emptyList(),
    val enabledSkills: List<String> = emptyList(),
    val enabledMcpServers: List<String> = emptyList(),
    val permissionProfile: AgentProfilePermissions = AgentProfilePermissions(),
    val securityScope: AgentProfileSecurityScope = AgentProfileSecurityScope.BALANCED,
    val projectScope: String? = null,
    val maxIterations: Int = 25,
    val maxToolCalls: Int = 50,
    val maxTaskDurationMs: Long = 600_000L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    init {
        require(name.isNotBlank()) { "Agent profile name must not be blank" }
        require(name.length <= 100) { "Agent profile name too long" }
        require(description.length <= 500) { "Description too long" }
        require(systemInstructions.length <= 10_000) { "System instructions too long" }
        require(maxIterations in 1..500) { "maxIterations out of range" }
        require(maxToolCalls in 1..1000) { "maxToolCalls out of range" }
        require(maxTaskDurationMs in 30_000..7_200_000) { "maxTaskDurationMs out of range" }
    }

    /** True when this profile requests capabilities that always require approval. */
    fun hasHighRiskCapabilities(): Boolean =
        permissionProfile.terminalAccess ||
        permissionProfile.networkAccess ||
        permissionProfile.packageAccess ||
        permissionProfile.sensitiveFileAccess

    /** Effective tools list — empty means all built-in tools are available. */
    fun effectiveTools(allToolNames: Set<String>): Set<String> =
        if (enabledTools.isEmpty()) allToolNames else enabledTools.toSet()
}

/** Result of agent profile operations. */
sealed class AgentProfileResult {
    data class Success(val profile: AgentProfile) : AgentProfileResult()
    data class Error(val message: String) : AgentProfileResult()
    data class ValidationError(val reasons: List<String>) : AgentProfileResult()
}
