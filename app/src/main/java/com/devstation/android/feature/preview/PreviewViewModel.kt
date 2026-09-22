package com.devstation.android.feature.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.preview.PreviewLogEntry
import com.devstation.android.core.preview.PreviewServer
import com.devstation.android.core.preview.PreviewServerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phase 9 §47: ViewModel for the Preview dashboard. All mutations go through
 * PreviewServerManager, which enforces the security policy and audits every transition.
 */
class PreviewViewModel(
    private val previewManager: PreviewServerManager
) : ViewModel() {

    data class UiState(
        val projectName: String = "",
        val server: PreviewServer? = null,
        val logs: List<PreviewLogEntry> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var activeProjectId: String? = null

    /** Bind to a project (the most recent project by default; callers may pin one). */
    fun bind(projectId: String?, projectName: String) {
        activeProjectId = projectId
        _uiState.value = _uiState.value.copy(projectName = projectName)
        refresh()
    }

    fun refresh() {
        val id = activeProjectId ?: return
        viewModelScope.launch {
            val server = previewManager.serverForProject(id)
            _uiState.value = _uiState.value.copy(
                server = server,
                logs = server?.let { previewManager.logsFor(it.id).getOrDefault(emptyList()) } ?: emptyList()
            )
        }
    }

    fun start(command: String, arguments: String, port: Int, workingDirectory: String, projectId: String, projectName: String) {
        activeProjectId = projectId
        _uiState.value = _uiState.value.copy(projectName = projectName)
        viewModelScope.launch {
            previewManager.start(
                projectId = projectId,
                projectName = projectName,
                command = command,
                arguments = arguments.split(' ').filter { it.isNotBlank() },
                workingDirectory = workingDirectory,
                requestedPort = port
            )
            refresh()
        }
    }

    fun start(command: String, arguments: String, port: Int) {
        val id = activeProjectId ?: return
        val server = previewManager.serverForProject(id)
        start(
            command = command,
            arguments = arguments,
            port = port,
            workingDirectory = server?.workingDirectory ?: "",
            projectId = id,
            projectName = _uiState.value.projectName
        )
    }

    fun stop() {
        val server = _uiState.value.server ?: return
        viewModelScope.launch {
            previewManager.stop(server.id, requesterProjectId = activeProjectId)
            refresh()
        }
    }

    fun restart() {
        val server = _uiState.value.server ?: return
        viewModelScope.launch {
            previewManager.restart(server.id, requesterProjectId = activeProjectId)
            refresh()
        }
    }

    fun clearLogs() {
        _uiState.value.server?.let { previewManager.clearLogs(it.id) }
    }

    class Factory(private val previewManager: PreviewServerManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PreviewViewModel(previewManager) as T
    }
}
