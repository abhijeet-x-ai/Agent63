package com.devstation.android.future.terminal

import com.devstation.android.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalManager(
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    private val engineFactory: (DispatcherProvider, CoroutineScope) -> TerminalEngine = { d, s ->
        LocalAndroidTerminal(d, s)
    }
) {
    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _activeSession = MutableStateFlow<TerminalSession?>(null)
    val activeSession: StateFlow<TerminalSession?> = _activeSession.asStateFlow()

    private var sessionCounter = 1

    private fun updateActiveSession() {
        val id = _activeSessionId.value
        val list = _sessions.value
        _activeSession.value = list.find { it.id == id } ?: list.firstOrNull()
    }

    suspend fun createSession(
        workingDir: String,
        title: String? = null,
        projectId: String? = null,
        runtimeType: com.devstation.android.future.runtime.RuntimeType = com.devstation.android.future.runtime.RuntimeType.ANDROID_SHELL,
        customEngine: TerminalEngine? = null
    ): TerminalSession = withContext(dispatchers.io) {
        val targetDir = java.io.File(workingDir)
        require(targetDir.exists() && targetDir.isDirectory) {
            "Working directory does not exist or is not a directory: $workingDir"
        }

        val sessionTitle = title ?: when (runtimeType) {
            com.devstation.android.future.runtime.RuntimeType.LINUX_USERSPACE -> "Linux $sessionCounter"
            else -> "Terminal $sessionCounter"
        }
        sessionCounter++

        val engine = customEngine ?: engineFactory(dispatchers, scope)
        val session = TerminalSession(
            title = sessionTitle,
            initialWorkingDir = targetDir.canonicalPath,
            projectId = projectId,
            engine = engine,
            scope = scope,
            runtimeType = runtimeType
        )

        _sessions.value = _sessions.value + session
        _activeSessionId.value = session.id
        updateActiveSession()

        session.engine.startSession(targetDir.canonicalPath)
        session
    }

    fun selectSession(sessionId: String) {
        val exists = _sessions.value.any { it.id == sessionId }
        if (exists) {
            _activeSessionId.value = sessionId
            updateActiveSession()
        }
    }

    suspend fun closeSession(sessionId: String) = withContext(dispatchers.io) {
        val target = _sessions.value.find { it.id == sessionId } ?: return@withContext
        target.close()

        val updated = _sessions.value.filter { it.id != sessionId }
        _sessions.value = updated

        if (_activeSessionId.value == sessionId) {
            _activeSessionId.value = updated.firstOrNull()?.id
        }
        updateActiveSession()
    }

    suspend fun restartSession(sessionId: String) = withContext(dispatchers.io) {
        val target = _sessions.value.find { it.id == sessionId } ?: return@withContext
        target.restart()
    }

    suspend fun stopSession(sessionId: String) = withContext(dispatchers.io) {
        val target = _sessions.value.find { it.id == sessionId } ?: return@withContext
        target.stop()
    }

    suspend fun closeAll() = withContext(dispatchers.io) {
        for (session in _sessions.value) {
            session.close()
        }
        _sessions.value = emptyList()
        _activeSessionId.value = null
        updateActiveSession()
    }
}
