package com.devstation.android.feature.runtime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.future.runtime.DevToolsStatus
import com.devstation.android.future.runtime.InstallProgress
import com.devstation.android.future.runtime.LinuxDiagnostics
import com.devstation.android.future.runtime.LinuxRuntimeManager
import com.devstation.android.future.runtime.LinuxRuntimeState
import com.devstation.android.future.runtime.LinuxStorageUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LinuxRuntimeUiState(
    val state: LinuxRuntimeState = LinuxRuntimeState.NOT_INSTALLED,
    val installProgress: InstallProgress? = null,
    val storageUsage: LinuxStorageUsage = LinuxStorageUsage(),
    val devToolsStatus: DevToolsStatus = DevToolsStatus(),
    val isInstallingTools: Boolean = false,
    val toolsInstallLog: String = "",
    val diagnostics: LinuxDiagnostics? = null,
    val isRunningDiagnostics: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

class LinuxRuntimeViewModel(
    private val runtimeManager: LinuxRuntimeManager
) : ViewModel() {

    private val _installProgress = MutableStateFlow<InstallProgress?>(null)
    private val _storageUsage = MutableStateFlow(LinuxStorageUsage())
    private val _devToolsStatus = MutableStateFlow(DevToolsStatus())
    private val _isInstallingTools = MutableStateFlow(false)
    private val _toolsInstallLog = MutableStateFlow("")
    private val _isRunningDiagnostics = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _infoMessage = MutableStateFlow<String?>(null)

    private val baseState = combine(
        runtimeManager.runtimeState,
        _installProgress,
        _storageUsage,
        _devToolsStatus,
        _isInstallingTools
    ) { rState, progress, storage, tools, installingTools ->
        Pair(
            Pair(rState, progress),
            Pair(storage, Pair(tools, installingTools))
        )
    }

    val uiState: StateFlow<LinuxRuntimeUiState> = combine(
        baseState,
        _toolsInstallLog,
        runtimeManager.lastDiagnostics,
        _isRunningDiagnostics,
        _errorMessage
    ) { base, toolsLog, diag, runningDiag, err ->
        val (rState, progress) = base.first
        val (storage, toolsPair) = base.second
        val (tools, installingTools) = toolsPair

        LinuxRuntimeUiState(
            state = rState,
            installProgress = progress,
            storageUsage = storage,
            devToolsStatus = tools,
            isInstallingTools = installingTools,
            toolsInstallLog = toolsLog,
            diagnostics = diag,
            isRunningDiagnostics = runningDiag,
            errorMessage = err,
            infoMessage = _infoMessage.value
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LinuxRuntimeUiState(state = runtimeManager.runtimeState.value)
    )

    init {
        refreshState()
    }

    fun refreshState() {
        viewModelScope.launch {
            _storageUsage.value = runtimeManager.calculateLinuxStorage()
            if (runtimeManager.isInstalled()) {
                _devToolsStatus.value = runtimeManager.devToolsInstaller.checkToolsStatus(
                    runtimeManager.storagePaths.rootfsDir
                )
            }
        }
    }

    fun startInstallation() {
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                runtimeManager.install().collect { progress ->
                    _installProgress.value = progress
                }
                refreshState()
                _infoMessage.value = "Linux environment installed successfully!"
            } catch (e: Exception) {
                _errorMessage.value = "Installation failed: ${e.message}"
            }
        }
    }

    fun installDevelopmentTools() {
        viewModelScope.launch {
            _isInstallingTools.value = true
            _toolsInstallLog.value = ""
            _errorMessage.value = null
            try {
                runtimeManager.devToolsInstaller.installDevTools(
                    runtimeManager.storagePaths.rootfsDir
                ).collect { logStep ->
                    _toolsInstallLog.value = logStep
                }
                refreshState()
                _infoMessage.value = "Development tools installed successfully!"
            } catch (e: Exception) {
                _errorMessage.value = "Failed to install dev tools: ${e.message}"
            } finally {
                _isInstallingTools.value = false
            }
        }
    }

    fun runDiagnostics() {
        viewModelScope.launch {
            _isRunningDiagnostics.value = true
            _errorMessage.value = null
            try {
                runtimeManager.runDiagnostics()
            } catch (e: Exception) {
                _errorMessage.value = "Diagnostics error: ${e.message}"
            } finally {
                _isRunningDiagnostics.value = false
            }
        }
    }

    fun resetEnvironment(keepHome: Boolean) {
        viewModelScope.launch {
            _errorMessage.value = null
            val result = runtimeManager.resetEnvironment(keepHome)
            if (result.isSuccess) {
                _installProgress.value = null
                _devToolsStatus.value = DevToolsStatus()
                refreshState()
                _infoMessage.value = "Linux environment reset. User projects preserved."
            } else {
                _errorMessage.value = "Reset failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun uninstallLinux() {
        viewModelScope.launch {
            _errorMessage.value = null
            val result = runtimeManager.uninstallLinux()
            if (result.isSuccess) {
                _installProgress.value = null
                _devToolsStatus.value = DevToolsStatus()
                refreshState()
                _infoMessage.value = "Linux environment uninstalled. User projects preserved."
            } else {
                _errorMessage.value = "Uninstall failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun clearMessages() {
        _errorMessage.value = null
        _infoMessage.value = null
    }

    class Factory(
        private val runtimeManager: LinuxRuntimeManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LinuxRuntimeViewModel(runtimeManager) as T
        }
    }
}
