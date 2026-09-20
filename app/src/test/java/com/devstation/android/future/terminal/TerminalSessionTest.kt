package com.devstation.android.future.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTerminalEngine : TerminalEngine {
    private val _state = MutableStateFlow(TerminalState.IDLE)
    override val state: StateFlow<TerminalState> = _state

    private val _outputLines = MutableSharedFlow<TerminalOutputLine>(replay = 100)
    override val outputLines: Flow<TerminalOutputLine> = _outputLines

    private val _currentWorkingDir = MutableStateFlow("/test/dir")
    override val currentWorkingDir: StateFlow<String> = _currentWorkingDir

    override val shellInfo: ShellInfo = ShellInfo("FakeShell", "/bin/fake", true, false)
    override val isRunning: Boolean
        get() = _state.value == TerminalState.RUNNING

    val commandsReceived = mutableListOf<String>()

    override suspend fun startSession(workingDir: String, environment: Map<String, String>) {
        _state.value = TerminalState.RUNNING
        _currentWorkingDir.value = workingDir
    }

    override suspend fun sendCommand(command: String) {
        commandsReceived.add(command)
        _outputLines.emit(TerminalOutputLine(command, OutputType.COMMAND))
    }

    override suspend fun writeInput(data: ByteArray) {}

    override suspend fun sendInterrupt() {
        _outputLines.emit(TerminalOutputLine("^C", OutputType.COMMAND))
    }

    override suspend fun sendEof() {}

    override suspend fun resize(columns: Int, rows: Int) {}

    override suspend fun stop() {
        _state.value = TerminalState.STOPPED
    }

    override suspend fun restart() {
        _state.value = TerminalState.RUNNING
    }

    override suspend fun terminate() {
        _state.value = TerminalState.STOPPED
    }

    suspend fun emitOutput(text: String, type: OutputType = OutputType.STDOUT) {
        _outputLines.emit(TerminalOutputLine(text, type))
    }
}

class TerminalSessionTest {

    @Test
    fun `command history excludes secrets`() = runTest {
        val engine = FakeTerminalEngine()
        val session = TerminalSession(
            initialWorkingDir = "/test",
            engine = engine,
            scope = this
        )

        session.execute("ls -la")
        session.execute("echo password=secret123")
        session.execute("pwd")

        val history = session.commandHistory.value
        assertTrue(history.contains("ls -la"))
        assertTrue(history.contains("pwd"))
        assertFalse(history.any { it.contains("password=") })
        session.close()
    }

    @Test
    fun `output buffer maintains bounded limit`() = runTest {
        val engine = FakeTerminalEngine()
        val maxLines = 10
        val session = TerminalSession(
            initialWorkingDir = "/test",
            engine = engine,
            scope = this,
            maxScrollbackLines = maxLines
        )

        for (i in 1..25) {
            engine.emitOutput("Line $i")
            testScheduler.advanceUntilIdle()
        }

        val buffer = session.outputBuffer.value
        assertTrue(buffer.size <= maxLines)
        assertEquals("Line 25", buffer.last().text)
        session.close()
    }

    @Test
    fun `clearBuffer resets output lines`() = runTest {
        val engine = FakeTerminalEngine()
        val session = TerminalSession(
            initialWorkingDir = "/test",
            engine = engine,
            scope = this
        )

        engine.emitOutput("Test output")
        testScheduler.advanceUntilIdle()
        session.clearBuffer()

        assertTrue(session.outputBuffer.value.isEmpty())
        session.close()
    }
}
