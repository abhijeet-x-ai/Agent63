package com.devstation.android.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Phase 8 Room v6 entities. Additive only — no Phase 1–5 table is modified.
 *
 * No raw secrets are stored in these tables. Credential references use IDs that map to
 * the existing SecureCredentialStore.
 */

// ---- MCP Servers ----

@Entity(
    tableName = "mcp_servers",
    indices = [Index("enabled")]
)
data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val transportType: String = "STDIO",
    val command: String = "",
    val argumentsJson: String = "[]",
    val environmentJson: String = "{}",
    val credentialReferenceId: String? = null,
    val endpoint: String? = null,
    val enabled: Boolean = true,
    val autoConnect: Boolean = false,
    val securityMode: String = "BALANCED",
    val projectScope: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "mcp_capabilities",
    indices = [Index("serverId"), Index("capabilityType")]
)
data class McpCapabilityEntity(
    @PrimaryKey val id: String,
    val serverId: String,
    val capabilityType: String,
    val name: String,
    val description: String = "",
    val inputSchemaJson: String = "{}",
    val outputMetadataJson: String = "{}",
    val discoveredAt: Long,
    val enabled: Boolean = true,
    val securityClassification: String = "UNKNOWN"
)

// ---- Skills ----

@Entity(
    tableName = "skills",
    indices = [Index("source"), Index("enabled")]
)
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val instructions: String,
    val requiredToolsJson: String = "[]",
    val requestedCapabilitiesJson: String = "[]",
    val securityProfileJson: String = "{}",
    val source: String,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long? = null,
    val runCount: Int = 0
)

// ---- Agent Profiles ----

@Entity(
    tableName = "agent_profiles",
    indices = [Index("enabled")]
)
data class AgentProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val systemInstructions: String = "",
    val providerId: String? = null,
    val modelId: String? = null,
    val enabledToolsJson: String = "[]",
    val enabledSkillsJson: String = "[]",
    val enabledMcpServersJson: String = "[]",
    val permissionProfileJson: String = "{}",
    val securityScope: String = "BALANCED",
    val projectScope: String? = null,
    val maxIterations: Int = 25,
    val maxToolCalls: Int = 50,
    val maxTaskDurationMs: Long = 600_000L,
    val createdAt: Long,
    val updatedAt: Long
)

// ---- DAOs ----

@Dao
interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY name ASC")
    suspend fun getAll(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers WHERE enabled = 1 ORDER BY name ASC")
    suspend fun getEnabled(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun getById(id: String): McpServerEntity?

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    fun observeById(id: String): Flow<McpServerEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: McpServerEntity)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface McpCapabilityDao {
    @Query("SELECT * FROM mcp_capabilities WHERE serverId = :serverId ORDER BY name ASC")
    suspend fun forServer(serverId: String): List<McpCapabilityEntity>

    @Query("SELECT * FROM mcp_capabilities WHERE serverId = :serverId AND capabilityType = :type ORDER BY name ASC")
    suspend fun forServerAndType(serverId: String, type: String): List<McpCapabilityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(capabilities: List<McpCapabilityEntity>)

    @Query("DELETE FROM mcp_capabilities WHERE serverId = :serverId")
    suspend fun deleteForServer(serverId: String)

    @Query("DELETE FROM mcp_capabilities")
    suspend fun deleteAll()
}

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY name ASC")
    suspend fun getAll(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE source = :source ORDER BY name ASC")
    suspend fun getBySource(source: String): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE enabled = 1 ORDER BY name ASC")
    suspend fun getEnabled(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun getById(id: String): SkillEntity?

    @Query("SELECT * FROM skills WHERE id = :id")
    fun observeById(id: String): Flow<SkillEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: SkillEntity)

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AgentProfileDao {
    @Query("SELECT * FROM agent_profiles ORDER BY name ASC")
    suspend fun getAll(): List<AgentProfileEntity>

    @Query("SELECT * FROM agent_profiles WHERE enabled = 1 ORDER BY name ASC")
    suspend fun getEnabled(): List<AgentProfileEntity>

    @Query("SELECT * FROM agent_profiles WHERE id = :id")
    suspend fun getById(id: String): AgentProfileEntity?

    @Query("SELECT * FROM agent_profiles WHERE id = :id")
    fun observeById(id: String): Flow<AgentProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: AgentProfileEntity)

    @Query("DELETE FROM agent_profiles WHERE id = :id")
    suspend fun delete(id: String)
}
