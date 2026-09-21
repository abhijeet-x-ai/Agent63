package com.devstation.android.core.di

import android.content.Context
import com.devstation.android.core.ai.AiCredentialManager
import com.devstation.android.core.ai.AIHttpClient
import com.devstation.android.core.ai.ChatOrchestrator
import com.devstation.android.core.ai.DefaultAIProviderManager
import com.devstation.android.core.common.DefaultDispatcherProvider
import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.database.DevStationDatabase
import com.devstation.android.core.filesystem.ProjectFileSystemManager
import com.devstation.android.core.filesystem.StorageStatsCalculator
import com.devstation.android.core.repository.AIModelCacheRepository
import com.devstation.android.core.repository.AIProviderConfigRepository
import com.devstation.android.core.repository.AISettingsRepository
import com.devstation.android.core.repository.AIUsageRepository
import com.devstation.android.core.repository.ConversationRepository
import com.devstation.android.core.repository.ProjectRepository
import com.devstation.android.core.repository.SettingsRepository
import com.devstation.android.core.security.KeystoreCredentialStore
import com.devstation.android.core.security.SecureCredentialStore
import kotlinx.coroutines.launch

interface AppContainer {
    val dispatchers: DispatcherProvider
    val database: DevStationDatabase
    val fileSystemManager: ProjectFileSystemManager
    val storageStatsCalculator: StorageStatsCalculator
    val secureCredentialStore: SecureCredentialStore
    val projectRepository: ProjectRepository
    val conversationRepository: ConversationRepository
    val settingsRepository: SettingsRepository
    val terminalManager: com.devstation.android.future.terminal.TerminalManager
    val linuxRuntimeManager: com.devstation.android.future.runtime.LinuxRuntimeManager

    // Phase 5: AI provider system
    val secureStore: SecureCredentialStore
    val aiCredentialManager: AiCredentialManager
    val aiProviderManager: DefaultAIProviderManager
    val aiSettingsRepository: AISettingsRepository
    val aiProviderConfigRepository: AIProviderConfigRepository
    val aiModelCacheRepository: AIModelCacheRepository
    val aiUsageRepository: AIUsageRepository
    val chatOrchestrator: ChatOrchestrator
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

    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + dispatchers.main
    )

    override val terminalManager: com.devstation.android.future.terminal.TerminalManager by lazy {
        com.devstation.android.future.terminal.TerminalManager(dispatchers, appScope)
    }

    override val linuxRuntimeManager: com.devstation.android.future.runtime.LinuxRuntimeManager by lazy {
        com.devstation.android.future.runtime.LinuxRuntimeManager(
            context = context,
            fileSystemManager = fileSystemManager,
            dispatchers = dispatchers,
            scope = appScope
        )
    }

    // ---- Phase 5: AI provider system (no filesystem/terminal/runtime access) ----

    override val secureStore: SecureCredentialStore by lazy { KeystoreCredentialStore(context) }

    override val aiCredentialManager: AiCredentialManager by lazy {
        AiCredentialManager(secureStore)
    }

    override val aiProviderConfigRepository: AIProviderConfigRepository by lazy {
        AIProviderConfigRepository(database.aiProviderConfigDao(), dispatchers)
    }

    override val aiModelCacheRepository: AIModelCacheRepository by lazy {
        AIModelCacheRepository(database.aiModelCacheDao(), dispatchers)
    }

    override val aiSettingsRepository: AISettingsRepository by lazy {
        AISettingsRepository(database.aiSettingsDao(), dispatchers)
    }

    override val aiUsageRepository: AIUsageRepository by lazy {
        AIUsageRepository(database.aiUsageRecordDao(), dispatchers)
    }

    override val aiProviderManager: DefaultAIProviderManager by lazy {
        DefaultAIProviderManager(
            httpClient = AIHttpClient(),
            credentials = aiCredentialManager,
            configRepository = aiProviderConfigRepository,
            modelCacheRepository = aiModelCacheRepository,
            dispatchers = dispatchers
        )
    }

    override val chatOrchestrator: ChatOrchestrator by lazy {
        ChatOrchestrator(
            providerManager = aiProviderManager,
            conversationRepository = conversationRepository,
            aiSettingsRepository = aiSettingsRepository,
            usageRepository = aiUsageRepository,
            dispatchers = dispatchers
        )
    }

    /**
     * Called from [DevStationApp.onCreate] to register built-in provider adapters
     * from persisted configuration before any UI can issue AI requests.
     */
    fun initializeAiProviders() {
        appScope.launch {
            aiProviderManager.refreshFromConfig()
        }
    }
}
