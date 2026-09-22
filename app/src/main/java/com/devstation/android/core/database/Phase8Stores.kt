package com.devstation.android.core.database

import com.devstation.android.core.agent.profiles.AgentProfile
import com.devstation.android.core.agent.profiles.AgentProfileConfigStore
import com.devstation.android.core.agent.profiles.AgentProfileSecurityScope
import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.mcp.McpCapability
import com.devstation.android.core.mcp.McpCapabilityType
import com.devstation.android.core.mcp.McpConfigStore
import com.devstation.android.core.mcp.McpSecurityClassification
import com.devstation.android.core.mcp.McpServerConfig
import com.devstation.android.core.mcp.McpTransportType
import com.devstation.android.core.skills.SkillCapability
import com.devstation.android.core.skills.SkillConfigStore
import com.devstation.android.core.skills.SkillDefinition
import com.devstation.android.core.skills.SkillSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 8 Room-backed config stores. Convert between domain models and Room entities.
 * These are the production implementations; in-memory versions exist for testing.
 */

private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

/** Encode a list of strings to a JSON array string. */
private fun encodeStringList(list: List<String>): String =
    JsonArray(list.map { JsonPrimitive(it) }).toString()

/** Decode a JSON array string to a list of strings. */
private fun decodeStringList(raw: String): List<String> = runCatching {
    json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
}.getOrDefault(emptyList())

/** Encode a map of strings to a JSON object string. */
private fun encodeStringMap(map: Map<String, String>): String =
    JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString()

/** Decode a JSON object string to a map of strings. */
private fun decodeStringMap(raw: String): Map<String, String> = runCatching {
    json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.content }
}.getOrDefault(emptyMap())

// ---- MCP Server Store ----

class RoomMcpConfigStore(
    private val serverDao: McpServerDao,
    private val capabilityDao: McpCapabilityDao,
    private val dispatchers: DispatcherProvider
) : McpConfigStore {

    override suspend fun getAll(): List<McpServerConfig> = withContext(Dispatchers.IO) {
        serverDao.getAll().map { it.toDomain() }
    }

    override suspend fun getById(id: String): McpServerConfig? = withContext(Dispatchers.IO) {
        serverDao.getById(id)?.toDomain()
    }

    override suspend fun save(config: McpServerConfig) = withContext(Dispatchers.IO) {
        serverDao.upsert(config.toEntity())
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        serverDao.delete(id)
        capabilityDao.deleteForServer(id)
    }

    private fun McpServerEntity.toDomain() = McpServerConfig(
        id = id,
        name = name,
        description = description,
        transportType = runCatching { McpTransportType.valueOf(transportType) }.getOrDefault(McpTransportType.STDIO),
        command = command,
        arguments = decodeStringList(argumentsJson),
        environment = decodeStringMap(environmentJson),
        credentialReferenceId = credentialReferenceId,
        endpoint = endpoint,
        enabled = enabled,
        autoConnect = autoConnect,
        securityMode = securityMode,
        projectScope = projectScope,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun McpServerConfig.toEntity() = McpServerEntity(
        id = id,
        name = name,
        description = description,
        transportType = transportType.name,
        command = command,
        argumentsJson = encodeStringList(arguments),
        environmentJson = encodeStringMap(environment),
        credentialReferenceId = credentialReferenceId,
        endpoint = endpoint,
        enabled = enabled,
        autoConnect = autoConnect,
        securityMode = securityMode,
        projectScope = projectScope,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

// ---- MCP Capability Store ----

class RoomMcpCapabilityStore(
    private val capabilityDao: McpCapabilityDao
) {
    suspend fun forServer(serverId: String): List<McpCapability> =
        capabilityDao.forServer(serverId).map { it.toDomain() }

    suspend fun saveAll(serverId: String, capabilities: List<McpCapability>) {
        capabilityDao.deleteForServer(serverId)
        capabilityDao.upsertAll(capabilities.map { it.toEntity() })
    }

    private fun McpCapabilityEntity.toDomain() = McpCapability(
        id = id,
        serverId = serverId,
        capabilityType = runCatching { McpCapabilityType.valueOf(capabilityType) }.getOrDefault(McpCapabilityType.TOOL),
        name = name,
        description = description,
        enabled = enabled,
        securityClassification = runCatching {
            McpSecurityClassification.valueOf(securityClassification)
        }.getOrDefault(McpSecurityClassification.UNKNOWN)
    )

    private fun McpCapability.toEntity() = McpCapabilityEntity(
        id = id,
        serverId = serverId,
        capabilityType = capabilityType.name,
        name = name,
        description = description,
        discoveredAt = discoveredAt,
        enabled = enabled,
        securityClassification = securityClassification.name
    )
}

// ---- Skill Store ----

class RoomSkillConfigStore(
    private val skillDao: SkillDao,
    private val dispatchers: DispatcherProvider
) : SkillConfigStore {

    override suspend fun getAll(): List<SkillDefinition> = withContext(Dispatchers.IO) {
        skillDao.getAll().map { it.toDomain() }
    }

    override suspend fun getById(id: String): SkillDefinition? = withContext(Dispatchers.IO) {
        skillDao.getById(id)?.toDomain()
    }

    override suspend fun save(skill: SkillDefinition) = withContext(Dispatchers.IO) {
        skillDao.upsert(skill.toEntity())
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        skillDao.delete(id)
    }

    private fun SkillEntity.toDomain() = SkillDefinition(
        id = id,
        name = name,
        description = description,
        version = version,
        author = author,
        instructions = instructions,
        requiredTools = decodeStringList(requiredToolsJson),
        requestedCapabilities = runCatching {
            decodeStringList(requestedCapabilitiesJson).map { SkillCapability.valueOf(it) }
        }.getOrDefault(emptyList()),
        source = runCatching { SkillSource.valueOf(source) }.getOrDefault(SkillSource.USER),
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastRunAt = lastRunAt,
        runCount = runCount
    )

    private fun SkillDefinition.toEntity() = SkillEntity(
        id = id,
        name = name,
        description = description,
        version = version,
        author = author,
        instructions = instructions,
        requiredToolsJson = encodeStringList(requiredTools),
        requestedCapabilitiesJson = encodeStringList(requestedCapabilities.map { it.name }),
        source = source.name,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastRunAt = lastRunAt,
        runCount = runCount
    )
}

// ---- Agent Profile Store ----

class RoomAgentProfileConfigStore(
    private val profileDao: AgentProfileDao,
    private val dispatchers: DispatcherProvider
) : AgentProfileConfigStore {

    override suspend fun getAll(): List<AgentProfile> = withContext(Dispatchers.IO) {
        profileDao.getAll().map { it.toDomain() }
    }

    override suspend fun getById(id: String): AgentProfile? = withContext(Dispatchers.IO) {
        profileDao.getById(id)?.toDomain()
    }

    override suspend fun save(profile: AgentProfile) = withContext(Dispatchers.IO) {
        profileDao.upsert(profile.toEntity())
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        profileDao.delete(id)
    }

    private fun AgentProfileEntity.toDomain() = AgentProfile(
        id = id,
        name = name,
        description = description,
        systemInstructions = systemInstructions,
        providerId = providerId,
        modelId = modelId,
        enabledTools = decodeStringList(enabledToolsJson),
        enabledSkills = decodeStringList(enabledSkillsJson),
        enabledMcpServers = decodeStringList(enabledMcpServersJson),
        securityScope = runCatching {
            AgentProfileSecurityScope.valueOf(securityScope)
        }.getOrDefault(AgentProfileSecurityScope.BALANCED),
        projectScope = projectScope,
        maxIterations = maxIterations,
        maxToolCalls = maxToolCalls,
        maxTaskDurationMs = maxTaskDurationMs,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun AgentProfile.toEntity() = AgentProfileEntity(
        id = id,
        name = name,
        description = description,
        enabled = true,
        systemInstructions = systemInstructions,
        providerId = providerId,
        modelId = modelId,
        enabledToolsJson = encodeStringList(enabledTools),
        enabledSkillsJson = encodeStringList(enabledSkills),
        enabledMcpServersJson = encodeStringList(enabledMcpServers),
        securityScope = securityScope.name,
        projectScope = projectScope,
        maxIterations = maxIterations,
        maxToolCalls = maxToolCalls,
        maxTaskDurationMs = maxTaskDurationMs,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
