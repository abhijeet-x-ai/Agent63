package com.devstation.android.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.ai.AIError
import com.devstation.android.core.ai.AIErrorMapper
import com.devstation.android.core.ai.AIProvider
import com.devstation.android.core.ai.AiCredentialManager
import com.devstation.android.core.ai.DefaultAIProviderManager
import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.database.AIProviderConfigEntity
import com.devstation.android.core.repository.AIModelCacheRepository
import com.devstation.android.core.repository.AIProviderConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ProviderRowState(
    val providerId: String,
    val displayName: String,
    val isBuiltIn: Boolean,
    val configured: Boolean,
    val enabled: Boolean,
    val isDefault: Boolean,
    val defaultModelId: String? = null,
    val lastConnectionStatus: String? = null,
    val lastConnectionCheckAt: Long? = null
)

data class AiProvidersUiState(
    val rows: List<ProviderRowState> = emptyList(),
    val defaultProviderId: String? = null,
    val isLoading: Boolean = true
)

/**
 * ViewModel for the AI Providers management screen.
 * Holds NO secret material — only configuration metadata and status.
 */
class AiProvidersViewModel(
    private val providerManager: DefaultAIProviderManager,
    private val configRepository: AIProviderConfigRepository,
    private val modelCacheRepository: AIModelCacheRepository,
    private val aiSettingsRepository: com.devstation.android.core.repository.AISettingsRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    val uiState: StateFlow<AiProvidersUiState> = combine(
        configRepository.observeAll(),
        aiSettingsRepository.observe()
    ) { configs, settings ->
        val byId = configs.associateBy { it.providerId }
        val rows = DefaultAIProviderManager.BUILT_IN_IDS.map { id ->
            val config = byId[id]
            ProviderRowState(
                providerId = id,
                displayName = when (id) {
                    DefaultAIProviderManager.ID_OPENAI -> "OpenAI-compatible"
                    DefaultAIProviderManager.ID_GEMINI -> "Google Gemini"
                    else -> "Anthropic Claude"
                },
                isBuiltIn = true,
                configured = config?.credentialId != null,
                enabled = config?.enabled ?: true,
                isDefault = settings.defaultProviderId == id,
                defaultModelId = config?.defaultModelId,
                lastConnectionStatus = config?.lastConnectionStatus,
                lastConnectionCheckAt = config?.lastConnectionCheckAt
            )
        }
        AiProvidersUiState(rows = rows, defaultProviderId = settings.defaultProviderId, isLoading = false)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AiProvidersUiState()
    )

    companion object {
        fun provideFactory(
            providerManager: DefaultAIProviderManager,
            configRepository: AIProviderConfigRepository,
            modelCacheRepository: AIModelCacheRepository,
            aiSettingsRepository: com.devstation.android.core.repository.AISettingsRepository,
            dispatchers: DispatcherProvider
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AiProvidersViewModel(providerManager, configRepository, modelCacheRepository, aiSettingsRepository, dispatchers) as T
            }
        }
    }
}

data class ProviderDetailUiState(
    val providerId: String = "",
    val displayName: String = "",
    val configured: Boolean = false,
    val enabled: Boolean = true,
    val baseUrl: String? = null,
    val defaultModelId: String? = null,
    val models: List<String> = emptyList(),
    val modelContextWindows: Map<String, Long?> = emptyMap(),
    val lastConnectionStatus: String? = null,
    val lastConnectionCheckAt: Long? = null,
    val isBusy: Boolean = false,
    val userMessage: String? = null,
    val connectionMessage: String? = null,
    val modelsFetchedAt: Long? = null
)

/**
 * ViewModel for a single provider's detail/setup screen.
 * API keys flow: input -> [AiCredentialManager] -> keystore; only the returned
 * credential reference id is ever persisted.
 */
class AiProviderDetailViewModel(
    private val providerId: String,
    private val providerManager: DefaultAIProviderManager,
    private val configRepository: AIProviderConfigRepository,
    private val modelCacheRepository: AIModelCacheRepository,
    private val credentialManager: AiCredentialManager,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProviderDetailUiState())
    val uiState: StateFlow<ProviderDetailUiState> = _uiState

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val config = configRepository.get(providerId)
            val models = modelCacheRepository.getForProvider(providerId)
            _uiState.value = ProviderDetailUiState(
                providerId = providerId,
                displayName = when (providerId) {
                    DefaultAIProviderManager.ID_OPENAI -> "OpenAI-compatible"
                    DefaultAIProviderManager.ID_GEMINI -> "Google Gemini"
                    else -> "Anthropic Claude"
                },
                configured = config?.credentialId != null,
                enabled = config?.enabled ?: true,
                baseUrl = config?.baseUrlOverride,
                defaultModelId = config?.defaultModelId,
                models = models.map { it.modelId },
                modelContextWindows = models.associate { it.modelId to it.contextWindow },
                lastConnectionStatus = config?.lastConnectionStatus,
                lastConnectionCheckAt = config?.lastConnectionCheckAt,
                modelsFetchedAt = models.maxOfOrNull { it.lastUpdated }
            )
        }
    }

    /** Save an API key (store in keystore, persist reference only). */
    fun saveApiKey(apiKey: String) {
        if (apiKey.isBlank()) return
        viewModelScope.launch {
            setBusy(true)
            try {
                val current = configRepository.get(providerId)
                val now = System.currentTimeMillis()
                val credentialId = if (current?.credentialId != null) {
                    credentialManager.replaceCredential(current.credentialId, apiKey.trim()).getOrThrow()
                } else {
                    credentialManager.storeCredential(apiKey.trim()).getOrThrow()
                }
                val config = AIProviderConfigEntity(
                    providerId = providerId,
                    displayName = _uiState.value.displayName,
                    enabled = current?.enabled ?: true,
                    credentialId = credentialId,
                    baseUrlOverride = normalizeBaseUrl(current?.baseUrlOverride),
                    defaultModelId = current?.defaultModelId,
                    createdAt = current?.createdAt ?: now,
                    updatedAt = now,
                    lastConnectionCheckAt = current?.lastConnectionCheckAt,
                    lastConnectionStatus = current?.lastConnectionStatus
                )
                configRepository.upsert(config)
                providerManager.refreshFromConfig()
                _uiState.value = _uiState.value.copy(
                    configured = true,
                    userMessage = "API key saved securely."
                )
            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(userMessage = "Could not save API key: ${t.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    fun saveBaseUrl(rawUrl: String) {
        viewModelScope.launch {
            try {
                val current = configRepository.get(providerId) ?: return@launch
                val normalized = normalizeBaseUrl(rawUrl.trim())
                configRepository.upsert(current.copy(baseUrlOverride = normalized, updatedAt = System.currentTimeMillis()))
                providerManager.refreshFromConfig()
                _uiState.value = _uiState.value.copy(baseUrl = normalized, userMessage = "Endpoint saved.")
            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(userMessage = "Invalid endpoint: ${t.message}")
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            setBusy(true)
            try {
                val provider = providerManager.getProvider(providerId)
                if (provider == null) {
                    _uiState.value = _uiState.value.copy(connectionMessage = "Provider is not registered yet.")
                    return@launch
                }
                val result = provider.testConnection()
                result.fold(
                    onSuccess = { health ->
                        configRepository.recordConnectionCheck(providerId, AIProviderConfigRepository.STATUS_CONNECTED)
                        _uiState.value = _uiState.value.copy(
                            connectionMessage = "✓ Connected (${health.latencyMs ?: 0} ms)",
                            lastConnectionStatus = AIProviderConfigRepository.STATUS_CONNECTED
                        )
                    },
                    onFailure = { t ->
                        val error = AIErrorMapper.fromThrowable(t)
                        val status = when (error) {
                            is AIError.AuthenticationError, is AIError.AuthorizationError -> AIProviderConfigRepository.STATUS_AUTH_FAILED
                            is AIError.NetworkError, is AIError.TimeoutError, is AIError.ProviderUnavailableError -> AIProviderConfigRepository.STATUS_NETWORK_ERROR
                            else -> AIProviderConfigRepository.STATUS_SERVER_ERROR
                        }
                        configRepository.recordConnectionCheck(providerId, status)
                        _uiState.value = _uiState.value.copy(
                            connectionMessage = "✕ ${error.message}",
                            lastConnectionStatus = status
                        )
                    }
                )
            } finally {
                setBusy(false)
            }
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            setBusy(true)
            try {
                val provider = providerManager.getProvider(providerId) ?: return@launch
                provider.getModels()
                    .onSuccess { models ->
                        modelCacheRepository.replaceForProvider(providerId, models)
                        _uiState.value = _uiState.value.copy(
                            models = models.map { it.modelId },
                            modelContextWindows = models.associate { it.modelId to it.contextWindow },
                            modelsFetchedAt = System.currentTimeMillis(),
                            userMessage = "Fetched ${models.size} models."
                        )
                    }
                    .onFailure { t ->
                        val error = AIErrorMapper.fromThrowable(t)
                        _uiState.value = _uiState.value.copy(userMessage = "Model listing failed: ${error.message}")
                    }
            } finally {
                setBusy(false)
            }
        }
    }

    fun setDefaultModel(modelId: String) {
        viewModelScope.launch {
            configRepository.setDefaultModel(providerId, modelId)
            _uiState.value = _uiState.value.copy(defaultModelId = modelId)
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            configRepository.setEnabled(providerId, enabled)
            providerManager.refreshFromConfig()
            _uiState.value = _uiState.value.copy(enabled = enabled)
        }
    }

    /** Secure delete: keystore secret first, then configuration row. */
    fun removeConfiguration() {
        viewModelScope.launch {
            setBusy(true)
            try {
                val config = configRepository.get(providerId)
                config?.credentialId?.let { credentialManager.deleteCredential(it) }
                configRepository.delete(providerId)
                modelCacheRepository.replaceForProvider(providerId, emptyList())
                providerManager.refreshFromConfig()
                _uiState.value = _uiState.value.copy(
                    configured = false,
                    models = emptyList(),
                    defaultModelId = null,
                    lastConnectionStatus = null,
                    userMessage = "Provider configuration and credentials removed."
                )
            } finally {
                setBusy(false)
            }
        }
    }

    fun clearUserMessage() {
        _uiState.value = _uiState.value.copy(userMessage = null, connectionMessage = null)
    }

    private fun normalizeBaseUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        // HTTPS enforced here; throws SecurityException for http:// (non-loopback).
        val url = com.devstation.android.core.ai.HttpsUrlValidator.validate(trimmed)
        return url.toString().trimEnd('/')
    }

    private suspend fun setBusy(busy: Boolean) {
        _uiState.value = _uiState.value.copy(isBusy = busy)
    }

    companion object {
        fun provideFactory(
            providerId: String,
            providerManager: DefaultAIProviderManager,
            configRepository: AIProviderConfigRepository,
            modelCacheRepository: AIModelCacheRepository,
            credentialManager: AiCredentialManager,
            dispatchers: DispatcherProvider
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AiProviderDetailViewModel(
                    providerId, providerManager, configRepository, modelCacheRepository, credentialManager, dispatchers
                ) as T
            }
        }
    }
}
