package com.devstation.android.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.database.AISettingsEntity
import com.devstation.android.core.repository.AISettingsRepository
import com.devstation.android.core.repository.AIProviderConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AiSettingsUiState(
    val settings: AISettingsEntity = AISettingsEntity(),
    val configuredProviders: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val userMessage: String? = null
)

class AiSettingsViewModel(
    private val settingsRepository: AISettingsRepository,
    private val configRepository: AIProviderConfigRepository
) : ViewModel() {

    private val _userMessage = MutableStateFlow<String?>(null)
    private val _configured = MutableStateFlow<List<String>>(emptyList())

    val uiState: StateFlow<AiSettingsUiState> = combine(
        settingsRepository.observe(),
        configRepository.observeAll(),
        _configured,
        _userMessage
    ) { settings, configs, _, message ->
        _configured.value = configs.filter { it.credentialId != null && it.enabled }.map { it.providerId }
        AiSettingsUiState(
            settings = settings,
            configuredProviders = _configured.value,
            isLoading = false,
            userMessage = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AiSettingsUiState()
    )

    fun updateSettings(transform: (AISettingsEntity) -> AISettingsEntity) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            settingsRepository.save(transform(current))
        }
    }

    fun setDefaultProvider(providerId: String?) = updateSettings { it.copy(defaultProviderId = providerId) }

    fun setDefaultModel(modelId: String?) = updateSettings { it.copy(defaultModelId = modelId) }

    fun setStreamingEnabled(enabled: Boolean) = updateSettings { it.copy(streamingEnabled = enabled) }

    fun setShowUsage(enabled: Boolean) = updateSettings { it.copy(showUsage = enabled) }

    fun setShowEstimatedCost(enabled: Boolean) = updateSettings { it.copy(showEstimatedCost = enabled) }

    fun setSaveFailedRequests(enabled: Boolean) = updateSettings { it.copy(saveFailedRequests = enabled) }

    fun setRetryCount(count: Int) = updateSettings { it.copy(retryCount = count.coerceIn(0, 5)) }

    fun setConnectTimeout(seconds: Long) = updateSettings { it.copy(connectTimeoutSeconds = seconds.coerceIn(5, 60)) }

    fun setReadTimeout(seconds: Long) = updateSettings { it.copy(readTimeoutSeconds = seconds.coerceIn(30, 600)) }

    companion object {
        fun provideFactory(
            settingsRepository: AISettingsRepository,
            configRepository: AIProviderConfigRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AiSettingsViewModel(settingsRepository, configRepository) as T
            }
        }
    }
}
