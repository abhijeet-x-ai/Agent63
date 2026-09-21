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
 * Phase 7 security persistence. Additive only — no Phase 1–6 table is modified.
 *
 * Nothing in these tables may contain a secret: policy values, scopes and *summaries* only.
 */

/** Single-row app-wide security policy (mode + category policies + retention). */
@Entity(tableName = "security_settings")
data class SecuritySettingsEntity(
    @PrimaryKey val id: Int = 1,
    /** Serialized [com.devstation.android.core.security.policy.SecurityPolicy]; no secrets. */
    val policyJson: String,
    val updatedAt: Long
)

/**
 * Per-project security settings (§39). One row per project; a disabled category is a hard DENY for
 * agent activity in that project.
 */
@Entity(tableName = "project_security_settings", indices = [Index("projectId")])
data class ProjectSecuritySettingsEntity(
    @PrimaryKey val projectId: String,
    val allowFileModification: Boolean = true,
    val allowTerminal: Boolean = true,
    val allowNetwork: Boolean = true,
    val allowPackageInstallation: Boolean = true,
    val allowSensitiveFileAccess: Boolean = true,
    val updatedAt: Long
)

/** §34 audit trail. Summaries are redacted and bounded before they reach this table. */
@Entity(tableName = "security_events", indices = [Index("timestamp"), Index("projectId"), Index("taskId")])
data class SecurityEventEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    /** PERMISSION_REQUESTED, TOOL_EXECUTED, PATH_BLOCKED, … */
    val type: String,
    /** ALLOWED, ASKED, DENIED, BLOCKED, RECORDED */
    val decision: String,
    val risk: String,
    val projectId: String? = null,
    val taskId: String? = null,
    val sessionId: String? = null,
    val agentId: String? = null,
    val toolName: String? = null,
    val action: String,
    val resourceType: String,
    val summary: String
)

/**
 * Temporary grants (§4/§31/§61). Phase 6's `agent_task_permissions` table still holds task grants;
 * this table holds session- and project-scoped grants with an explicit expiry so nothing can
 * outlive its scope.
 */
@Entity(tableName = "permission_grants", indices = [Index("scope"), Index("taskId"), Index("sessionId"), Index("projectId")])
data class PermissionGrantEntity(
    @PrimaryKey val id: String,
    /** SESSION or PROJECT (REQUEST/TASK grants are never persisted here). */
    val scope: String,
    val projectId: String? = null,
    val taskId: String? = null,
    val sessionId: String? = null,
    val toolName: String,
    val grantedAt: Long,
    /** Epoch millis when this grant stops applying; null = until its scope ends. */
    val expiresAt: Long? = null
)

@Dao
interface SecuritySettingsDao {
    @Query("SELECT * FROM security_settings WHERE id = 1")
    suspend fun get(): SecuritySettingsEntity?

    @Query("SELECT * FROM security_settings WHERE id = 1")
    fun observe(): Flow<SecuritySettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: SecuritySettingsEntity)
}

@Dao
interface ProjectSecuritySettingsDao {
    @Query("SELECT * FROM project_security_settings WHERE projectId = :projectId")
    suspend fun get(projectId: String): ProjectSecuritySettingsEntity?

    @Query("SELECT * FROM project_security_settings")
    fun observeAll(): Flow<List<ProjectSecuritySettingsEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: ProjectSecuritySettingsEntity)

    @Query("DELETE FROM project_security_settings WHERE projectId = :projectId")
    suspend fun delete(projectId: String)
}

@Dao
interface SecurityEventDao {
    @Query("SELECT * FROM security_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<SecurityEventEntity>

    @Query("SELECT * FROM security_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<SecurityEventEntity>>

    @Query("SELECT * FROM security_events WHERE taskId = :taskId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun forTask(taskId: String, limit: Int = 200): List<SecurityEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: SecurityEventEntity)

    @Query("DELETE FROM security_events WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long): Int

    @Query("DELETE FROM security_events")
    suspend fun deleteAll(): Int
}

@Dao
interface PermissionGrantDao {
    @Query("SELECT * FROM permission_grants ORDER BY grantedAt DESC")
    suspend fun all(): List<PermissionGrantEntity>

    @Query("SELECT * FROM permission_grants ORDER BY grantedAt DESC")
    fun observeAll(): Flow<List<PermissionGrantEntity>>

    @Query(
        "SELECT toolName FROM permission_grants WHERE scope = :scope AND toolName = :toolName " +
            "AND (expiresAt IS NULL OR expiresAt > :now) " +
            "AND (:taskId IS NULL OR taskId = :taskId) " +
            "AND (:sessionId IS NULL OR sessionId = :sessionId) " +
            "AND (:projectId IS NULL OR projectId = :projectId) LIMIT 1"
    )
    suspend fun findGrant(
        scope: String,
        toolName: String,
        taskId: String?,
        sessionId: String?,
        projectId: String?,
        now: Long
    ): String?

    @Query(
        "SELECT toolName FROM permission_grants WHERE scope = :scope AND (expiresAt IS NULL OR expiresAt > :now) " +
            "AND (:sessionId IS NULL OR sessionId = :sessionId) " +
            "AND (:projectId IS NULL OR projectId = :projectId)"
    )
    suspend fun toolNamesFor(scope: String, sessionId: String?, projectId: String?, now: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(grant: PermissionGrantEntity)

    @Query("SELECT * FROM permission_grants WHERE scope IN ('PROJECT', 'GLOBAL') AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY grantedAt DESC")
    suspend fun activeScopedGrants(now: Long): List<PermissionGrantEntity>

    @Query("DELETE FROM permission_grants WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String): Int

    @Query("DELETE FROM permission_grants WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String): Int

    @Query("DELETE FROM permission_grants WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String): Int

    @Query("DELETE FROM permission_grants WHERE scope = :scope")
    suspend fun deleteForScope(scope: String): Int

    @Query("DELETE FROM permission_grants WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("DELETE FROM permission_grants")
    suspend fun deleteAll(): Int
}
