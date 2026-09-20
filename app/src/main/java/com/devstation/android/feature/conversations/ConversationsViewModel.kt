package com.devstation.android.feature.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.model.Conversation
import com.devstation.android.core.model.Project
import com.devstation.android.core.repository.ConversationRepository
import com.devstation.android.core.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationsUiState(
    val conversations: List<Conversation> = emptyList(),
    val pinnedConversations: List<Conversation> = emptyList(),
    val availableProjects: List<Project> = emptyList(),
    val isLoading: Boolean = false,
    val userMessage: String? = null
)

class ConversationsViewModel(
    private val conversationRepository: ConversationRepository,
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _userMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ConversationsUiState> = combine(
        conversationRepository.getAllConversations(),
        conversationRepository.getPinnedConversations(),
        projectRepository.getAllProjects(),
        _userMessage
    ) { all, pinned, projects, message ->
        ConversationsUiState(
            conversations = all,
            pinnedConversations = pinned,
            availableProjects = projects,
            isLoading = false,
            userMessage = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConversationsUiState(isLoading = true)
    )

    fun createConversation(title: String, projectId: String? = null, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val result = conversationRepository.createConversation(title, projectId)
            result.onSuccess {
                onCreated(it.id)
            }.onFailure {
                _userMessage.value = "Failed to create conversation: ${it.message}"
            }
        }
    }

    fun togglePin(id: String, isPinned: Boolean) {
        viewModelScope.launch {
            conversationRepository.togglePin(id, isPinned)
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(id)
            _userMessage.value = "Conversation deleted"
        }
    }

    fun dismissUserMessage() {
        _userMessage.value = null
    }

    companion object {
        fun provideFactory(
            conversationRepository: ConversationRepository,
            projectRepository: ProjectRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ConversationsViewModel(conversationRepository, projectRepository) as T
            }
        }
    }
}
