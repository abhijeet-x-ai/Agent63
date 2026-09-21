package com.devstation.android.core.ai

import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.database.AIProviderConfigEntity
import com.devstation.android.core.repository.AIProviderConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAIProviderTest {

    @Test
    fun `emits deterministic test response`() = runTest {
        val provider = FakeAIProvider()
        val request = AIRequest(modelId = "fake-mini", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "ping")))
        val events = provider.generate(request).toList()

        assertEquals("fake", events.filterIsInstance<AIResponseEvent.Started>().first().providerId)
        val text = events.filterIsInstance<AIResponseEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("DevStation test response. ", text)
        assertNotNull(events.filterIsInstance<AIResponseEvent.Completed>().firstOrNull())
        assertNotNull(events.filterIsInstance<AIResponseEvent.Usage>().firstOrNull())
    }

    @Test
    fun `emits scripted error when configured`() = runTest {
        val provider = FakeAIProvider(scriptedError = AIError.RateLimitError())
        val events = provider.generate(AIRequest(modelId = "m", messages = emptyList())).toList()
        assertTrue(events.filterIsInstance<AIResponseEvent.Error>().first().error is AIError.RateLimitError)
    }
}

object TestDispatchers : DispatcherProvider {
    // Unconfined avoids scheduler conflicts with each test's runTest dispatcher.
    override val main = Dispatchers.Unconfined
    override val io = Dispatchers.Unconfined
    override val default = Dispatchers.Unconfined
    override val unconfined = Dispatchers.Unconfined
}

/** In-memory config repository double for manager tests (pure JVM, no Android). */
open class FakeConfigRepository : AIProviderConfigRepository(
    dao = object : com.devstation.android.core.database.AIProviderConfigDao {
        override fun getAllFlow(): Flow<List<AIProviderConfigEntity>> = MutableStateFlow(emptyList())
        override suspend fun getAll(): List<AIProviderConfigEntity> = emptyList()
        override suspend fun getById(providerId: String): AIProviderConfigEntity? = null
        override suspend fun upsert(config: AIProviderConfigEntity) = Unit
        override suspend fun deleteById(providerId: String) = Unit
        override suspend fun updateDefaultModel(providerId: String, modelId: String?, now: Long) = Unit
        override suspend fun updateEnabled(providerId: String, enabled: Boolean, now: Long) = Unit
        override suspend fun updateConnectionCheck(providerId: String, checkedAt: Long, status: String, now: Long) = Unit
    },
    dispatchers = TestDispatchers
) {
    private val configs = MutableStateFlow<Map<String, AIProviderConfigEntity>>(emptyMap())

    override fun observeAll(): Flow<List<AIProviderConfigEntity>> =
        MutableStateFlow(configs.value.values.toList())

    override suspend fun get(providerId: String): AIProviderConfigEntity? = configs.value[providerId]

    override suspend fun upsert(config: AIProviderConfigEntity) {
        configs.value = configs.value + (config.providerId to config)
    }

    override suspend fun delete(providerId: String) {
        configs.value = configs.value - providerId
    }

    override suspend fun setDefaultModel(providerId: String, modelId: String?) {
        val c = configs.value[providerId] ?: return
        upsert(c.copy(defaultModelId = modelId))
    }

    override suspend fun setEnabled(providerId: String, enabled: Boolean) {
        val c = configs.value[providerId] ?: return
        upsert(c.copy(enabled = enabled))
    }

    override suspend fun recordConnectionCheck(providerId: String, status: String) {
        val c = configs.value[providerId] ?: return
        upsert(c.copy(lastConnectionStatus = status, lastConnectionCheckAt = 0L))
    }
}

class ProviderRegistrationTest {

    private fun stubCredentialStore() = object : com.devstation.android.core.security.SecureCredentialStore {
        override fun storeSecret(alias: String, secret: String) = Result.success(Unit)
        override fun getSecret(alias: String) = Result.success<String?>(null)
        override fun removeSecret(alias: String) = Result.success(Unit)
        override fun hasSecret(alias: String) = false
        override fun listAliases() = emptyList<String>()
    }

    private fun buildManager(configRepo: AIProviderConfigRepository): DefaultAIProviderManager =
        DefaultAIProviderManager(
            httpClient = AIHttpClient(),
            credentials = AiCredentialManager(stubCredentialStore()),
            configRepository = configRepo,
            modelCacheRepository = object : com.devstation.android.core.repository.AIModelCacheRepository(
                dao = object : com.devstation.android.core.database.AIModelCacheDao {
                    override fun getForProviderFlow(providerId: String) = MutableStateFlow(emptyList<com.devstation.android.core.database.AIModelCacheEntity>())
                    override suspend fun getForProvider(providerId: String) = emptyList<com.devstation.android.core.database.AIModelCacheEntity>()
                    override suspend fun get(providerId: String, modelId: String): com.devstation.android.core.database.AIModelCacheEntity? = null
                    override suspend fun upsertAll(models: List<com.devstation.android.core.database.AIModelCacheEntity>) = Unit
                    override suspend fun deleteForProvider(providerId: String) = Unit
                },
                dispatchers = TestDispatchers
            ) {
                override suspend fun get(providerId: String, modelId: String) = null
                override suspend fun getForProvider(providerId: String) = emptyList<com.devstation.android.core.database.AIModelCacheEntity>()
                override suspend fun replaceForProvider(providerId: String, models: List<AIModel>) = Unit
            },
            dispatchers = TestDispatchers
        )

    @Test
    fun `registers and looks up providers`() {
        val manager = buildManager(FakeConfigRepository())
        val fake = FakeAIProvider()
        manager.registerProvider(fake)
        assertEquals(fake, manager.getProvider("fake"))
        assertEquals(null, manager.getProvider("missing"))
        assertTrue(manager.providers.any { it.providerId == "fake" })
    }

    @Test
    fun `resolveModel synthesizes model when no cache exists`() = runTest {
        val manager = buildManager(FakeConfigRepository())
        manager.registerProvider(FakeAIProvider())
        val resolved = manager.resolveModel("fake", "fake-large").getOrThrow()
        assertEquals("fake-large", resolved.modelId)
        assertEquals("fake", resolved.providerId)
    }

    @Test
    fun `resolveModel fails for disabled provider`() = runTest {
        val configRepo = FakeConfigRepository()
        configRepo.upsert(
            AIProviderConfigEntity(
                providerId = "fake",
                displayName = "Fake",
                enabled = false,
                createdAt = 0L,
                updatedAt = 0L
            )
        )
        val manager = buildManager(configRepo)
        manager.registerProvider(FakeAIProvider())
        val result = manager.resolveModel("fake", "fake-mini")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AIError.ProviderUnavailableError)
    }

    @Test
    fun `resolveModel fails without any model selected`() = runTest {
        val manager = buildManager(FakeConfigRepository())
        manager.registerProvider(FakeAIProvider())
        val result = manager.resolveModel("fake", null)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AIError.InvalidRequestError)
    }
}
