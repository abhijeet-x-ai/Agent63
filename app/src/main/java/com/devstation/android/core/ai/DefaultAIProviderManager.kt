package com.devstation.android.core.ai

import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.database.AIProviderConfigEntity
import com.devstation.android.core.repository.AIModelCacheRepository
import com.devstation.android.core.repository.AIProviderConfigRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Concrete provider registry. Owns adapter construction from persisted config
 * (credential reference + base URL override) and resolves enabled providers.
 *
 * Deliberately receives NO filesystem/terminal/runtime dependencies:
 * providers physically cannot reach DevStation tooling (Phase 5 spec §43/§44).
 */
class DefaultAIProviderManager(
    private val httpClient: AIHttpClient,
    private val credentials: AiCredentialManager,
    private val configRepository: AIProviderConfigRepository,
    private val modelCacheRepository: AIModelCacheRepository,
    private val dispatchers: DispatcherProvider
) : AIProviderManager {

    private val providersById = ConcurrentHashMap<String, AIProvider>()
    private val mutex = Mutex()

    override val providers: List<AIProvider>
        get() = providersById.values.toList()

    override fun getProvider(providerId: String): AIProvider? = providersById[providerId]

    override fun registerProvider(provider: AIProvider) {
        require(providerIdIsValid(provider.providerId)) { "Invalid provider id: ${provider.providerId}" }
        providersById[provider.providerId] = provider
    }

    /**
     * Rebuild adapters for the built-in provider ids from current persisted config.
     * Called at startup and whenever a provider configuration changes.
     */
    suspend fun refreshFromConfig() = withContext(dispatchers.io) {
        mutex.withLock {
            val ids = listOf(ID_OPENAI, ID_GEMINI, ID_ANTHROPIC)
            for (id in ids) {
                val config = configRepository.get(id)
                registerBuiltIn(
                    config = config,
                    id = id
                )
            }
        }
    }

    private fun registerBuiltIn(config: AIProviderConfigEntity?, id: String) {
        when (id) {
            ID_OPENAI -> registerProvider(
                OpenAICompatibleProvider(
                    providerId = ID_OPENAI,
                    displayName = "OpenAI-compatible",
                    defaultBaseUrl = OpenAICompatibleProvider.DEFAULT_BASE_URL,
                    httpClient = httpClient,
                    credentials = credentials,
                    baseUrlOverride = config?.baseUrlOverride,
                    credentialId = config?.credentialId
                )
            )
            ID_GEMINI -> registerProvider(
                GeminiProvider(
                    httpClient = httpClient,
                    credentials = credentials,
                    credentialId = config?.credentialId
                )
            )
            ID_ANTHROPIC -> registerProvider(
                AnthropicProvider(
                    httpClient = httpClient,
                    credentials = credentials,
                    credentialId = config?.credentialId,
                    baseUrlOverride = config?.baseUrlOverride
                )
            )
        }
    }

    /**
     * Resolve the effective model for a provider: explicit modelId -> provider
     * default -> settings default -> error. Cached metadata is preferred so no
     * network round-trip is required to resolve a previously listed model.
     */
    override suspend fun resolveModel(providerId: String, modelId: String?): Result<AIModel> =
        withContext(dispatchers.io) {
            val provider = getProvider(providerId)
                ?: return@withContext Result.failure(AIError.ProviderUnavailableError("Provider $providerId is not registered.") as Throwable)
            val config = configRepository.get(providerId)
            if (config != null && !config.enabled) {
                return@withContext Result.failure(AIError.ProviderUnavailableError("Provider ${provider.displayName} is disabled.") as Throwable)
            }
            val effective = modelId ?: config?.defaultModelId
                ?: return@withContext Result.failure(
                    AIError.InvalidRequestError("No model selected for ${provider.displayName}.") as Throwable
                )
            val cached = modelCacheRepository.get(providerId, effective)
            if (cached != null) {
                return@withContext Result.success(modelCacheRepository.toDomainModel(cached))
            }
            Result.success(
                AIModel(
                    providerId = providerId,
                    modelId = effective,
                    displayName = effective,
                    capabilities = provider.capabilities,
                    lastUpdated = 0L
                )
            )
        }

    /** Cached model ids for a provider (for selection UI without network). */
    suspend fun resolveModelsFor(providerId: String): List<String> = withContext(dispatchers.io) {
        modelCacheRepository.getForProvider(providerId).map { it.modelId }
    }

    private fun providerIdIsValid(id: String): Boolean = id.isNotBlank() && !id.contains(' ')

    companion object {
        const val ID_OPENAI = "openai"
        const val ID_GEMINI = "gemini"
        const val ID_ANTHROPIC = "anthropic"
        val BUILT_IN_IDS = listOf(ID_OPENAI, ID_GEMINI, ID_ANTHROPIC)
    }
}
