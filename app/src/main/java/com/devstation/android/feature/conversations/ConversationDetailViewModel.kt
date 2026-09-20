package com.devstation.android.feature.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.model.Conversation
import com.devstation.android.core.model.Message
import com.devstation.android.core.model.MessageRole
import com.devstation.android.core.repository.ConversationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationDetailUiState(
    val conversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class ConversationDetailViewModel(
    private val conversationRepository: ConversationRepository,
    private val conversationId: String
) : ViewModel() {

    private val _conversation = MutableStateFlow<Conversation?>(null)
    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ConversationDetailUiState> = combine(
        _conversation,
        conversationRepository.getMessages(conversationId),
        _errorMessage
    ) { conv, messages, error ->
        ConversationDetailUiState(
            conversation = conv,
            messages = messages,
            isLoading = false,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConversationDetailUiState(isLoading = true)
    )

    init {
        loadConversation()
    }

    private fun loadConversation() {
        viewModelScope.launch {
            _conversation.value = conversationRepository.getConversationById(conversationId)
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            val result = conversationRepository.sendMessage(
                conversationId = conversationId,
                content = content,
                role = MessageRole.USER
            )
            result.onFailure {
                _errorMessage.value = "Failed to store message: ${it.message}"
            }
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    companion object {
        fun provideFactory(
            conversationRepository: ConversationRepository,
            conversationId: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ConversationDetailViewModel(conversationRepository, conversationId) as T
            }
        }
    }
}
