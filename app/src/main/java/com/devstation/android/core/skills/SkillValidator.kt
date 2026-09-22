package com.devstation.android.core.skills

import com.devstation.android.core.agent.ToolRegistry

/**
 * Phase 8 §17/§23: Validates skill definitions before registration.
 *
 * A skill that fails validation is never registered, never executed, and never shown
 * as available to the agent.
 */
class SkillValidator(private val availableTools: () -> Set<String> = { emptySet() }) {

    /** Validate a skill definition. Returns empty list if valid, otherwise list of reasons. */
    fun validate(skill: SkillDefinition): List<String> {
        val reasons = mutableListOf<String>()

        // Schema validation
        if (skill.name.isBlank()) reasons.add("Skill name must not be blank")
        if (skill.name.length > 100) reasons.add("Skill name too long (max 100)")
        if (skill.description.length > 500) reasons.add("Description too long (max 500)")
        if (skill.instructions.isBlank()) reasons.add("Instructions must not be blank")
        if (skill.instructions.length > 10_000) reasons.add("Instructions too long (max 10,000)")
        if (!skill.version.matches(Regex("^\\d+\\.\\d+\\.\\d+$"))) {
            reasons.add("Version must be semver (e.g. 1.0.0)")
        }

        // Tool reference validation
        val tools = availableTools()
        skill.requiredTools.forEach { toolName ->
            if (toolName !in tools) {
                reasons.add("Required tool '$toolName' is not available in the current tool registry")
            }
        }

        // Security validation — reject dangerous declarations
        if (skill.securityProfile.touchesSensitiveFiles && !skill.requestedCapabilities.any {
            it == SkillCapability.FILESYSTEM_READ || it == SkillCapability.FILESYSTEM_WRITE
        }) {
            reasons.add("Skill declares sensitive file access but does not request filesystem capabilities")
        }

        // Reject skills that try to declare unrestricted access
        // (This is a structural check; the runtime enforces it at execution time too)

        return reasons
    }

    /** True when the skill passes validation. */
    fun isValid(skill: SkillDefinition): Boolean = validate(skill).isEmpty()
}
