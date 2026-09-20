package com.devstation.android.future.terminal

import com.devstation.android.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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

class LocalAndroidTerminal(
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope
) : TerminalEngine {

    private val _state = MutableStateFlow(TerminalState.IDLE)
    override val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val _outputLines = MutableSharedFlow<TerminalOutputLine>(replay = 50, extraBufferCapacity = 500)
    override val outputLines: SharedFlow<TerminalOutputLine> = _outputLines.asSharedFlow()

    private val _currentWorkingDir = MutableStateFlow("")
    override val currentWorkingDir: StateFlow<String> = _currentWorkingDir.asStateFlow()

    private var _shellInfo: ShellInfo = ShellDetector.detectShell()
    override val shellInfo: ShellInfo
        get() = _shellInfo

    override val isRunning: Boolean
        get() = _state.value == TerminalState.RUNNING

    private var process: Process? = null
    private var stdinWriter: OutputStreamWriter? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null
    private var processWatcherJob: Job? = null
    private var initialWorkingDir: String = ""

    override suspend fun startSession(workingDir: String, environment: Map<String, String>) = withContext(dispatchers.io) {
        if (_state.value == TerminalState.RUNNING) {
            return@withContext
        }

        _state.value = TerminalState.STARTING
        initialWorkingDir = workingDir

        val dirFile = File(workingDir)
        if (!dirFile.exists() || !dirFile.isDirectory) {
            _state.value = TerminalState.FAILED
            _outputLines.emit(
                TerminalOutputLine(
                    text = "Error: Working directory does not exist: $workingDir",
                    type = OutputType.SYSTEM
                )
            )
            throw IllegalArgumentException("Working directory does not exist: $workingDir")
        }

        _currentWorkingDir.value = dirFile.absolutePath
        _shellInfo = ShellDetector.detectShell()

        try {
            val processBuilder = ProcessBuilder(_shellInfo.path)
            processBuilder.directory(dirFile)

            val env = processBuilder.environment()
            env["TERM"] = "xterm-256color"
            env["PWD"] = dirFile.absolutePath
            for ((key, value) in environment) {
                env[key] = value
            }

            val proc = processBuilder.start()
            process = proc
            stdinWriter = OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8)
            _state.value = TerminalState.RUNNING

            _outputLines.emit(
                TerminalOutputLine(
                    text = "DevStation Terminal [${_shellInfo.name}]",
                    type = OutputType.SYSTEM
                )
            )
            _outputLines.emit(
                TerminalOutputLine(
                    text = "Working Directory: ${dirFile.absolutePath}",
                    type = OutputType.SYSTEM
                )
            )

            // Stream stdout
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
                                _outputLines.emit(
                                    TerminalOutputLine(
                                        text = lineBuilder.toString(),
                                        type = OutputType.STDOUT
                                    )
                                )
                                lineBuilder.clear()
                            } else if (c != '\r') {
                                lineBuilder.append(c)
                            }
                        }
                        if (lineBuilder.isNotEmpty() && !reader.ready()) {
                            _outputLines.emit(
                                TerminalOutputLine(
                                    text = lineBuilder.toString(),
                                    type = OutputType.STDOUT
                                )
                            )
                            lineBuilder.clear()
                        }
                    }
                    if (lineBuilder.isNotEmpty()) {
                        _outputLines.emit(
                            TerminalOutputLine(
                                text = lineBuilder.toString(),
                                type = OutputType.STDOUT
                            )
                        )
                    }
                } catch (e: Exception) {
                    if (isActive && _state.value == TerminalState.RUNNING) {
                        _outputLines.emit(
                            TerminalOutputLine(
                                text = "Stdout error: ${e.message}",
                                type = OutputType.SYSTEM
                            )
                        )
                    }
                }
            }

            // Stream stderr
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
                                _outputLines.emit(
                                    TerminalOutputLine(
                                        text = lineBuilder.toString(),
                                        type = OutputType.STDERR
                                    )
                                )
                                lineBuilder.clear()
                            } else if (c != '\r') {
                                lineBuilder.append(c)
                            }
                        }
                        if (lineBuilder.isNotEmpty() && !reader.ready()) {
                            _outputLines.emit(
                                TerminalOutputLine(
                                    text = lineBuilder.toString(),
                                    type = OutputType.STDERR
                                )
                            )
                            lineBuilder.clear()
                        }
                    }
                    if (lineBuilder.isNotEmpty()) {
                        _outputLines.emit(
                            TerminalOutputLine(
                                text = lineBuilder.toString(),
                                type = OutputType.STDERR
                            )
                        )
                    }
                } catch (e: Exception) {
                    if (isActive && _state.value == TerminalState.RUNNING) {
                        _outputLines.emit(
                            TerminalOutputLine(
                                text = "Stderr error: ${e.message}",
                                type = OutputType.SYSTEM
                            )
                        )
                    }
                }
            }

            // Watch process exit
            processWatcherJob = scope.launch(dispatchers.io) {
                val exitCode = proc.waitFor()
                _state.value = TerminalState.STOPPED
                _outputLines.emit(
                    TerminalOutputLine(
                        text = "Shell session terminated (exit code: $exitCode)",
                        type = OutputType.SYSTEM
                    )
                )
            }

        } catch (e: Exception) {
            _state.value = TerminalState.FAILED
            _outputLines.emit(
                TerminalOutputLine(
                    text = "Unable to start local shell: ${e.message}",
                    type = OutputType.SYSTEM
                )
            )
        }
    }

    override suspend fun sendCommand(command: String) = withContext(dispatchers.io) {
        val writer = stdinWriter ?: return@withContext
        if (_state.value != TerminalState.RUNNING) {
            _outputLines.emit(
                TerminalOutputLine(
                    text = "Error: Shell is not running. Please restart the session.",
                    type = OutputType.SYSTEM
                )
            )
            return@withContext
        }

        try {
            _outputLines.emit(
                TerminalOutputLine(
                    text = "$ $command",
                    type = OutputType.COMMAND
                )
            )

            // Handle cd command directory tracking
            if (command.trim().startsWith("cd ") || command.trim() == "cd") {
                handleDirectoryChange(command.trim())
            }

            writer.write(command + "\n")
            writer.flush()
        } catch (e: Exception) {
            _outputLines.emit(
                TerminalOutputLine(
                    text = "Failed to send command: ${e.message}",
                    type = OutputType.SYSTEM
                )
            )
        }
    }

    private fun handleDirectoryChange(cdCommand: String) {
        val targetPath = cdCommand.removePrefix("cd").trim()
        val current = File(_currentWorkingDir.value)
        val target = if (targetPath.isEmpty() || targetPath == "~") {
            File(initialWorkingDir)
        } else if (targetPath.startsWith("/")) {
            File(targetPath)
        } else {
            File(current, targetPath)
        }

        if (target.exists() && target.isDirectory) {
            _currentWorkingDir.value = target.canonicalPath
        }
    }

    override suspend fun writeInput(data: ByteArray) = withContext(dispatchers.io) {
        val proc = process ?: return@withContext
        try {
            proc.outputStream.write(data)
            proc.outputStream.flush()
        } catch (e: Exception) {
            _outputLines.emit(
                TerminalOutputLine(
                    text = "Write error: ${e.message}",
                    type = OutputType.SYSTEM
                )
            )
        }
    }

    override suspend fun sendInterrupt() = withContext(dispatchers.io) {
        // Send Ctrl+C (0x03)
        _outputLines.emit(
            TerminalOutputLine(
                text = "^C",
                type = OutputType.COMMAND
            )
        )
        writeInput(byteArrayOf(3))
    }

    override suspend fun sendEof() = withContext(dispatchers.io) {
        // Send Ctrl+D (0x04)
        writeInput(byteArrayOf(4))
    }

    override suspend fun resize(columns: Int, rows: Int) {
        // Standard Android Java ProcessBuilder pipes do not support ioctl(TIOCSWINSZ).
        // The contract accepts dimensions and prepares for Phase 3 PTY integration.
    }

    override suspend fun stop() = withContext(dispatchers.io) {
        if (_state.value != TerminalState.RUNNING) return@withContext
        _state.value = TerminalState.STOPPING
        try {
            process?.destroy()
        } catch (e: Exception) {
            // Ignore
        }
        _state.value = TerminalState.STOPPED
    }

    override suspend fun restart() = withContext(dispatchers.io) {
        terminate()
        if (initialWorkingDir.isNotBlank()) {
            startSession(initialWorkingDir)
        }
    }

    override suspend fun terminate() = withContext(dispatchers.io) {
        _state.value = TerminalState.STOPPING
        stdoutJob?.cancel()
        stderrJob?.cancel()
        processWatcherJob?.cancel()

        try {
            stdinWriter?.close()
        } catch (e: Exception) {
            // Ignore
        }

        try {
            process?.destroyForcibly()
        } catch (e: Exception) {
            // Ignore
        }

        process = null
        stdinWriter = null
        _state.value = TerminalState.STOPPED
    }
}
