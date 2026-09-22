package com.devstation.android.core.skills

import java.util.UUID

/**
 * Phase 8 §16/§17: Skill system data models.
 *
 * A Skill is a reusable AI workflow/capability definition. It specifies instructions,
 * required tools, requested capabilities, and a security profile. Skills execute through
 * the existing AgentRuntime + ToolRegistry + SecurityManager pipeline.
 */

/** Where a skill came from. */
enum class SkillSource { BUILTIN, PROJECT, USER }

/** Capability a skill may request. */
enum class SkillCapability {
    FILESYSTEM_READ,
    FILESYSTEM_WRITE,
    TERMINAL_READ,
    TERMINAL_WRITE,
    NETWORK,
    PACKAGE_INSTALL
}

/** Security constraints for a skill. */
data class SkillSecurityProfile(
    /** Requested capabilities — these are REQUESTS only, SecurityManager is authoritative. */
    val requestedCapabilities: List<SkillCapability> = emptyList(),
    /** Whether the skill requires network access. */
    val requiresNetwork: Boolean = false,
    /** Whether the skill requires terminal access. */
    val requiresTerminal: Boolean = false,
    /** Whether the skill touches sensitive files. */
    val touchesSensitiveFiles: Boolean = false
) {
    /** Validate that no illegal security declarations exist. */
    fun isValid(): Boolean {
        // Skills must not declare unrestricted access
        return !touchesSensitiveFiles || requestedCapabilities.any {
            it == SkillCapability.FILESYSTEM_READ || it == SkillCapability.FILESYSTEM_WRITE
        }
    }
}

/**
 * A structured skill definition.
 *
 * [instructions] are the system prompt additions that guide the AI when this skill is active.
 * [requiredTools] are the DevStation tool names the skill needs (validated on registration).
 * [requestedCapabilities] are security capability requests (NOT grants — SecurityManager decides).
 */
data class SkillDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val author: String = "DevStation",
    val instructions: String,
    val requiredTools: List<String> = emptyList(),
    val requestedCapabilities: List<SkillCapability> = emptyList(),
    val securityProfile: SkillSecurityProfile = SkillSecurityProfile(),
    val source: SkillSource = SkillSource.USER,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null,
    val runCount: Int = 0
) {
    init {
        require(name.isNotBlank()) { "Skill name must not be blank" }
        require(name.length <= 100) { "Skill name too long" }
        require(description.length <= 500) { "Description too long" }
        require(instructions.isNotBlank()) { "Instructions must not be blank" }
        require(instructions.length <= 10_000) { "Instructions too long" }
        require(version.matches(Regex("^\\d+\\.\\d+\\.\\d+$"))) { "Version must be semver (e.g. 1.0.0)" }
    }
}

/** Result of executing a skill. */
sealed class SkillExecutionResult {
    data class Success(
        val skillId: String,
        val output: String,
        val toolCallsUsed: Int,
        val durationMs: Long
    ) : SkillExecutionResult()

    data class Error(
        val skillId: String,
        val message: String
    ) : SkillExecutionResult()

    data class Denied(
        val skillId: String,
        val reason: String
    ) : SkillExecutionResult()

    data class ValidationFailed(
        val skillId: String,
        val reasons: List<String>
    ) : SkillExecutionResult()
}

/** State of skill execution for tracking recursion depth. */
data class SkillExecutionContext(
    val skillId: String,
    val parentSkillId: String? = null,
    val depth: Int = 0,
    val startTime: Long = System.currentTimeMillis(),
    val toolCalls: Int = 0
) {
    fun canRecurse(): Boolean = depth < MAX_SKILL_RECURSION_DEPTH

    fun childContext(childSkillId: String): SkillExecutionContext = SkillExecutionContext(
        skillId = childSkillId,
        parentSkillId = skillId,
        depth = depth + 1
    )

    companion object {
        const val MAX_SKILL_RECURSION_DEPTH = 3
    }
}
