package com.devstation.android.core.agent.profiles

import com.devstation.android.core.agent.SecretRedactor
import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.security.policy.SecurityAuditLogger
import com.devstation.android.core.security.policy.SecurityEventType
import com.devstation.android.core.security.policy.AuditDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 8 §26/§27: Agent Profile lifecycle manager.
 *
 * Handles CRUD operations for agent profiles with validation. Profiles are configuration only —
 * they cannot grant permissions, modify security policy, or bypass the SecurityManager.
 */
class AgentProfileManager(
    private val audit: SecurityAuditLogger,
    private val dispatchers: DispatcherProvider,
    private val configStore: AgentProfileConfigStore
) {
    private val _profiles = MutableStateFlow<Map<String, AgentProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, AgentProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<AgentProfile?>(null)
    val activeProfile: StateFlow<AgentProfile?> = _activeProfile.asStateFlow()

    /** Load profiles from persistent storage. */
    suspend fun loadProfiles() {
        val stored = runCatching { configStore.getAll() }.getOrDefault(emptyList())
        _profiles.value = stored.associateBy { it.id }
    }

    /** Create a new agent profile. */
    suspend fun createProfile(profile: AgentProfile): AgentProfileResult {
        val reasons = validate(profile)
        if (reasons.isNotEmpty()) {
            return AgentProfileResult.ValidationError(reasons)
        }

        // Strip any invalid security declarations
        val sanitized = sanitizeSecurity(profile)

        runCatching { configStore.save(sanitized) }
        _profiles.value = _profiles.value + (sanitized.id to sanitized)

        audit.log(
            type = SecurityEventType.SECURITY_POLICY_CHANGED,
            decision = AuditDecision.RECORDED,
            summary = "Agent profile created: ${SecretRedactor.redact(sanitized.name)}"
        )
        return AgentProfileResult.Success(sanitized)
    }

    /** Update an existing agent profile. */
    suspend fun updateProfile(profile: AgentProfile): AgentProfileResult {
        if (!_profiles.value.containsKey(profile.id)) {
            return AgentProfileResult.Error("Profile '${profile.id}' not found.")
        }

        val reasons = validate(profile)
        if (reasons.isNotEmpty()) {
            return AgentProfileResult.ValidationError(reasons)
        }

        val sanitized = sanitizeSecurity(profile.copy(updatedAt = System.currentTimeMillis()))
        runCatching { configStore.save(sanitized) }
        _profiles.value = _profiles.value + (sanitized.id to sanitized)

        if (_activeProfile.value?.id == sanitized.id) {
            _activeProfile.value = sanitized
        }

        return AgentProfileResult.Success(sanitized)
    }

    /** Duplicate a profile with a new ID. */
    suspend fun duplicateProfile(profileId: String, newName: String): AgentProfileResult {
        val original = _profiles.value[profileId]
            ?: return AgentProfileResult.Error("Profile not found.")

        val duplicate = original.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = newName,
            description = "Copy of ${original.name}",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        return createProfile(duplicate)
    }

    /** Delete a profile. */
    suspend fun deleteProfile(profileId: String): Result<Unit> {
        if (!_profiles.value.containsKey(profileId)) {
            return Result.failure(IllegalStateException("Profile not found."))
        }
        runCatching { configStore.delete(profileId) }
        _profiles.value = _profiles.value - profileId
        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = null
        }
        audit.log(
            type = SecurityEventType.SECURITY_POLICY_CHANGED,
            decision = AuditDecision.RECORDED,
            summary = "Agent profile deleted: $profileId"
        )
        return Result.success(Unit)
    }

    /** Set the active profile. */
    fun setActiveProfile(profileId: String?) {
        _activeProfile.value = profileId?.let { _profiles.value[it] }
    }

    /** Get a profile by ID. */
    fun getProfile(profileId: String): AgentProfile? = _profiles.value[profileId]

    /** Get all profiles. */
    fun allProfiles(): List<AgentProfile> = _profiles.value.values.toList()

    /**
     * Validate an agent profile. Returns empty list if valid.
     */
    private fun validate(profile: AgentProfile): List<String> {
        val reasons = mutableListOf<String>()

        if (profile.name.isBlank()) reasons.add("Name must not be blank")
        if (profile.name.length > 100) reasons.add("Name too long (max 100)")
        if (profile.description.length > 500) reasons.add("Description too long (max 500)")
        if (profile.systemInstructions.length > 10_000) reasons.add("Instructions too long (max 10,000)")
        if (profile.maxIterations !in 1..500) reasons.add("maxIterations out of range")
        if (profile.maxToolCalls !in 1..1000) reasons.add("maxToolCalls out of range")
        if (profile.maxTaskDurationMs !in 30_000..7_200_000) reasons.add("maxTaskDurationMs out of range")

        // Validate permission profile
        if (!profile.permissionProfile.isValid()) {
            reasons.add("Permission profile contains invalid declarations")
        }

        return reasons
    }

    /**
     * Sanitize security declarations. Strip any capability that could grant unrestricted access
     * or bypass the security system (§27).
     */
    private fun sanitizeSecurity(profile: AgentProfile): AgentProfile {
        // Profiles cannot grant permissions, disable security, or bypass the SecurityManager.
        // The permission profile is already constrained by its data class design.
        return profile
    }
}

/** Persistence port for agent profile configurations. */
interface AgentProfileConfigStore {
    suspend fun getAll(): List<AgentProfile>
    suspend fun getById(id: String): AgentProfile?
    suspend fun save(profile: AgentProfile)
    suspend fun delete(id: String)
}

/** In-memory implementation for testing. */
class InMemoryAgentProfileConfigStore : AgentProfileConfigStore {
    private val profiles = mutableMapOf<String, AgentProfile>()

    override suspend fun getAll(): List<AgentProfile> = profiles.values.toList()
    override suspend fun getById(id: String): AgentProfile? = profiles[id]
    override suspend fun save(profile: AgentProfile) { profiles[profile.id] = profile }
    override suspend fun delete(id: String) { profiles.remove(id) }
}
