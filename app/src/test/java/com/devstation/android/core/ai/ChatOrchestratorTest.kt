package com.devstation.android.core.ai

import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.database.ConversationDao
import com.devstation.android.core.database.ConversationEntity
import com.devstation.android.core.database.MessageDao
import com.devstation.android.core.database.MessageEntity
import com.devstation.android.core.repository.ConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unconfined dispatcher provider for pure-JVM tests. */
private val OrchestratorTestDispatchers = object : DispatcherProvider {
    override val main = Dispatchers.Unconfined
    override val io = Dispatchers.Unconfined
    override val default = Dispatchers.Unconfined
    override val unconfined = Dispatchers.Unconfined
}

class ChatOrchestratorTest {

    private val orchestrator = ChatOrchestrator(
        providerManager = object : AIProviderManager {
            override val providers: List<AIProvider> = emptyList()
            override fun getProvider(providerId: String): AIProvider? = null
            override fun registerProvider(provider: AIProvider) = Unit
            override suspend fun resolveModel(providerId: String, modelId: String?): Result<AIModel> =
                Result.failure(AIError.ProviderUnavailableError("not registered"))
        },
        conversationRepository = ConversationRepository(
            conversationDao = object : ConversationDao {
                override fun getAllConversationsFlow(): Flow<List<ConversationEntity>> = flowOf(emptyList())
                override fun getPinnedConversationsFlow(): Flow<List<ConversationEntity>> = flowOf(emptyList())
                override fun getRecentConversationsFlow(limit: Int): Flow<List<ConversationEntity>> = flowOf(emptyList())
                override fun getConversationsByProjectFlow(projectId: String): Flow<List<ConversationEntity>> = flowOf(emptyList())
                override suspend fun getConversationById(id: String) = null
                override suspend fun insertConversation(conversation: ConversationEntity) = Unit
                override suspend fun updateConversation(conversation: ConversationEntity) = Unit
                override suspend fun updatePinStatus(id: String, isPinned: Boolean, updatedAt: Long) = Unit
                override suspend fun updateTitle(id: String, newTitle: String, updatedAt: Long) = Unit
                override suspend fun updateConversationModel(id: String, providerId: String?, modelId: String?, updatedAt: Long) = Unit
                override suspend fun deleteConversationById(id: String) = Unit
            },
            messageDao = object : MessageDao {
                override fun getMessagesForConversationFlow(conversationId: String): Flow<List<MessageEntity>> = flowOf(emptyList())
                override suspend fun getMessagesForConversation(conversationId: String) = emptyList<MessageEntity>()
                override suspend fun insertMessage(message: MessageEntity) = Unit
                override suspend fun updateMessageErrorState(messageId: String, errorState: String?) = Unit
                override suspend fun deleteMessagesForConversation(conversationId: String) = Unit
            },
            dispatchers = OrchestratorTestDispatchers
        ),
        aiSettingsRepository = object : com.devstation.android.core.repository.AISettingsRepository(
            dao = object : com.devstation.android.core.database.AISettingsDao {
                override fun getFlow() = throw UnsupportedOperationException()
                override suspend fun get(): com.devstation.android.core.database.AISettingsEntity? = null
                override suspend fun upsert(settings: com.devstation.android.core.database.AISettingsEntity) = Unit
            },
            dispatchers = OrchestratorTestDispatchers
        ) {
            override suspend fun get(): com.devstation.android.core.database.AISettingsEntity = com.devstation.android.core.database.AISettingsEntity()
        },
        usageRepository = object : com.devstation.android.core.repository.AIUsageRepository(
            dao = object : com.devstation.android.core.database.AIUsageRecordDao {
                override suspend fun insert(record: com.devstation.android.core.database.AIUsageRecordEntity) = Unit
                override suspend fun getForConversation(conversationId: String) = emptyList<com.devstation.android.core.database.AIUsageRecordEntity>()
                override suspend fun deleteForConversation(conversationId: String) = Unit
            },
            dispatchers = OrchestratorTestDispatchers
        ) {},
        dispatchers = OrchestratorTestDispatchers
    )

    @Test
    fun `token estimate is proportional to length`() {
        assertEquals(0, orchestrator.estimateTokens(""))
        assertEquals(25, orchestrator.estimateTokens("a".repeat(100)))
        assertTrue(orchestrator.estimateTokens("a".repeat(401)) >= 101)
    }

    @Test
    fun `no context window means no exceed`() {
        val model = AIModel(providerId = "p", modelId = "m", displayName = "m", contextWindow = null)
        val check = orchestrator.checkContext(model, listOf(AIMessage(role = AIMessageRole.USER, content = "hello world")), null)
        assertFalse(check.exceedsContext)
        assertFalse(check.nearLimit)
        assertEquals(3, check.estimatedTokens) // 11 chars / 4 = ceil 2.75 = 3
    }

    @Test
    fun `near limit detected above 80 percent`() {
        val model = AIModel(providerId = "p", modelId = "m", displayName = "m", contextWindow = 100)
        val messages = listOf(AIMessage(role = AIMessageRole.USER, content = "a".repeat(340))) // 85 tokens
        val check = orchestrator.checkContext(model, messages, null)
        assertTrue(check.nearLimit)
        assertFalse(check.exceedsContext)
    }

    @Test
    fun `exceeds context above 95 percent`() {
        val model = AIModel(providerId = "p", modelId = "m", displayName = "m", contextWindow = 100)
        val messages = listOf(AIMessage(role = AIMessageRole.USER, content = "a".repeat(400))) // 100 tokens
        val check = orchestrator.checkContext(model, messages, null)
        assertTrue(check.exceedsContext)
    }

    @Test
    fun `system instruction counted in estimate`() {
        val model = AIModel(providerId = "p", modelId = "m", displayName = "m", contextWindow = null)
        val check = orchestrator.checkContext(
            model,
            listOf(AIMessage(role = AIMessageRole.USER, content = "")),
            systemInstruction = "a".repeat(80)
        )
        assertEquals(20, check.estimatedTokens)
    }
}
