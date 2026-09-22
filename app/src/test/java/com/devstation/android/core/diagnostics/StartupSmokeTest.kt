package com.devstation.android.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StartupSmokeTest {

    @Before
    fun setUp() {
        // Reset or initialize diagnostics
        for (subsystem in Subsystem.values()) {
            StartupDiagnostics.record(subsystem, SubsystemState.PENDING, "Test Reset")
        }
    }

    @Test
    fun testAllSubsystemsInitializedToPending() {
        val reports = StartupDiagnostics.getAllReports()
        assertEquals(Subsystem.values().size, reports.size)
        assertTrue(reports.all { it.state == SubsystemState.PENDING })
    }

    @Test
    fun testCriticalSubsystemsReadiness() {
        assertFalse(StartupDiagnostics.isAllCriticalReady())

        StartupDiagnostics.record(Subsystem.APPLICATION, SubsystemState.READY, "Core loaded")
        StartupDiagnostics.record(Subsystem.STORAGE, SubsystemState.READY, "Storage mounted")
        StartupDiagnostics.record(Subsystem.DATABASE, SubsystemState.READY, "Database opened")
        StartupDiagnostics.record(Subsystem.SECURITY, SubsystemState.READY, "Keystore ready")

        assertTrue(StartupDiagnostics.isAllCriticalReady())
        assertFalse(StartupDiagnostics.hasFailures())
    }

    @Test
    fun testGracefulDegradationDoesNotFailCriticalReadiness() {
        StartupDiagnostics.record(Subsystem.APPLICATION, SubsystemState.READY)
        StartupDiagnostics.record(Subsystem.STORAGE, SubsystemState.READY)
        StartupDiagnostics.record(Subsystem.DATABASE, SubsystemState.READY)
        StartupDiagnostics.record(Subsystem.SECURITY, SubsystemState.DEGRADED, "Fallback software keystore")

        // Degraded security still allows critical ready
        assertTrue(StartupDiagnostics.isAllCriticalReady())
        assertTrue(StartupDiagnostics.hasDegradations())
        assertFalse(StartupDiagnostics.hasFailures())

        // Non-critical subsystem failure does not break isAllCriticalReady
        StartupDiagnostics.record(Subsystem.MCP, SubsystemState.DEGRADED, "MCP offline")
        assertTrue(StartupDiagnostics.isAllCriticalReady())
    }

    @Test
    fun testSecretRedactionInDiagnosticErrors() {
        val fakeGhToken = "ghp_1234567890abcdef1234567890"
        val fakeApiKey = "sk-abcdefghijklmnopqrstuvwxyz123456"
        val error = RuntimeException("Connection failed for token $fakeGhToken and api key $fakeApiKey")

        StartupDiagnostics.record(Subsystem.GIT_GITHUB, SubsystemState.FAILED, "Auth error", error)

        val report = StartupDiagnostics.getReport(Subsystem.GIT_GITHUB)
        assertEquals(SubsystemState.FAILED, report.state)
        assertTrue(report.errorMessage != null)
        assertFalse(report.errorMessage!!.contains(fakeGhToken))
        assertFalse(report.errorMessage!!.contains(fakeApiKey))
        assertTrue(report.errorMessage!!.contains("[REDACTED_GH_TOKEN]"))
        assertTrue(report.errorMessage!!.contains("[REDACTED_API_KEY]"))
    }

    @Test
    fun testUncaughtExceptionInterception() {
        val secretError = IllegalStateException("Auth failure: Bearer ya29.secretToken123456789")
        StartupDiagnostics.recordUncaughtException(Thread.currentThread(), secretError)

        val crashRecords = StartupDiagnostics.getCrashRecords()
        assertTrue(crashRecords.isNotEmpty())
        val last = crashRecords.last()
        assertEquals(Thread.currentThread().name, last.threadName)
        assertEquals("java.lang.IllegalStateException", last.exceptionClass)
        assertFalse(last.message.contains("ya29.secretToken123456789"))
        assertTrue(last.message.contains("Bearer [REDACTED_TOKEN]"))
    }

    @Test
    fun testSummaryGenerationContainsAllSubsystems() {
        StartupDiagnostics.record(Subsystem.APPLICATION, SubsystemState.READY, "Core active")
        StartupDiagnostics.record(Subsystem.BROWSER_PREVIEW, SubsystemState.READY, "WebView ready")
        StartupDiagnostics.record(Subsystem.TERMINAL_LINUX, SubsystemState.READY, "Terminal active")

        val summary = StartupDiagnostics.generateSummary()
        assertTrue(summary.contains("Agent 63 System Health Summary"))
        assertTrue(summary.contains("Application Core: READY"))
        assertTrue(summary.contains("Browser & Live Preview: READY"))
        assertTrue(summary.contains("Terminal & Linux Runtime: READY"))
    }
}
