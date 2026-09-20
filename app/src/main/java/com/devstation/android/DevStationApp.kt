package com.devstation.android

import android.app.Application
import com.devstation.android.core.di.AppContainer
import com.devstation.android.core.di.DefaultAppContainer

class DevStationApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        // Ensure local workspace directory exists on startup
        container.fileSystemManager.defaultWorkspaceDir
    }
}
