package com.devstation.android.future.runtime

import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.future.terminal.OutputType
import com.devstation.android.future.terminal.ShellInfo
import com.devstation.android.future.terminal.TerminalEngine
import com.devstation.android.future.terminal.TerminalOutputLine
import com.devstation.android.future.terminal.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Terminal engine executing inside the Linux userspace runtime.
 * Implements the standard TerminalEngine interface for seamless UI reuse.
 */
class LinuxTerminalEngine(
    private val launcher: LinuxProcessLauncher,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope
) : TerminalEngine {

    private val _state = MutableStateFlow(TerminalState.IDLE)
    override val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val _outputLines = MutableSharedFlow<TerminalOutputLine>(replay = 200)
    override val outputLines: Flow<TerminalOutputLine> = _outputLines.asSharedFlow()

    private val _currentWorkingDir = MutableStateFlow("/workspace")
    override val currentWorkingDir: StateFlow<String> = _currentWorkingDir.asStateFlow()

    override val shellInfo: ShellInfo = ShellInfo(
        name = "Linux Userspace Shell (/bin/sh)",
        path = "/bin/sh",
        isAvailable = true,
        ptySupported = false
    )

    override val isRunning: Boolean
        get() = _state.value == TerminalState.RUNNING

    private var process: Process? = null
    private var processWriter: OutputStreamWriter? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null
    private var watcherJob: Job? = null
    private var activeHostWorkspaceDir: File? = null

    override suspend fun startSession(workingDir: String, environment: Map<String, String>) = withContext(dispatchers.io) {
        if (_state.value == TerminalState.RUNNING) {
            stop()
        }

        _state.value = TerminalState.STARTING
        val hostDir = File(workingDir)
        activeHostWorkspaceDir = hostDir

        try {
            // Launch interactive shell inside Linux userspace
            val guestShellCommand = listOf("/bin/sh", "-i")
            val proc = launcher.launchProcess(guestShellCommand, hostDir, environment)
            process = proc
            processWriter = OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8)
            _state.value = TerminalState.RUNNING

            // Emit welcome banner
            _outputLines.emit(
                TerminalOutputLine(
                    text = "\u001B[1;36mDevStation Linux Userspace Environment\u001B[0m",
                    type = OutputType.SYSTEM
                )
            )
            _outputLines.emit(
                TerminalOutputLine(
                    text = "Mounted project: ${hostDir.name} -> /workspace",
                    type = OutputType.SYSTEM
                )
            )
            _outputLines.emit(
                TerminalOutputLine(
                    text = "Persistent HOME: /home/devstation",
                    type = OutputType.SYSTEM
                )
            )

            // Read stdout
            stdoutJob = scope.launch(dispatchers.io) {
                val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
                try {
                    val buffer = CharArray(1024)
                    var charsRead = 0
                    val lineBuilder = StringBuilder()

                    while (isActive && reader.read(buffer).also { charsRead = it } != -1) {
                        for (i in 0 until charsRead) {
                            val c = buffer[i]
                            if (c == '\n') {
                                _outputLines.emit(TerminalOutputLine(lineBuilder.toString(), OutputType.STDOUT))
                                lineBuilder.clear()
                            } else if (c != '\r') {
                                lineBuilder.append(c)
                            }
                        }
                        if (lineBuilder.isNotEmpty() && !reader.ready()) {
                            _outputLines.emit(TerminalOutputLine(lineBuilder.toString(), OutputType.STDOUT))
                            lineBuilder.clear()
                        }
                    }
                    if (lineBuilder.isNotEmpty()) {
                        _outputLines.emit(TerminalOutputLine(lineBuilder.toString(), OutputType.STDOUT))
                    }
                } catch (e: Exception) {
                    if (isActive && _state.value == TerminalState.RUNNING) {
                        _outputLines.emit(TerminalOutputLine("Linux stdout error: ${e.message}", OutputType.SYSTEM))
                    }
                }
            }

            // Read stderr
            stderrJob = scope.launch(dispatchers.io) {
                val reader = BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8))
                try {
                    val buffer = CharArray(1024)
                    var charsRead = 0
                    val lineBuilder = StringBuilder()

                    while (isActive && reader.read(buffer).also { charsRead = it } != -1) {
                        for (i in 0 until charsRead) {
                            val c = buffer[i]
                            if (c == '\n') {
                                _outputLines.emit(TerminalOutputLine(lineBuilder.toString(), OutputType.STDERR))
                                lineBuilder.clear()
                            } else if (c != '\r') {
                                lineBuilder.append(c)
                            }
                        }
                        if (lineBuilder.isNotEmpty() && !reader.ready()) {
                            _outputLines.emit(TerminalOutputLine(lineBuilder.toString(), OutputType.STDERR))
                            lineBuilder.clear()
                        }
                    }
                    if (lineBuilder.isNotEmpty()) {
                        _outputLines.emit(TerminalOutputLine(lineBuilder.toString(), OutputType.STDERR))
                    }
                } catch (e: Exception) {
                    if (isActive && _state.value == TerminalState.RUNNING) {
                        _outputLines.emit(TerminalOutputLine("Linux stderr error: ${e.message}", OutputType.SYSTEM))
                    }
                }
            }

            // Watch process exit
            watcherJob = scope.launch(dispatchers.io) {
                try {
                    val exitCode = proc.waitFor()
                    _state.value = TerminalState.STOPPED
                    _outputLines.emit(
                        TerminalOutputLine(
                            text = "[Process completed with exit code $exitCode]",
                            type = OutputType.SYSTEM
                        )
                    )
                } catch (e: Exception) {
                    _state.value = TerminalState.STOPPED
                }
            }
        } catch (e: Exception) {
            _state.value = TerminalState.FAILED
            _outputLines.emit(
                TerminalOutputLine(
                    text = "Failed to launch Linux runtime: ${e.message}",
                    type = OutputType.SYSTEM
                )
            )
        }
    }

    override suspend fun sendCommand(command: String) = withContext(dispatchers.io) {
        val writer = processWriter
        if (writer != null && _state.value == TerminalState.RUNNING) {
            try {
                _outputLines.emit(TerminalOutputLine(text = command, type = OutputType.COMMAND))
                writer.write(command)
                writer.write("\n")
                writer.flush()
            } catch (e: Exception) {
                _outputLines.emit(
                    TerminalOutputLine(text = "Failed to write command: ${e.message}", type = OutputType.SYSTEM)
                )
            }
        }
    }

    override suspend fun writeInput(data: ByteArray): Unit = withContext(dispatchers.io) {
        process?.outputStream?.let { stream ->
            try {
                stream.write(data)
                stream.flush()
            } catch (e: Exception) {
                // Ignore stream closed
            }
        }
        Unit
    }

    override suspend fun sendInterrupt(): Unit = withContext(dispatchers.io) {
        writeInput(byteArrayOf(3)) // ASCII ETX (Ctrl+C)
        _outputLines.emit(TerminalOutputLine(text = "^C", type = OutputType.COMMAND))
    }

    override suspend fun sendEof(): Unit = withContext(dispatchers.io) {
        writeInput(byteArrayOf(4)) // ASCII EOT (Ctrl+D)
    }

    override suspend fun resize(columns: Int, rows: Int) {
        // Pseudo-terminal resize hook for future PTY integration
    }

    override suspend fun stop() = withContext(dispatchers.io) {
        _state.value = TerminalState.STOPPING
        stdoutJob?.cancel()
        stderrJob?.cancel()
        watcherJob?.cancel()

        try {
            processWriter?.close()
        } catch (e: Exception) {
            // Ignore
        }

        process?.let { proc ->
            proc.destroy()
            try {
                proc.destroyForcibly()
            } catch (e: Exception) {
                // Ignore
            }
        }

        process = null
        processWriter = null
        _state.value = TerminalState.STOPPED
    }

    override suspend fun restart() {
        val dir = activeHostWorkspaceDir?.absolutePath ?: "/workspace"
        stop()
        startSession(dir)
    }

    override suspend fun terminate() {
        stop()
    }
}
