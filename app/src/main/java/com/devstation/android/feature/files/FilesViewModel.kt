package com.devstation.android.feature.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.filesystem.ProjectFileSystemManager
import com.devstation.android.core.model.FileItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class Breadcrumb(val name: String, val path: String)

data class FilesUiState(
    val currentPath: String = "",
    val rootPath: String = "",
    val projectName: String = "",
    val breadcrumbs: List<Breadcrumb> = emptyList(),
    val items: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val userMessage: String? = null
)

class FilesViewModel(
    private val fileSystemManager: ProjectFileSystemManager,
    initialPath: String,
    initialName: String
) : ViewModel() {

    private val root = initialPath.ifBlank { fileSystemManager.defaultWorkspaceDir.absolutePath }
    private val projName = initialName.ifBlank { File(root).name }

    private val _uiState = MutableStateFlow(
        FilesUiState(
            currentPath = root,
            rootPath = root,
            projectName = projName,
            breadcrumbs = computeBreadcrumbs(root, root, projName),
            isLoading = true
        )
    )
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    init {
        loadDirectory(root)
    }

    fun loadDirectory(path: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = fileSystemManager.listFiles(path)
            result.onSuccess { fileList ->
                _uiState.value = _uiState.value.copy(
                    currentPath = path,
                    breadcrumbs = computeBreadcrumbs(path, _uiState.value.rootPath, _uiState.value.projectName),
                    items = fileList,
                    isLoading = false
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    userMessage = "Could not list directory: ${error.message}"
                )
            }
        }
    }

    fun navigateUp() {
        val current = File(_uiState.value.currentPath)
        val rootFile = File(_uiState.value.rootPath)
        if (current.absolutePath != rootFile.absolutePath) {
            val parent = current.parentFile
            if (parent != null) {
                loadDirectory(parent.absolutePath)
            }
        }
    }

    fun createFolder(folderName: String) {
        viewModelScope.launch {
            val result = fileSystemManager.createFolder(_uiState.value.currentPath, folderName)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(userMessage = "Folder '$folderName' created")
                loadDirectory(_uiState.value.currentPath)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(userMessage = "Failed to create folder: ${error.message}")
            }
        }
    }

    fun createFile(fileName: String, content: String = "") {
        viewModelScope.launch {
            val result = fileSystemManager.createFile(_uiState.value.currentPath, fileName, content)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(userMessage = "File '$fileName' created")
                loadDirectory(_uiState.value.currentPath)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(userMessage = "Failed to create file: ${error.message}")
            }
        }
    }

    fun renameItem(path: String, newName: String) {
        viewModelScope.launch {
            val result = fileSystemManager.renameFile(path, newName)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(userMessage = "Renamed to '$newName'")
                loadDirectory(_uiState.value.currentPath)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(userMessage = "Rename failed: ${error.message}")
            }
        }
    }

    fun deleteItem(path: String) {
        viewModelScope.launch {
            val result = fileSystemManager.deleteFile(path)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(userMessage = "Item deleted")
                loadDirectory(_uiState.value.currentPath)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(userMessage = "Delete failed: ${error.message}")
            }
        }
    }

    fun dismissUserMessage() {
        _uiState.value = _uiState.value.copy(userMessage = null)
    }

    private fun computeBreadcrumbs(currentPath: String, rootPath: String, projectName: String): List<Breadcrumb> {
        val rootFile = File(rootPath)
        val currentFile = File(currentPath)

        if (currentFile.absolutePath == rootFile.absolutePath) {
            return listOf(Breadcrumb(projectName, rootPath))
        }

        val trail = mutableListOf<Breadcrumb>()
        var curr: File? = currentFile

        while (curr != null && curr.absolutePath.startsWith(rootFile.absolutePath)) {
            val name = if (curr.absolutePath == rootFile.absolutePath) projectName else curr.name
            trail.add(0, Breadcrumb(name, curr.absolutePath))
            if (curr.absolutePath == rootFile.absolutePath) break
            curr = curr.parentFile
        }

        return if (trail.isEmpty()) listOf(Breadcrumb(projectName, rootPath)) else trail
    }

    companion object {
        fun provideFactory(
            fileSystemManager: ProjectFileSystemManager,
            initialPath: String,
            initialName: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return FilesViewModel(fileSystemManager, initialPath, initialName) as T
            }
        }
    }
}
