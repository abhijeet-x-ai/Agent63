package com.devstation.android.core.diagnostics

import android.os.Build
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Subsystems monitored during Agent 63 startup and runtime.
 */
enum class Subsystem(val displayName: String) {
    APPLICATION("Application Core"),
    STORAGE("Internal Storage"),
    DATABASE("Room Database"),
    SECURITY("Keystore & Security"),
    TERMINAL_LINUX("Terminal & Linux Runtime"),
    AI("AI Provider System"),
    MCP("Model Context Protocol (MCP)"),
    SKILLS("Agent Skills & Tools"),
    BROWSER_PREVIEW("Browser & Live Preview"),
    GIT_GITHUB("Git & GitHub Integration")
}

/**
 * Health states for monitored subsystems.
 */
enum class SubsystemState {
    PENDING,
    INITIALIZING,
    READY,
    DEGRADED,
    FAILED
}

/**
 * Snapshot of a subsystem's health and initialization details.
 */
data class SubsystemReport(
    val subsystem: Subsystem,
    val state: SubsystemState,
    val details: String? = null,
    val errorMessage: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Record of any uncaught exceptions caught by the global handler.
 */
data class CrashRecord(
    val threadName: String,
    val exceptionClass: String,
    val message: String,
    val stackTraceSnippet: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Centralized, thread-safe diagnostics tracker for Agent 63 startup and system health.
 * Ensures zero unhandled crashes escape during subsystem initialization,
 * and maintains audit records for graceful degradation.
 */
object StartupDiagnostics {

    private const val TAG = "Agent63Diagnostics"

    private val subsystemStates = ConcurrentHashMap<Subsystem, SubsystemReport>()
    private val crashRecords = CopyOnWriteArrayList<CrashRecord>()
    private val diagnosticLogs = CopyOnWriteArrayList<String>()

    init {
        // Initialize all subsystems to PENDING
        for (subsystem in Subsystem.values()) {
            subsystemStates[subsystem] = SubsystemReport(
                subsystem = subsystem,
                state = SubsystemState.PENDING,
                details = "Awaiting startup trigger"
            )
        }
    }

    /**
     * Record transition or status update for a subsystem.
     */
    fun record(
        subsystem: Subsystem,
        state: SubsystemState,
        details: String? = null,
        error: Throwable? = null
    ) {
        try {
            val sanitizedMessage = error?.let { sanitize(it.message ?: it.javaClass.simpleName) }
            val report = SubsystemReport(
                subsystem = subsystem,
                state = state,
                details = details?.let { sanitize(it) },
                errorMessage = sanitizedMessage,
                timestampMs = System.currentTimeMillis()
            )
            subsystemStates[subsystem] = report

            val logMessage = "[${subsystem.name}] -> $state: ${details ?: ""} ${sanitizedMessage ?: ""}".trim()
            addLog(logMessage)

            when (state) {
                SubsystemState.FAILED -> Log.e(TAG, logMessage, error)
                SubsystemState.DEGRADED -> Log.w(TAG, logMessage, error)
                SubsystemState.READY -> Log.i(TAG, logMessage)
                else -> Log.d(TAG, logMessage)
            }
        } catch (_: Throwable) {
            // Diagnostics must never fail or throw
        }
    }

    /**
     * Record an uncaught exception intercepted by the crash handler.
     */
    fun recordUncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val fullTrace = sw.toString()
            val snippet = fullTrace.lines().take(10).joinToString("\n")

            val record = CrashRecord(
                threadName = thread.name,
                exceptionClass = throwable.javaClass.name,
                message = sanitize(throwable.message ?: "No message"),
                stackTraceSnippet = sanitize(snippet)
            )
            crashRecords.add(record)
            val logMsg = "CRASH INTERCEPTED on ${thread.name}: ${record.exceptionClass} - ${record.message}"
            addLog(logMsg)
            Log.e(TAG, logMsg, throwable)
        } catch (_: Throwable) {
            // Diagnostics must never throw
        }
    }

    fun getReport(subsystem: Subsystem): SubsystemReport {
        return subsystemStates[subsystem] ?: SubsystemReport(subsystem, SubsystemState.PENDING)
    }

    fun getAllReports(): List<SubsystemReport> {
        return Subsystem.values().map { getReport(it) }
    }

    fun getCrashRecords(): List<CrashRecord> {
        return crashRecords.toList()
    }

    fun getRecentLogs(limit: Int = 50): List<String> {
        return diagnosticLogs.takeLast(limit)
    }

    fun hasFailures(): Boolean {
        return subsystemStates.values.any { it.state == SubsystemState.FAILED }
    }

    fun hasDegradations(): Boolean {
        return subsystemStates.values.any { it.state == SubsystemState.DEGRADED }
    }

    fun isAllCriticalReady(): Boolean {
        // APPLICATION, STORAGE, DATABASE, and SECURITY are critical foundation subsystems
        val critical = listOf(Subsystem.APPLICATION, Subsystem.STORAGE, Subsystem.DATABASE, Subsystem.SECURITY)
        return critical.all {
            val state = subsystemStates[it]?.state
            state == SubsystemState.READY || state == SubsystemState.DEGRADED
        }
    }

    /**
     * Produces a human-readable diagnostic report for logging and troubleshooting.
     */
    fun generateSummary(): String {
        val sb = StringBuilder()
        sb.append("Agent 63 System Health Summary\n")
        sb.append("Android API: ${Build.VERSION.SDK_INT}, Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("----------------------------------------\n")
        for (subsystem in Subsystem.values()) {
            val rep = getReport(subsystem)
            sb.append("${rep.subsystem.displayName}: ${rep.state}")
            rep.details?.let { sb.append(" ($it)") }
            rep.errorMessage?.let { sb.append(" [Error: $it]") }
            sb.append("\n")
        }
        if (crashRecords.isNotEmpty()) {
            sb.append("----------------------------------------\n")
            sb.append("Logged Crash Interceptions: ${crashRecords.size}\n")
            for (cr in crashRecords) {
                sb.append(" - [${cr.threadName}] ${cr.exceptionClass}: ${cr.message}\n")
            }
        }
        return sb.toString()
    }

    private fun addLog(message: String) {
        if (diagnosticLogs.size > 200) {
            diagnosticLogs.removeAt(0)
        }
        diagnosticLogs.add("${System.currentTimeMillis()}: $message")
    }

    /**
     * Redacts tokens, api keys, bearer headers, passwords, and private key strings from diagnostic output.
     */
    private fun sanitize(input: String): String {
        return input
            .replace(Regex("(?i)(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{16,}"), "[REDACTED_GH_TOKEN]")
            .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9_\\-\\.]+"), "Bearer [REDACTED_TOKEN]")
            .replace(Regex("(?i)(sk-[A-Za-z0-9]{20,})"), "[REDACTED_API_KEY]")
            .replace(Regex("(?i)(password|secret|token)=\\S+"), "$1=[REDACTED]")
            .replace(Regex("(?i)-----BEGIN [A-Z ]+ PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]+ PRIVATE KEY-----"), "[REDACTED_PRIVATE_KEY]")
    }
}
