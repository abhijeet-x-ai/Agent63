package com.devstation.android.core.skills

import com.devstation.android.core.agent.SecretRedactor
import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.security.policy.SecurityAuditLogger
import com.devstation.android.core.security.policy.SecurityEventType
import com.devstation.android.core.security.policy.AuditDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 8 §16/§18/§22/§23: Skill registry and lifecycle manager.
 *
 * Handles registration, validation, enable/disable, and execution of skills.
 * Skills are validated before registration. Execution goes through the existing
 * AgentRuntime → ToolExecutor → SecurityManager pipeline.
 */
class SkillManager(
    private val validator: SkillValidator,
    private val skillExecutor: SkillExecutor,
    private val audit: SecurityAuditLogger,
    private val dispatchers: DispatcherProvider,
    private val configStore: SkillConfigStore
) {
    private val _skills = MutableStateFlow<Map<String, SkillDefinition>>(emptyMap())
    val skills: StateFlow<Map<String, SkillDefinition>> = _skills.asStateFlow()

    private val executionContexts = ConcurrentHashMap<String, SkillExecutionContext>()

    /** Load skills from persistent storage + built-in skills. */
    suspend fun loadSkills() {
        val stored = runCatching { configStore.getAll() }.getOrDefault(emptyList())
        val storedMap = stored.associateBy { it.id }.toMutableMap()

        // Always include built-in skills
        BuiltInSkills.all.forEach { builtin ->
            if (!storedMap.containsKey(builtin.id)) {
                storedMap[builtin.id] = builtin
            }
        }

        _skills.value = storedMap
    }

    /** Register a new skill. Validates before saving. */
    suspend fun registerSkill(skill: SkillDefinition): Result<SkillDefinition> {
        val reasons = validator.validate(skill)
        if (reasons.isNotEmpty()) {
            return Result.failure(
                SkillValidationException("Skill validation failed: ${reasons.joinToString("; ")}")
            )
        }

        if (_skills.value.containsKey(skill.id) && skill.source != SkillSource.BUILTIN) {
            return Result.failure(IllegalStateException("Skill '${skill.name}' is already registered."))
        }

        runCatching { configStore.save(skill) }
        _skills.value = _skills.value + (skill.id to skill)

        audit.log(
            type = SecurityEventType.SECURITY_POLICY_CHANGED,
            decision = AuditDecision.RECORDED,
            summary = "Skill registered: ${SecretRedactor.redact(skill.name)} (v${skill.version})"
        )
        return Result.success(skill)
    }

    /** Update an existing skill. */
    suspend fun updateSkill(skill: SkillDefinition): Result<SkillDefinition> {
        val existing = _skills.value[skill.id]
            ?: return Result.failure(IllegalStateException("Skill '${skill.id}' not found."))
        if (existing.source == SkillSource.BUILTIN) {
            return Result.failure(IllegalStateException("Built-in skills cannot be modified."))
        }

        val reasons = validator.validate(skill)
        if (reasons.isNotEmpty()) {
            return Result.failure(
                SkillValidationException("Skill validation failed: ${reasons.joinToString("; ")}")
            )
        }

        val updated = skill.copy(updatedAt = System.currentTimeMillis())
        runCatching { configStore.save(updated) }
        _skills.value = _skills.value + (skill.id to updated)
        return Result.success(updated)
    }

    /** Remove a skill. Built-in skills cannot be removed. */
    suspend fun removeSkill(skillId: String): Result<Unit> {
        val skill = _skills.value[skillId]
            ?: return Result.failure(IllegalStateException("Skill not found."))
        if (skill.source == SkillSource.BUILTIN) {
            return Result.failure(IllegalStateException("Built-in skills cannot be removed."))
        }
        runCatching { configStore.delete(skillId) }
        _skills.value = _skills.value - skillId
        audit.log(
            type = SecurityEventType.SECURITY_POLICY_CHANGED,
            decision = AuditDecision.RECORDED,
            summary = "Skill removed: $skillId"
        )
        return Result.success(Unit)
    }

    /** Enable or disable a skill. */
    suspend fun setEnabled(skillId: String, enabled: Boolean): Result<Unit> {
        val skill = _skills.value[skillId]
            ?: return Result.failure(IllegalStateException("Skill not found."))
        val updated = skill.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
        runCatching { configStore.save(updated) }
        _skills.value = _skills.value + (skillId to updated)
        return Result.success(Unit)
    }

    /** Execute a skill through the AgentRuntime pipeline. */
    suspend fun executeSkill(
        skillId: String,
        goal: String,
        projectId: String,
        conversationId: String? = null
    ): SkillExecutionResult {
        val skill = _skills.value[skillId]
            ?: return SkillExecutionResult.Error(skillId, "Skill not found.")
        if (!skill.enabled) {
            return SkillExecutionResult.Error(skillId, "Skill is disabled.")
        }

        // Check recursion protection
        val context = executionContexts[projectId]
        if (context != null && context.skillId == skillId) {
            return SkillExecutionResult.Denied(skillId, "Skill recursion detected — this skill is already running.")
        }
        if (context != null && !context.canRecurse()) {
            return SkillExecutionResult.Denied(skillId, "Maximum skill recursion depth reached.")
        }

        // Validate skill before execution (re-validate since tool registry may have changed)
        val reasons = validator.validate(skill)
        if (reasons.isNotEmpty()) {
            return SkillExecutionResult.ValidationFailed(skillId, reasons)
        }

        val execContext = if (context != null) {
            context.childContext(skillId)
        } else {
            SkillExecutionContext(skillId = skillId)
        }
        executionContexts[projectId] = execContext

        try {
            audit.log(
                type = SecurityEventType.PERMISSION_REQUESTED,
                decision = AuditDecision.RECORDED,
                summary = "Skill execution started: ${skill.name}"
            )

            val result = skillExecutor.execute(skill, goal, projectId, conversationId, execContext)

            // Update run count
            val updated = skill.copy(
                lastRunAt = System.currentTimeMillis(),
                runCount = skill.runCount + 1
            )
            runCatching { configStore.save(updated) }
            _skills.value = _skills.value + (skillId to updated)

            audit.log(
                type = when (result) {
                    is SkillExecutionResult.Success -> SecurityEventType.TOOL_EXECUTED
                    is SkillExecutionResult.Error -> SecurityEventType.TOOL_BLOCKED
                    is SkillExecutionResult.Denied -> SecurityEventType.PERMISSION_DENIED
                    is SkillExecutionResult.ValidationFailed -> SecurityEventType.TOOL_BLOCKED
                },
                decision = when (result) {
                    is SkillExecutionResult.Success -> AuditDecision.ALLOWED
                    is SkillExecutionResult.Error -> AuditDecision.RECORDED
                    is SkillExecutionResult.Denied -> AuditDecision.DENIED
                    is SkillExecutionResult.ValidationFailed -> AuditDecision.BLOCKED
                },
                summary = "Skill ${skill.name}: ${result::class.simpleName}"
            )

            return result
        } finally {
            executionContexts.remove(projectId)
        }
    }

    /** Get all enabled skills. */
    fun enabledSkills(): List<SkillDefinition> =
        _skills.value.values.filter { it.enabled }

    /** Get skills by source. */
    fun skillsBySource(source: SkillSource): List<SkillDefinition> =
        _skills.value.values.filter { it.source == source }

    /** Get a skill by ID. */
    fun getSkill(skillId: String): SkillDefinition? = _skills.value[skillId]
}

/** Exception thrown when skill validation fails. */
class SkillValidationException(message: String) : IllegalArgumentException(message)

/** Persistence port for skill configurations. */
interface SkillConfigStore {
    suspend fun getAll(): List<SkillDefinition>
    suspend fun getById(id: String): SkillDefinition?
    suspend fun save(skill: SkillDefinition)
    suspend fun delete(id: String)
}

/** In-memory implementation for testing. */
class InMemorySkillConfigStore : SkillConfigStore {
    private val skills = mutableMapOf<String, SkillDefinition>()

    override suspend fun getAll(): List<SkillDefinition> = skills.values.toList()
    override suspend fun getById(id: String): SkillDefinition? = skills[id]
    override suspend fun save(skill: SkillDefinition) { skills[skill.id] = skill }
    override suspend fun delete(id: String) { skills.remove(id) }
}
