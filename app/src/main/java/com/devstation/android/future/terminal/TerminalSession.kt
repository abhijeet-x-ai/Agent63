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

    // v1.1.4: ring buffer + batched emissions. The old code copied the whole
    // 5000-line list on every line (O(n^2)) and recomposed the full list per
    // keystroke of `cat big.log`. Now lines accumulate in a deque and snapshots
    // flush at most every 120ms or 100 lines.
    private val ringLock = Any()
    private val ring = ArrayDeque<TerminalOutputLine>(maxScrollbackLines)
    private var pendingLines = 0
    private var lastEmitMs = 0L

    private val collectJob = scope.launch {
        engine.outputLines.collect { line ->
            val snapshot: List<TerminalOutputLine>? = synchronized(ringLock) {
                if (ring.size >= maxScrollbackLines) {
                    ring.removeFirst()
                }
                ring.addLast(line)
                pendingLines++
                val now = System.currentTimeMillis()
                if (pendingLines >= 100 || now - lastEmitMs >= 120 || ring.size < 500) {
                    pendingLines = 0
                    lastEmitMs = now
                    ring.toList()
                } else {
                    null
                }
            }
            snapshot?.let { _outputBuffer.value = it }
        }
    }

    /** Flush any lines batched since the last emission (e.g. before close). */
    private fun flushRing() {
        synchronized(ringLock) {
            if (pendingLines > 0) {
                _outputBuffer.value = ring.toList()
                pendingLines = 0
                lastEmitMs = System.currentTimeMillis()
            }
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
        synchronized(ringLock) {
            ring.clear()
            pendingLines = 0
        }
        _outputBuffer.value = emptyList()
    }

    suspend fun restart() {
        engine.restart()
    }

    suspend fun stop() {
        engine.stop()
    }

    suspend fun close() {
        flushRing()
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
