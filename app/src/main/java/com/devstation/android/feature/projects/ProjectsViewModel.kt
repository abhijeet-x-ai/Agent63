package com.devstation.android.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.filesystem.ProjectFileSystemManager
import com.devstation.android.core.model.Project
import com.devstation.android.core.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class ProjectsUiState(
    val projects: List<Project> = emptyList(),
    val pinnedProjects: List<Project> = emptyList(),
    val searchQuery: String = "",
    val defaultWorkspacePath: String = "",
    val isLoading: Boolean = false,
    val userMessage: String? = null
)

class ProjectsViewModel(
    private val projectRepository: ProjectRepository,
    private val fileSystemManager: ProjectFileSystemManager
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _userMessage = MutableStateFlow<String?>(null)
    private val _isLoading = MutableStateFlow(false)

    val uiState: StateFlow<ProjectsUiState> = combine(
        projectRepository.getAllProjects(),
        projectRepository.getPinnedProjects(),
        _searchQuery,
        _isLoading,
        _userMessage
    ) { projects, pinned, query, loading, message ->
        val filtered = if (query.isBlank()) {
            projects
        } else {
            projects.filter { it.name.contains(query, ignoreCase = true) || it.localPath.contains(query, ignoreCase = true) }
        }

        ProjectsUiState(
            projects = filtered,
            pinnedProjects = pinned,
            searchQuery = query,
            defaultWorkspacePath = fileSystemManager.defaultWorkspaceDir.absolutePath,
            isLoading = loading,
            userMessage = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ProjectsUiState(
            defaultWorkspacePath = fileSystemManager.defaultWorkspaceDir.absolutePath,
            isLoading = true
        )
    )

    init {
        refreshSizes()
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun createProject(name: String, parentDirPath: String? = null, onCreated: (Project) -> Unit = {}) {
        viewModelScope.launch {
            _isLoading.value = true
            val parentDir = parentDirPath?.takeIf { it.isNotBlank() }?.let { File(it) }
            val result = projectRepository.createProject(name, parentDir)
            _isLoading.value = false

            result.onSuccess { project ->
                _userMessage.value = "Created project '${project.name}'"
                onCreated(project)
            }.onFailure { error ->
                _userMessage.value = "Failed to create project: ${error.message}"
            }
        }
    }

    fun importExistingFolder(folderPath: String, name: String = "") {
        viewModelScope.launch {
            _isLoading.value = true
            val result = projectRepository.importExistingFolder(name, folderPath)
            _isLoading.value = false

            result.onSuccess { project ->
                _userMessage.value = "Imported folder as project '${project.name}'"
            }.onFailure { error ->
                _userMessage.value = "Import failed: ${error.message}"
            }
        }
    }

    fun renameProject(id: String, newName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = projectRepository.renameProject(id, newName)
            _isLoading.value = false

            result.onSuccess { project ->
                _userMessage.value = "Renamed to '${project.name}'"
            }.onFailure { error ->
                _userMessage.value = "Failed to rename: ${error.message}"
            }
        }
    }

    fun deleteProject(id: String, deleteFilesFromDisk: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = projectRepository.deleteProject(id, deleteFilesFromDisk)
            _isLoading.value = false

            result.onSuccess {
                _userMessage.value = if (deleteFilesFromDisk) "Project and files removed" else "Project unlinked from workspace"
            }.onFailure { error ->
                _userMessage.value = "Failed to delete project: ${error.message}"
            }
        }
    }

    fun togglePin(id: String, isPinned: Boolean) {
        viewModelScope.launch {
            projectRepository.togglePin(id, isPinned)
        }
    }

    fun refreshSizes() {
        viewModelScope.launch {
            projectRepository.refreshProjectSizes()
        }
    }

    fun dismissUserMessage() {
        _userMessage.value = null
    }

    companion object {
        fun provideFactory(
            projectRepository: ProjectRepository,
            fileSystemManager: ProjectFileSystemManager
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ProjectsViewModel(projectRepository, fileSystemManager) as T
            }
        }
    }
}
