package com.devstation.android.future.terminal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Terminal Engine contract for Phase 2: Terminal & Process Management.
 */
interface TerminalEngine {
    val state: StateFlow<TerminalState>
    val outputLines: Flow<TerminalOutputLine>
    val currentWorkingDir: StateFlow<String>
    val shellInfo: ShellInfo
    val isRunning: Boolean

    suspend fun startSession(workingDir: String, environment: Map<String, String> = emptyMap())
    suspend fun sendCommand(command: String)
    suspend fun writeInput(data: ByteArray)
    suspend fun sendInterrupt()
    suspend fun sendEof()
    suspend fun resize(columns: Int, rows: Int)
    suspend fun stop()
    suspend fun restart()
    suspend fun terminate()
}
