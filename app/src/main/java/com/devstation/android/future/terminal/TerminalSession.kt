package com.devstation.android.future.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class TerminalSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Terminal",
    val initialWorkingDir: String,
    val projectId: String? = null,
    val engine: TerminalEngine,
    val scope: CoroutineScope,
    val runtimeType: com.devstation.android.future.runtime.RuntimeType = com.devstation.android.future.runtime.RuntimeType.ANDROID_SHELL,
    private val maxScrollbackLines: Int = 5000
) {
    private val _outputBuffer = MutableStateFlow<List<TerminalOutputLine>>(emptyList())
    val outputBuffer: StateFlow<List<TerminalOutputLine>> = _outputBuffer.asStateFlow()

    private val _commandHistory = MutableStateFlow<List<String>>(emptyList())
    val commandHistory: StateFlow<List<String>> = _commandHistory.asStateFlow()

    val state: StateFlow<TerminalState> = engine.state
    val currentWorkingDir: StateFlow<String> = engine.currentWorkingDir
    val shellInfo: ShellInfo = engine.shellInfo

    private val collectJob = scope.launch {
        engine.outputLines.collect { line ->
            val currentList = _outputBuffer.value
            val updatedList = if (currentList.size >= maxScrollbackLines) {
                currentList.drop(currentList.size - maxScrollbackLines + 1) + line
            } else {
                currentList + line
            }
            _outputBuffer.value = updatedList
        }
    }

    suspend fun execute(command: String) {
        val trimmed = command.trim()
        if (trimmed.isNotBlank()) {
            if (!containsSecret(trimmed)) {
                val history = _commandHistory.value
                if (history.lastOrNull() != trimmed) {
                    _commandHistory.value = history + trimmed
                }
            }
            engine.sendCommand(trimmed)
        }
    }

    suspend fun sendInterrupt() {
        engine.sendInterrupt()
    }

    suspend fun sendEof() {
        engine.sendEof()
    }

    fun clearBuffer() {
        _outputBuffer.value = emptyList()
    }

    suspend fun restart() {
        engine.restart()
    }

    suspend fun stop() {
        engine.stop()
    }

    suspend fun close() {
        collectJob.cancel()
        engine.terminate()
    }

    private fun containsSecret(cmd: String): Boolean {
        val lower = cmd.lowercase()
        return lower.contains("password=") ||
                lower.contains("secret=") ||
                lower.contains("token=") ||
                lower.contains("api_key=")
    }
}
