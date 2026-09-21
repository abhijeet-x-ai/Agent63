package com.devstation.android

import android.app.Application
import com.devstation.android.core.di.AppContainer
import com.devstation.android.core.di.DefaultAppContainer

class DevStationApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val appContainer = DefaultAppContainer(this)
        container = appContainer
        // Ensure local workspace directory exists on startup
        appContainer.fileSystemManager.defaultWorkspaceDir
        // Phase 5: register AI provider adapters from persisted configuration
        appContainer.initializeAiProviders()
        // Phase 6: recover interrupted agent tasks and sync the agent tool policy
        appContainer.initializeAgent()
    }
}
