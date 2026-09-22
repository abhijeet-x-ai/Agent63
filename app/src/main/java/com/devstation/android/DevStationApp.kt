package com.devstation.android

import android.app.Application
import android.util.Log
import com.devstation.android.core.diagnostics.StartupDiagnostics
import com.devstation.android.core.diagnostics.Subsystem
import com.devstation.android.core.diagnostics.SubsystemState
import com.devstation.android.core.di.AppContainer
import com.devstation.android.core.di.DefaultAppContainer

class DevStationApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // Install process-wide crash interceptor for diagnostics
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                StartupDiagnostics.recordUncaughtException(thread, throwable)
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        StartupDiagnostics.record(Subsystem.APPLICATION, SubsystemState.INITIALIZING, "Starting Agent 63 core")

        val appContainer = DefaultAppContainer(this)
        container = appContainer

        // Ensure local workspace directory exists on startup without crashing if filesystem is restricted
        runCatching {
            appContainer.fileSystemManager.defaultWorkspaceDir
        }.onFailure { err ->
            StartupDiagnostics.record(Subsystem.STORAGE, SubsystemState.DEGRADED, "Workspace dir warning", err)
        }

        // Phase 5: register AI provider adapters from persisted configuration
        runCatching { appContainer.initializeAiProviders() }

        // Phase 6: recover interrupted agent tasks and sync the agent tool policy
        runCatching { appContainer.initializeAgent() }

        // Phase 7: end previous-session grants, restore project grants, apply audit retention
        runCatching { appContainer.initializeSecurity() }

        // Phase 8: load MCP servers, skills and custom agent profiles
        runCatching { appContainer.initializePhase8() }

        StartupDiagnostics.record(Subsystem.APPLICATION, SubsystemState.READY, "Agent 63 application core ready")
        Log.i("Agent63", "Agent 63 initialized successfully. API level: ${android.os.Build.VERSION.SDK_INT}")
    }
}
