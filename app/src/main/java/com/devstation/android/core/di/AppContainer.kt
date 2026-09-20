package com.devstation.android.core.di

import android.content.Context
import com.devstation.android.core.common.DefaultDispatcherProvider
import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.database.DevStationDatabase
import com.devstation.android.core.filesystem.ProjectFileSystemManager
import com.devstation.android.core.filesystem.StorageStatsCalculator
import com.devstation.android.core.repository.ConversationRepository
import com.devstation.android.core.repository.ProjectRepository
import com.devstation.android.core.repository.SettingsRepository
import com.devstation.android.core.security.KeystoreCredentialStore
import com.devstation.android.core.security.SecureCredentialStore

interface AppContainer {
    val dispatchers: DispatcherProvider
    val database: DevStationDatabase
    val fileSystemManager: ProjectFileSystemManager
    val storageStatsCalculator: StorageStatsCalculator
    val secureCredentialStore: SecureCredentialStore
    val projectRepository: ProjectRepository
    val conversationRepository: ConversationRepository
    val settingsRepository: SettingsRepository
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    override val dispatchers: DispatcherProvider by lazy {
        DefaultDispatcherProvider()
    }

    override val database: DevStationDatabase by lazy {
        DevStationDatabase.getInstance(context)
    }

    override val fileSystemManager: ProjectFileSystemManager by lazy {
        ProjectFileSystemManager(context)
    }

    override val storageStatsCalculator: StorageStatsCalculator by lazy {
        StorageStatsCalculator(context, fileSystemManager)
    }

    override val secureCredentialStore: SecureCredentialStore by lazy {
        KeystoreCredentialStore(context)
    }

    override val projectRepository: ProjectRepository by lazy {
        ProjectRepository(
            projectDao = database.projectDao(),
            fileSystemManager = fileSystemManager,
            dispatchers = dispatchers
        )
    }

    override val conversationRepository: ConversationRepository by lazy {
        ConversationRepository(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            dispatchers = dispatchers
        )
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(
            appSettingsDao = database.appSettingsDao(),
            dispatchers = dispatchers
        )
    }
}
