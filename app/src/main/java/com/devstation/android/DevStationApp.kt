package com.devstation.android

import android.app.Application
import android.util.Log
import com.devstation.android.core.diagnostics.StartupDiagnostics
import com.devstation.android.core.diagnostics.Subsystem
import com.devstation.android.core.diagnostics.SubsystemState
import com.devstation.android.core.di.AppContainer
import com.devstation.android.core.di.DefaultAppContainer

class DevStationApp : Application() {
    @Volatile
    var container: AppContainer? = null
        private set

    val safeContainer: AppContainer
        get() = container ?: synchronized(this) {
            container ?: DefaultAppContainer(this).also { container = it }
        }

    override fun onCreate() {
        super.onCreate()

        // Install process-wide crash interceptor for diagnostics and prevent background crashes from terminating UI
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                StartupDiagnostics.recordUncaughtException(thread, throwable)
                val crashFile = java.io.File(filesDir, "last_crash.txt")
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                val crashDetails = "Time: ${System.currentTimeMillis()}\nThread: ${thread.name}\n${sw}\n"
                crashFile.writeText(crashDetails)

                if (thread.name == "main") {
                    // Check crash throttle to prevent restart loops
                    val prefs = getSharedPreferences("agent63_crash_guard", MODE_PRIVATE)
                    val lastCrashTime = prefs.getLong("last_crash_time", 0L)
                    val now = System.currentTimeMillis()
                    prefs.edit().putLong("last_crash_time", now).commit()

                    if (now - lastCrashTime > 3000) {
                        // Launch in-app recovery screen so the user doesn't see "keeps stopping"
                        val intent = android.content.Intent(this, MainActivity::class.java).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                            putExtra("CRASH_ERROR", crashDetails)
                        }
                        startActivity(intent)
                        android.os.Process.killProcess(android.os.Process.myPid())
                        System.exit(10)
                        return@setDefaultUncaughtExceptionHandler
                    }
                }
            } catch (_: Throwable) {}

            if (thread.name == "main") {
                defaultHandler?.uncaughtException(thread, throwable)
            } else {
                Log.e("Agent63", "Non-main thread exception intercepted: ${thread.name}", throwable)
            }
        }

        StartupDiagnostics.record(Subsystem.APPLICATION, SubsystemState.INITIALIZING, "Starting Agent 63 core")

        runCatching {
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
        }.onFailure { fatalError ->
            StartupDiagnostics.record(Subsystem.APPLICATION, SubsystemState.FAILED, "Fatal startup failure", fatalError)
            Log.e("Agent63", "Agent 63 container initialization failed", fatalError)
        }
    }
}
