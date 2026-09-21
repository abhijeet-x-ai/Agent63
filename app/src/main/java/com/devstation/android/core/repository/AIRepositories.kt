package com.devstation.android.core.repository

import com.devstation.android.core.ai.AIModel
import com.devstation.android.core.ai.AIProviderCapabilities
import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.database.AIModelCacheDao
import com.devstation.android.core.database.AIModelCacheEntity
import com.devstation.android.core.database.AIProviderConfigDao
import com.devstation.android.core.database.AIProviderConfigEntity
import com.devstation.android.core.database.AISettingsDao
import com.devstation.android.core.database.AISettingsEntity
import com.devstation.android.core.database.AIUsageRecordDao
import com.devstation.android.core.database.AIUsageRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Phase 5 repositories over the new AI tables. All metadata only — no secrets.
 */
// Classes and methods are `open` so pure-JVM unit tests can provide in-memory fakes.
open class AIProviderConfigRepository(
    private val dao: AIProviderConfigDao,
    private val dispatchers: DispatcherProvider
) {
    open fun observeAll(): Flow<List<AIProviderConfigEntity>> = dao.getAllFlow()

    open suspend fun get(providerId: String): AIProviderConfigEntity? = withContext(dispatchers.io) {
        dao.getById(providerId)
    }

    open suspend fun upsert(config: AIProviderConfigEntity) = withContext(dispatchers.io) {
        dao.upsert(config)
    }

    open suspend fun delete(providerId: String) = withContext(dispatchers.io) {
        dao.deleteById(providerId)
    }

    open suspend fun setDefaultModel(providerId: String, modelId: String?) = withContext(dispatchers.io) {
        dao.updateDefaultModel(providerId, modelId, System.currentTimeMillis())
    }

    open suspend fun setEnabled(providerId: String, enabled: Boolean) = withContext(dispatchers.io) {
        dao.updateEnabled(providerId, enabled, System.currentTimeMillis())
    }

    open suspend fun recordConnectionCheck(providerId: String, status: String) = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        dao.updateConnectionCheck(providerId, now, status, now)
    }

    companion object {
        const val STATUS_CONNECTED = "CONNECTED"
        const val STATUS_AUTH_FAILED = "AUTH_FAILED"
        const val STATUS_NETWORK_ERROR = "NETWORK_ERROR"
        const val STATUS_SERVER_ERROR = "SERVER_ERROR"
        const val STATUS_NOT_CONFIGURED = "NOT_CONFIGURED"
    }
}

open class AIModelCacheRepository(
    private val dao: AIModelCacheDao,
    private val dispatchers: DispatcherProvider
) {
    open fun observeForProvider(providerId: String): Flow<List<AIModelCacheEntity>> =
        dao.getForProviderFlow(providerId)

    open suspend fun getForProvider(providerId: String): List<AIModelCacheEntity> =
        withContext(dispatchers.io) { dao.getForProvider(providerId) }

    open suspend fun get(providerId: String, modelId: String): AIModelCacheEntity? =
        withContext(dispatchers.io) { dao.get(providerId, modelId) }

    open suspend fun replaceForProvider(providerId: String, models: List<AIModel>) =
        withContext(dispatchers.io) {
            val now = System.currentTimeMillis()
            dao.deleteForProvider(providerId)
            dao.upsertAll(
                models.map { model ->
                    AIModelCacheEntity(
                        providerId = providerId,
                        modelId = model.modelId,
                        displayName = model.displayName,
                        contextWindow = model.contextWindow,
                        capabilitiesCsv = capabilitiesToCsv(model.capabilities),
                        inputPricing = model.inputPricing,
                        outputPricing = model.outputPricing,
                        enabled = model.enabled,
                        lastUpdated = now
                    )
                }
            )
        }

    suspend fun toDomainModel(entity: AIModelCacheEntity): AIModel = AIModel(
        providerId = entity.providerId,
        modelId = entity.modelId,
        displayName = entity.displayName,
        contextWindow = entity.contextWindow,
        capabilities = csvToCapabilities(entity.capabilitiesCsv),
        inputPricing = entity.inputPricing,
        outputPricing = entity.outputPricing,
        enabled = entity.enabled,
        lastUpdated = entity.lastUpdated
    )

    private fun capabilitiesToCsv(capabilities: AIProviderCapabilities): String {
        val flags = mutableListOf<String>()
        if (capabilities.chat) flags.add("chat")
        if (capabilities.streaming) flags.add("streaming")
        if (capabilities.vision) flags.add("vision")
        if (capabilities.toolCalling) flags.add("toolCalling")
        if (capabilities.embeddings) flags.add("embeddings")
        if (capabilities.modelListing) flags.add("modelListing")
        if (capabilities.usageReporting) flags.add("usageReporting")
        if (capabilities.systemPrompt) flags.add("systemPrompt")
        if (capabilities.maxContext) flags.add("maxContext")
        if (capabilities.reasoning) flags.add("reasoning")
        if (capabilities.fileInput) flags.add("fileInput")
        return flags.joinToString(",")
    }

    private fun csvToCapabilities(csv: String): AIProviderCapabilities {
        val set = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return AIProviderCapabilities(
            chat = "chat" in set,
            streaming = "streaming" in set,
            vision = "vision" in set,
            toolCalling = "toolCalling" in set,
            embeddings = "embeddings" in set,
            modelListing = "modelListing" in set,
            usageReporting = "usageReporting" in set,
            systemPrompt = "systemPrompt" in set,
            maxContext = "maxContext" in set,
            reasoning = "reasoning" in set,
            fileInput = "fileInput" in set
        )
    }
}

open class AISettingsRepository(
    private val dao: AISettingsDao,
    private val dispatchers: DispatcherProvider
) {
    fun observe(): Flow<AISettingsEntity> = dao.getFlow().map { it ?: AISettingsEntity() }

    open suspend fun get(): AISettingsEntity = withContext(dispatchers.io) {
        dao.get() ?: AISettingsEntity().also { dao.upsert(it) }
    }

    open suspend fun save(settings: AISettingsEntity) = withContext(dispatchers.io) {
        dao.upsert(settings.copy(id = 1))
    }
}

open class AIUsageRepository(
    private val dao: AIUsageRecordDao,
    private val dispatchers: DispatcherProvider
) {
    open suspend fun record(
        conversationId: String?,
        messageId: String?,
        providerId: String,
        modelId: String,
        inputTokens: Long?,
        outputTokens: Long?,
        totalTokens: Long?,
        cachedTokens: Long?,
        reasoningTokens: Long?,
        estimatedCostUsd: Double?
    ) = withContext(dispatchers.io) {
        dao.insert(
            AIUsageRecordEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                messageId = messageId,
                providerId = providerId,
                modelId = modelId,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = totalTokens,
                cachedTokens = cachedTokens,
                reasoningTokens = reasoningTokens,
                estimatedCostUsd = estimatedCostUsd,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getForConversation(conversationId: String): List<AIUsageRecordEntity> =
        withContext(dispatchers.io) { dao.getForConversation(conversationId) }
}
