package com.devstation.android.future.terminal

enum class TerminalState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}

enum class OutputType {
    STDOUT,
    STDERR,
    SYSTEM,
    COMMAND
}

data class TerminalOutputLine(
    val text: String,
    val type: OutputType,
    val timestamp: Long = System.currentTimeMillis()
)

data class ShellInfo(
    val name: String,
    val path: String,
    val isAvailable: Boolean,
    val ptySupported: Boolean = false
)

data class CommandHistoryItem(
    val command: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class TerminalConfig(
    val fontSizeSp: Int = 12,
    val maxScrollbackLines: Int = 5000,
    val autoScroll: Boolean = true
)
