package com.devstation.android.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.filesystem.ProjectFileSystemManager
import com.devstation.android.future.terminal.CommandHistoryItem
import com.devstation.android.future.terminal.ShellDetector
import com.devstation.android.future.terminal.ShellInfo
import com.devstation.android.future.terminal.TerminalConfig
import com.devstation.android.future.terminal.TerminalManager
import com.devstation.android.future.terminal.TerminalOutputLine
import com.devstation.android.future.terminal.TerminalSession
import com.devstation.android.future.terminal.TerminalState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class TerminalUiState(
    val sessions: List<TerminalSession> = emptyList(),
    val activeSession: TerminalSession? = null,
    val outputLines: List<TerminalOutputLine> = emptyList(),
    val currentWorkingDir: String = "",
    val shellInfo: ShellInfo = ShellDetector.detectShell(),
    val state: TerminalState = TerminalState.IDLE,
    val inputBuffer: String = "",
    val config: TerminalConfig = TerminalConfig(),
    val isAutoScroll: Boolean = true,
    val userMessage: String? = null,
    val selectedRuntime: com.devstation.android.future.runtime.RuntimeType = com.devstation.android.future.runtime.RuntimeType.ANDROID_SHELL,
    val isLinuxInstalled: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModel(
    private val terminalManager: TerminalManager,
    private val fileSystemManager: ProjectFileSystemManager,
    private val linuxRuntimeManager: com.devstation.android.future.runtime.LinuxRuntimeManager? = null,
    initialProjectPath: String = ""
) : ViewModel() {

    private val _inputBuffer = MutableStateFlow("")
    private val _config = MutableStateFlow(TerminalConfig())
    private val _userMessage = MutableStateFlow<String?>(null)
    private val _isAutoScroll = MutableStateFlow(true)
    private val _selectedRuntime = MutableStateFlow(com.devstation.android.future.runtime.RuntimeType.ANDROID_SHELL)
    private var historyIndex = -1
    private var savedDraftInput = ""

    val sessions: StateFlow<List<TerminalSession>> = terminalManager.sessions
    val activeSession: StateFlow<TerminalSession?> = terminalManager.activeSession

    private val activeSessionOutputs: StateFlow<List<TerminalOutputLine>> = activeSession
        .flatMapLatest { session ->
            session?.outputBuffer ?: flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val activeSessionState: StateFlow<TerminalState> = activeSession
        .flatMapLatest { session ->
            session?.state ?: flowOf(TerminalState.IDLE)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TerminalState.IDLE)

    private val activeSessionDir: StateFlow<String> = activeSession
        .flatMapLatest { session ->
            session?.currentWorkingDir ?: flowOf("")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private data class SessionSnapshot(
        val sessions: List<TerminalSession>,
        val activeSession: TerminalSession?,
        val outputLines: List<TerminalOutputLine>,
        val currentWorkingDir: String,
        val state: TerminalState
    )

    private val sessionSnapshotFlow = combine(
        sessions,
        activeSession,
        activeSessionOutputs,
        activeSessionState,
        activeSessionDir
    ) { sessionsList, active, outputs, termState, workingDir ->
        SessionSnapshot(
            sessions = sessionsList,
            activeSession = active,
            outputLines = outputs,
            currentWorkingDir = workingDir.ifBlank { active?.initialWorkingDir ?: "" },
            state = termState
        )
    }

    private data class TerminalControlSnapshot(
        val input: String,
        val config: TerminalConfig,
        val autoScroll: Boolean,
        val userMessage: String?,
        val runtime: com.devstation.android.future.runtime.RuntimeType
    )

    private val terminalControlFlow = combine(
        _inputBuffer,
        _config,
        _isAutoScroll,
        _userMessage,
        _selectedRuntime
    ) { input, config, autoScroll, message, runtime ->
        TerminalControlSnapshot(input, config, autoScroll, message, runtime)
    }

    val uiState: StateFlow<TerminalUiState> = combine(
        sessionSnapshotFlow,
        terminalControlFlow
    ) { snapshot, control ->
        TerminalUiState(
            sessions = snapshot.sessions,
            activeSession = snapshot.activeSession,
            outputLines = snapshot.outputLines,
            currentWorkingDir = snapshot.currentWorkingDir,
            shellInfo = snapshot.activeSession?.shellInfo ?: ShellDetector.detectShell(),
            state = snapshot.state,
            inputBuffer = control.input,
            config = control.config,
            isAutoScroll = control.autoScroll,
            userMessage = control.userMessage,
            selectedRuntime = snapshot.activeSession?.runtimeType ?: control.runtime,
            isLinuxInstalled = linuxRuntimeManager?.isInstalled() == true
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TerminalUiState(
            isLinuxInstalled = linuxRuntimeManager?.isInstalled() == true
        )
    )

    init {
        initializeSession(initialProjectPath)
    }

    fun initializeSession(projectPath: String) {
        viewModelScope.launch {
            val targetDir = if (projectPath.isNotBlank()) {
                val f = File(projectPath)
                if (f.exists() && f.isDirectory) {
                    f.absolutePath
                } else {
                    _userMessage.value = "Directory '$projectPath' does not exist."
                    fileSystemManager.defaultWorkspaceDir.absolutePath
                }
            } else {
                fileSystemManager.defaultWorkspaceDir.absolutePath
            }

            if (sessions.value.isEmpty()) {
                runCatching { terminalManager.createSession(targetDir) }
                    .onFailure { _userMessage.value = "Could not open terminal: ${it.message}" }
            } else if (projectPath.isNotBlank()) {
                // If user specifically navigated to terminal with a project path, switch or create
                val matching = sessions.value.find { it.initialWorkingDir == targetDir }
                if (matching != null) {
                    terminalManager.selectSession(matching.id)
                } else {
                    val folderName = File(targetDir).name
                    runCatching { terminalManager.createSession(targetDir, title = folderName) }
                        .onFailure { _userMessage.value = "Could not open terminal: ${it.message}" }
                }
            }
        }
    }

    fun createNewSession(
        customWorkingDir: String? = null,
        runtimeType: com.devstation.android.future.runtime.RuntimeType = _selectedRuntime.value
    ) {
        viewModelScope.launch {
            val dir = customWorkingDir ?: activeSession.value?.initialWorkingDir ?: fileSystemManager.defaultWorkspaceDir.absolutePath
            val customEngine = if (runtimeType == com.devstation.android.future.runtime.RuntimeType.LINUX_USERSPACE) {
                linuxRuntimeManager?.createTerminalEngine()
            } else null

            runCatching {
                terminalManager.createSession(
                    workingDir = dir,
                    runtimeType = runtimeType,
                    customEngine = customEngine
                )
            }.onFailure { _userMessage.value = "Could not create session: ${it.message}" }
        }
    }

    fun selectRuntime(runtime: com.devstation.android.future.runtime.RuntimeType) {
        if (runtime == com.devstation.android.future.runtime.RuntimeType.REMOTE) {
            _userMessage.value = "Remote VPS runtime coming in Phase 12."
            return
        }

        _selectedRuntime.value = runtime

        if (runtime == com.devstation.android.future.runtime.RuntimeType.LINUX_USERSPACE) {
            val isInstalled = linuxRuntimeManager?.isInstalled() == true
            if (!isInstalled) {
                _userMessage.value = "Linux environment is not installed yet. Please install it first."
                return
            }
            val existingLinux = sessions.value.find { it.runtimeType == com.devstation.android.future.runtime.RuntimeType.LINUX_USERSPACE }
            if (existingLinux != null) {
                terminalManager.selectSession(existingLinux.id)
            } else {
                val dir = activeSession.value?.initialWorkingDir ?: fileSystemManager.defaultWorkspaceDir.absolutePath
                createNewSession(customWorkingDir = dir, runtimeType = com.devstation.android.future.runtime.RuntimeType.LINUX_USERSPACE)
            }
        } else if (runtime == com.devstation.android.future.runtime.RuntimeType.ANDROID_SHELL) {
            val existingAndroid = sessions.value.find { it.runtimeType == com.devstation.android.future.runtime.RuntimeType.ANDROID_SHELL }
            if (existingAndroid != null) {
                terminalManager.selectSession(existingAndroid.id)
            } else {
                val dir = activeSession.value?.initialWorkingDir ?: fileSystemManager.defaultWorkspaceDir.absolutePath
                createNewSession(customWorkingDir = dir, runtimeType = com.devstation.android.future.runtime.RuntimeType.ANDROID_SHELL)
            }
        }
    }

    fun selectSession(sessionId: String) {
        terminalManager.selectSession(sessionId)
        historyIndex = -1
    }

    fun closeSession(sessionId: String) {
        viewModelScope.launch {
            terminalManager.closeSession(sessionId)
        }
    }

    fun restartActiveSession() {
        viewModelScope.launch {
            val active = activeSession.value ?: return@launch
            terminalManager.restartSession(active.id)
        }
    }

    fun stopActiveSession() {
        viewModelScope.launch {
            val active = activeSession.value ?: return@launch
            terminalManager.stopSession(active.id)
        }
    }

    fun onInputChange(newInput: String) {
        _inputBuffer.value = newInput
    }

    fun executeCurrentCommand() {
        val command = _inputBuffer.value
        val active = activeSession.value ?: return
        viewModelScope.launch {
            runCatching { active.execute(command) }
                .onFailure { _userMessage.value = "Command failed: ${it.message}" }
            _inputBuffer.value = ""
            historyIndex = -1
            savedDraftInput = ""
        }
    }

    fun sendCtrlC() {
        viewModelScope.launch {
            activeSession.value?.sendInterrupt()
            _inputBuffer.value = ""
            historyIndex = -1
        }
    }

    fun sendCtrlD() {
        viewModelScope.launch {
            activeSession.value?.sendEof()
        }
    }

    fun sendCtrlL() {
        activeSession.value?.clearBuffer()
    }

    fun sendTab() {
        _inputBuffer.value = _inputBuffer.value + "    "
    }

    fun sendEscape() {
        _inputBuffer.value = ""
        historyIndex = -1
    }

    fun navigateHistoryUp() {
        val history = activeSession.value?.commandHistory?.value ?: return
        if (history.isEmpty()) return

        if (historyIndex == -1) {
            savedDraftInput = _inputBuffer.value
            historyIndex = history.size - 1
        } else if (historyIndex > 0) {
            historyIndex--
        }

        _inputBuffer.value = history.getOrElse(historyIndex) { "" }
    }

    fun navigateHistoryDown() {
        val history = activeSession.value?.commandHistory?.value ?: return
        if (historyIndex == -1) return

        if (historyIndex < history.size - 1) {
            historyIndex++
            _inputBuffer.value = history[historyIndex]
        } else {
            historyIndex = -1
            _inputBuffer.value = savedDraftInput
        }
    }

    fun setAutoScroll(enabled: Boolean) {
        _isAutoScroll.value = enabled
    }

    fun clearBuffer() {
        activeSession.value?.clearBuffer()
    }

    fun updateConfig(config: TerminalConfig) {
        _config.value = config
    }

    fun dismissUserMessage() {
        _userMessage.value = null
    }

    companion object {
        fun provideFactory(
            terminalManager: TerminalManager,
            fileSystemManager: ProjectFileSystemManager,
            linuxRuntimeManager: com.devstation.android.future.runtime.LinuxRuntimeManager? = null,
            initialProjectPath: String = ""
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TerminalViewModel(terminalManager, fileSystemManager, linuxRuntimeManager, initialProjectPath) as T
            }
        }
    }
}
