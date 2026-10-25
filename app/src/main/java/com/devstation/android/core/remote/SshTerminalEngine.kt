package com.devstation.android.core.remote

import com.devstation.android.future.terminal.OutputType
import com.devstation.android.future.terminal.ShellInfo
import com.devstation.android.future.terminal.TerminalEngine
import com.devstation.android.future.terminal.TerminalOutputLine
import com.devstation.android.future.terminal.TerminalState
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 11: a [TerminalEngine] whose shell lives on a remote SSH server.
 *
 * Plugging into the existing engine contract means the whole terminal UI
 * (sessions, tabs, scrollback, ANSI rendering) works for remote shells with
 * zero UI changes: [com.devstation.android.future.terminal.TerminalManager]
 * accepts this engine via its `customEngine` parameter with
 * `RuntimeType.REMOTE`.
 *
 * Output is decoded as UTF-8 incrementally (multi-byte characters split
 * across packets are never corrupted) and emitted as raw chunks, exactly
 * like the local engines.
 */
class SshTerminalEngine(
    private val sessionManager: SshSessionManager,
    private val profile: SshHostProfile,
    private val remoteWorkingDir: String? = null
) : TerminalEngine {

    /** Engine-owned scope: the output pump lives and dies with the engine. */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(TerminalState.IDLE)
    override val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val _outputLines = MutableSharedFlow<TerminalOutputLine>(
        replay = 50,
        extraBufferCapacity = 500
    )
    override val outputLines: SharedFlow<TerminalOutputLine> = _outputLines.asSharedFlow()

    private val _currentWorkingDir = MutableStateFlow("~")
    override val currentWorkingDir: StateFlow<String> = _currentWorkingDir.asStateFlow()

    override val shellInfo: ShellInfo = ShellInfo(
        name = "ssh",
        path = profile.displayAddress,
        isAvailable = true,
        ptySupported = true
    )

    override val isRunning: Boolean get() = _state.value == TerminalState.RUNNING

    private var channel: SshShellChannel? = null
    private var readerJob: Job? = null
    private var columns: Int = 120
    private var rows: Int = 32

    override suspend fun startSession(workingDir: String, environment: Map<String, String>) {
        if (_state.value == TerminalState.RUNNING) return
        _state.value = TerminalState.STARTING
        try {
            val shell = withContext(Dispatchers.IO) { sessionManager.openShell(columns, rows) }
            channel = shell
            _state.value = TerminalState.RUNNING
            emitSystem("Connected to ${profile.displayAddress}")
            readerJob = engineScope.launch { pumpOutput(shell) }
            remoteWorkingDir?.takeIf { it.isNotBlank() }?.let { dir ->
                sendCommand("cd \"$dir\"")
            }
        } catch (t: Throwable) {
            _state.value = TerminalState.FAILED
            emitSystem("SSH shell failed: ${t.message ?: "unknown error"}")
            throw t
        }
    }

    override suspend fun sendCommand(command: String) {
        writeInput((command + "\n").toByteArray(Charsets.UTF_8))
    }

    override suspend fun writeInput(data: ByteArray) {
        val shell = channel ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                shell.outputStream.write(data)
                shell.outputStream.flush()
            }
        }
    }

    override suspend fun sendInterrupt() {
        writeInput(byteArrayOf(0x03))
    }

    override suspend fun sendEof() {
        writeInput(byteArrayOf(0x04))
    }

    override suspend fun resize(columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        val shell = channel ?: return
        withContext(Dispatchers.IO) { runCatching { shell.resize(columns, rows) } }
    }

    override suspend fun stop() {
        if (_state.value == TerminalState.STOPPED) return
        _state.value = TerminalState.STOPPING
        readerJob?.cancel()
        readerJob = null
        val shell = channel
        channel = null
        if (shell != null) withContext(Dispatchers.IO) { runCatching { shell.close() } }
        _state.value = TerminalState.STOPPED
    }

    override suspend fun restart() {
        stop()
        startSession(_currentWorkingDir.value)
    }

    override suspend fun terminate() {
        stop()
        engineScope.coroutineContext[Job]?.cancel()
    }

    private suspend fun pumpOutput(shell: SshShellChannel) {
        try {
            decodeUtf8(shell.inputStream) { text ->
                _outputLines.emit(TerminalOutputLine(text, OutputType.STDOUT))
            }
        } catch (_: Throwable) {
            // Stream teardown races are normal on stop()/disconnect.
        } finally {
            if (_state.value == TerminalState.RUNNING) {
                _state.value = TerminalState.STOPPED
                emitSystem("SSH session closed")
            }
        }
    }

    private suspend fun emitSystem(text: String) {
        _outputLines.emit(TerminalOutputLine(text, OutputType.SYSTEM))
    }

    /** Incremental UTF-8 decoding so split multi-byte sequences survive packet boundaries. */
    private suspend fun decodeUtf8(input: InputStream, onText: suspend (String) -> Unit) {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val raw = ByteArray(8_192)
        val byteBuffer = ByteBuffer.allocate(8_192)
        val charBuffer = CharBuffer.allocate(8_192)
        while (true) {
            val read = input.read(raw)
            if (read < 0) break
            byteBuffer.put(raw, 0, read)
            byteBuffer.flip()
            decoder.decode(byteBuffer, charBuffer, false)
            byteBuffer.compact()
            charBuffer.flip()
            if (charBuffer.hasRemaining()) {
                onText(charBuffer.toString())
            }
            charBuffer.clear()
        }
    }
}
