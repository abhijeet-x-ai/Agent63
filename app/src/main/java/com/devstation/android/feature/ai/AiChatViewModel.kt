package com.devstation.android.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.ai.AIError
import com.devstation.android.core.ai.AIErrorMapper
import com.devstation.android.core.ai.AIResponseEvent
import com.devstation.android.core.ai.ChatOrchestrator
import com.devstation.android.core.ai.DefaultAIProviderManager
import com.devstation.android.core.model.Conversation
import com.devstation.android.core.model.Message
import com.devstation.android.core.model.MessageRole
import com.devstation.android.core.repository.AISettingsRepository
import com.devstation.android.core.repository.ConversationRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val providerId: String? = null,
    val modelId: String? = null,
    val availableProviders: List<Pair<String, String>> = emptyList(),
    val availableModels: List<String> = emptyList(),
    val isStreaming: Boolean = false,
    val streamingText: String? = null,
    val errorMessage: String? = null,
    val usageLine: String? = null,
    val lastFailedUserMessage: String? = null,
    val isLoading: Boolean = true
)

/**
 * Chat ViewModel — normal AI conversation only. It cannot touch files, terminal,
 * or the Linux runtime because it only holds repositories and the orchestrator.
 */
class AiChatViewModel(
    private val conversationId: String,
    private val conversationRepository: ConversationRepository,
    private val orchestrator: ChatOrchestrator,
    private val providerManager: DefaultAIProviderManager,
    private val aiSettingsRepository: AISettingsRepository
) : ViewModel() {

    private val _conversation = MutableStateFlow<Conversation?>(null)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _isStreaming = MutableStateFlow(false)
    private val _streamingText = MutableStateFlow<String?>(null)
    private val _usageLine = MutableStateFlow<String?>(null)
    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    private val _lastFailedUserMessage = MutableStateFlow<String?>(null)

    private var streamJob: Job? = null

    val uiState: StateFlow<ChatUiState> = combine(
        combine(_conversation, conversationRepository.getMessages(conversationId)) { c, m -> c to m },
        combine(_isStreaming, _streamingText, _errorMessage) { s, t, e -> Triple(s, t, e) },
        combine(_usageLine, _availableModels, _lastFailedUserMessage) { u, m, f -> Triple(u, m, f) }
    ) { (conv, messages), (streaming, text, error), (usage, models, failed) ->
        val settings = aiSettingsRepository.get()
        val providerId = conv?.providerId ?: settings.defaultProviderId
        val modelId = conv?.modelId ?: settings.defaultModelId
        ChatUiState(
            conversation = conv,
            messages = messages,
            providerId = providerId,
            modelId = modelId,
            availableProviders = providerManager.providers.map { it.providerId to it.displayName },
            availableModels = models,
            isStreaming = streaming,
            streamingText = text,
            errorMessage = error,
            usageLine = usage,
            lastFailedUserMessage = failed,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatUiState()
    )

    init {
        viewModelScope.launch {
            _conversation.value = conversationRepository.getConversationById(conversationId)
            refreshAvailableModels()
        }
    }

    private suspend fun refreshAvailableModels() {
        val conv = _conversation.value
        val settings = aiSettingsRepository.get()
        val providerId = conv?.providerId ?: settings.defaultProviderId
        if (providerId == null) {
            _availableModels.value = emptyList()
            return
        }
        val config = providerManager.getProvider(providerId)
        _availableModels.value = if (config != null) {
            providerManager.resolveModelsFor(providerId)
        } else emptyList()
    }

    fun sendMessage(content: String) {
        if (content.isBlank() || _isStreaming.value) return
        viewModelScope.launch {
            val stored = conversationRepository.sendMessage(conversationId, content, MessageRole.USER)
            stored.onFailure {
                _errorMessage.value = "Failed to store message: ${it.message}"
                return@launch
            }
            _lastFailedUserMessage.value = content
            startStreaming()
        }
    }

    /** Retry the last failed exchange (re-sends with the same history). */
    fun retryLast() {
        if (_isStreaming.value) return
        viewModelScope.launch {
            startStreaming()
        }
    }

    private suspend fun startStreaming() {
        val built = orchestrator.buildRequest(conversationId, stream = true)
        val (provider, request) = built.getOrElse { error ->
            val aiError = AIErrorMapper.fromThrowable(error)
            _errorMessage.value = aiError.message
            return
        }
        _usageLine.value = null
        _streamingText.value = ""
        _isStreaming.value = true
        streamJob = viewModelScope.launch {
            orchestrator.streamChat(conversationId, provider, request).collect { event ->
                when (event) {
                    is AIResponseEvent.TextDelta -> {
                        _streamingText.value = (_streamingText.value ?: "") + event.text
                    }
                    is AIResponseEvent.ThinkingDelta -> {
                        // Displayed as subtle "thinking" line; not persisted as content.
                    }
                    is AIResponseEvent.Usage -> {
                        val u = event.usage
                        _usageLine.value = when {
                            !u.isKnown -> "Usage unavailable"
                            else -> listOfNotNull(
                                u.inputTokens?.let { "in: $it" },
                                u.outputTokens?.let { "out: $it" },
                                u.totalTokens?.let { "total: $it" }
                            ).joinToString(" • ")
                        }
                    }
                    is AIResponseEvent.Error -> {
                        _errorMessage.value = event.error.message
                    }
                    is AIResponseEvent.Cancelled -> {
                        _errorMessage.value = null
                    }
                    else -> Unit
                }
            }
        }
        streamJob?.invokeOnCompletion {
            _isStreaming.value = false
            _streamingText.value = null
            streamJob = null
        }
    }

    /** Cancel the active request; cancellation propagates to the HTTP call. */
    fun cancelStreaming() {
        streamJob?.cancel()
    }

    fun selectProviderAndModel(providerId: String, modelId: String?) {
        viewModelScope.launch {
            conversationRepository.setConversationModel(conversationId, providerId, modelId)
            _conversation.value = conversationRepository.getConversationById(conversationId)
            refreshAvailableModels()
        }
    }

    fun updateTitle(newTitle: String) {
        viewModelScope.launch {
            conversationRepository.updateTitle(conversationId, newTitle)
            _conversation.value = conversationRepository.getConversationById(conversationId)
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    companion object {
        fun provideFactory(
            conversationId: String,
            conversationRepository: ConversationRepository,
            orchestrator: ChatOrchestrator,
            providerManager: DefaultAIProviderManager,
            aiSettingsRepository: AISettingsRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AiChatViewModel(
                    conversationId, conversationRepository, orchestrator, providerManager, aiSettingsRepository
                ) as T
            }
        }
    }
}
