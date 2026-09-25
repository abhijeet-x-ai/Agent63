package com.devstation.android.feature.home

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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val recentProjects: List<Project> = emptyList(),
    val pinnedProjects: List<Project> = emptyList(),
    val recentConversations: List<Conversation> = emptyList(),
    val pinnedConversations: List<Conversation> = emptyList(),
    val activeProject: Project? = null,
    val isLoading: Boolean = false,
    val infoMessage: String? = null
)

class HomeViewModel(
    private val projectRepository: ProjectRepository,
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    private val _infoMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        projectRepository.getRecentProjects(5),
        projectRepository.getPinnedProjects(),
        conversationRepository.getRecentConversations(5),
        conversationRepository.getPinnedConversations(),
        _infoMessage
    ) { recentProjects, pinnedProjects, recentConversations, pinnedConversations, infoMessage ->
        HomeUiState(
            recentProjects = recentProjects,
            pinnedProjects = pinnedProjects,
            recentConversations = recentConversations,
            pinnedConversations = pinnedConversations,
            activeProject = recentProjects.firstOrNull(),
            isLoading = false,
            infoMessage = infoMessage
        )
    }.catch { emit(HomeUiState(isLoading = false, infoMessage = "Workspace data unavailable")) }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState(isLoading = true)
    )

    fun createNewConversation(title: String = "New Workspace Session", onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val result = conversationRepository.createConversation(
                title = title,
                projectId = uiState.value.activeProject?.id
            )
            result.onSuccess { conv ->
                onCreated(conv.id)
            }.onFailure {
                _infoMessage.value = "Failed to create conversation: ${it.message}"
            }
        }
    }

    fun dismissInfoMessage() {
        _infoMessage.value = null
    }

    companion object {
        fun provideFactory(
            projectRepository: ProjectRepository,
            conversationRepository: ConversationRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(projectRepository, conversationRepository) as T
            }
        }
    }
}
