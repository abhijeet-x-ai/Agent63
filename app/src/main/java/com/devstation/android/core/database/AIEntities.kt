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
 * Phase 5 AI provider configuration. Stores ONLY metadata — the API key itself
 * lives in Android Keystore-backed SecureCredentialStore, referenced by [credentialId].
 */
@Entity(tableName = "ai_provider_configs", indices = [Index("credentialId")])
data class AIProviderConfigEntity(
    @PrimaryKey val providerId: String,
    val displayName: String,
    val enabled: Boolean = true,
    val credentialId: String? = null,
    val baseUrlOverride: String? = null,
    val defaultModelId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastConnectionCheckAt: Long? = null,
    /** One of: CONNECTED, AUTH_FAILED, NETWORK_ERROR, SERVER_ERROR, NOT_CONFIGURED */
    val lastConnectionStatus: String? = null
)

@Entity(
    tableName = "ai_model_cache",
    primaryKeys = ["providerId", "modelId"]
)
data class AIModelCacheEntity(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val contextWindow: Long? = null,
    val capabilitiesCsv: String = "",
    val inputPricing: Double? = null,
    val outputPricing: Double? = null,
    val enabled: Boolean = true,
    val lastUpdated: Long
)

@Entity(tableName = "ai_usage_records", indices = [Index("conversationId"), Index("createdAt")])
data class AIUsageRecordEntity(
    @PrimaryKey val id: String,
    val conversationId: String? = null,
    val messageId: String? = null,
    val providerId: String,
    val modelId: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val cachedTokens: Long? = null,
    val reasoningTokens: Long? = null,
    /** Stored only when usage AND verified pricing both exist; otherwise null. */
    val estimatedCostUsd: Double? = null,
    val createdAt: Long
)

@Entity(tableName = "ai_settings")
data class AISettingsEntity(
    @PrimaryKey val id: Int = 1,
    val defaultProviderId: String? = null,
    val defaultModelId: String? = null,
    val streamingEnabled: Boolean = true,
    val showUsage: Boolean = true,
    val showEstimatedCost: Boolean = true,
    val saveFailedRequests: Boolean = true,
    val connectTimeoutSeconds: Long = 15L,
    val readTimeoutSeconds: Long = 120L,
    val retryCount: Int = 2,
    /** Maximum characters of conversation context sent in one request. */
    val maxPayloadChars: Int = 128_000
)

@Dao
interface AIProviderConfigDao {
    @Query("SELECT * FROM ai_provider_configs")
    fun getAllFlow(): Flow<List<AIProviderConfigEntity>>

    @Query("SELECT * FROM ai_provider_configs")
    suspend fun getAll(): List<AIProviderConfigEntity>

    @Query("SELECT * FROM ai_provider_configs WHERE providerId = :providerId")
    suspend fun getById(providerId: String): AIProviderConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: AIProviderConfigEntity)

    @Query("DELETE FROM ai_provider_configs WHERE providerId = :providerId")
    suspend fun deleteById(providerId: String)

    @Query("UPDATE ai_provider_configs SET defaultModelId = :modelId, updatedAt = :now WHERE providerId = :providerId")
    suspend fun updateDefaultModel(providerId: String, modelId: String?, now: Long)

    @Query("UPDATE ai_provider_configs SET enabled = :enabled, updatedAt = :now WHERE providerId = :providerId")
    suspend fun updateEnabled(providerId: String, enabled: Boolean, now: Long)

    @Query(
        "UPDATE ai_provider_configs SET lastConnectionCheckAt = :checkedAt, lastConnectionStatus = :status, updatedAt = :now WHERE providerId = :providerId"
    )
    suspend fun updateConnectionCheck(providerId: String, checkedAt: Long, status: String, now: Long)
}

@Dao
interface AIModelCacheDao {
    @Query("SELECT * FROM ai_model_cache WHERE providerId = :providerId ORDER BY modelId ASC")
    fun getForProviderFlow(providerId: String): Flow<List<AIModelCacheEntity>>

    @Query("SELECT * FROM ai_model_cache WHERE providerId = :providerId ORDER BY modelId ASC")
    suspend fun getForProvider(providerId: String): List<AIModelCacheEntity>

    @Query("SELECT * FROM ai_model_cache WHERE providerId = :providerId AND modelId = :modelId")
    suspend fun get(providerId: String, modelId: String): AIModelCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(models: List<AIModelCacheEntity>)

    @Query("DELETE FROM ai_model_cache WHERE providerId = :providerId")
    suspend fun deleteForProvider(providerId: String)
}

@Dao
interface AIUsageRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: AIUsageRecordEntity)

    @Query("SELECT * FROM ai_usage_records WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getForConversation(conversationId: String): List<AIUsageRecordEntity>

    @Query("DELETE FROM ai_usage_records WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)
}

@Dao
interface AISettingsDao {
    @Query("SELECT * FROM ai_settings WHERE id = 1")
    fun getFlow(): Flow<AISettingsEntity?>

    @Query("SELECT * FROM ai_settings WHERE id = 1")
    suspend fun get(): AISettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AISettingsEntity)
}
