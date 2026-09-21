package com.devstation.android.core.di

import android.content.Context
import com.devstation.android.core.ai.AiCredentialManager
import com.devstation.android.core.ai.AIHttpClient
import com.devstation.android.core.ai.ChatOrchestrator
import com.devstation.android.core.ai.DefaultAIProviderManager
import com.devstation.android.core.agent.AgentConversationPort
import com.devstation.android.core.agent.AgentPermissionStore
import com.devstation.android.core.agent.AgentRuntime
import com.devstation.android.core.agent.AgentTaskHistoryRepository
import com.devstation.android.core.agent.ApprovalBroker
import com.devstation.android.core.agent.PermissionManager
import com.devstation.android.core.agent.ProjectLocator
import com.devstation.android.core.agent.RoomAgentEventStore
import com.devstation.android.core.agent.RoomAgentHistoryStore
import com.devstation.android.core.agent.RoomAgentPermissionStore
import com.devstation.android.core.agent.RoomAgentTaskStore
import com.devstation.android.core.agent.tools.AgentProcessRegistry
import com.devstation.android.core.agent.tools.AndroidShellCommandRunner
import com.devstation.android.core.agent.tools.DefaultAgentToolFactory
import com.devstation.android.core.agent.tools.EditorBridge
import com.devstation.android.core.agent.tools.EditorBridgeImpl
import com.devstation.android.core.agent.tools.LinuxCommandRunner
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

    // Phase 6: AI agent + tool execution system
    val editorBridge: EditorBridge
    val agentRuntime: AgentRuntime
    val agentTaskHistoryRepository: AgentTaskHistoryRepository
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

    // ---- Phase 6: AI agent + tool execution system ----

    override val editorBridge: EditorBridge by lazy { EditorBridgeImpl() }

    private val agentProcessRegistry: AgentProcessRegistry by lazy { AgentProcessRegistry() }

    /** Refreshed from persisted AI settings so the tool layer never reads the DB off-thread. */
    private val agentAllowAndroidShell = java.util.concurrent.atomic.AtomicBoolean(true)

    private val agentTaskStore: RoomAgentTaskStore by lazy {
        RoomAgentTaskStore(database.agentTaskDao(), dispatchers)
    }

    private val agentEventStore: RoomAgentEventStore by lazy {
        RoomAgentEventStore(database.agentEventDao(), dispatchers)
    }

    private val agentHistoryStore: RoomAgentHistoryStore by lazy {
        RoomAgentHistoryStore(database.agentActionHistoryDao(), dispatchers)
    }

    private val agentPermissionStore: AgentPermissionStore by lazy {
        RoomAgentPermissionStore(database.agentTaskPermissionDao(), dispatchers)
    }

    override val agentTaskHistoryRepository: AgentTaskHistoryRepository by lazy {
        AgentTaskHistoryRepository(
            database.agentTaskDao(),
            database.agentEventDao(),
            database.agentActionHistoryDao(),
            dispatchers
        )
    }

    override val agentRuntime: AgentRuntime by lazy {
        val projectLocator = ProjectLocator { projectId -> projectRepository.getProjectById(projectId) }
        val conversationPort = object : AgentConversationPort {
            override suspend fun history(conversationId: String): List<com.devstation.android.core.ai.AIMessage> =
                conversationRepository.getMessagesOnce(conversationId)
                    .filter { it.role != com.devstation.android.core.model.MessageRole.SYSTEM }
                    .map {
                        com.devstation.android.core.ai.AIMessage(
                            role = com.devstation.android.core.ai.AIMessageRole.valueOf(it.role.name),
                            content = it.content,
                            timestamp = it.createdAt,
                            id = it.id
                        )
                    }

            override suspend fun providerSelection(conversationId: String): Pair<String?, String?> {
                val conversation = conversationRepository.getConversationById(conversationId)
                return conversation?.providerId to conversation?.modelId
            }

            override suspend fun persistAssistant(conversationId: String, content: String) {
                conversationRepository.upsertMessage(
                    conversationId = conversationId,
                    messageId = java.util.UUID.randomUUID().toString(),
                    role = com.devstation.android.core.model.MessageRole.ASSISTANT,
                    content = content
                )
            }
        }

        val toolFactory = DefaultAgentToolFactory(
            editorBridge = editorBridge,
            processRegistry = agentProcessRegistry,
            linuxRunner = LinuxCommandRunner(
                launcher = linuxRuntimeManager.launcher,
                storagePaths = linuxRuntimeManager.storagePaths,
                registry = agentProcessRegistry
            ),
            androidRunner = AndroidShellCommandRunner(agentProcessRegistry),
            linuxAvailable = { linuxRuntimeManager.isInstalled() },
            allowAndroidFallback = { agentAllowAndroidShell.get() }
        )

        AgentRuntime(
            providerManager = aiProviderManager,
            aiSettingsRepository = aiSettingsRepository,
            projectLocator = projectLocator,
            conversationPort = conversationPort,
            toolFactory = toolFactory,
            permissionManager = PermissionManager(
                broker = approvalBroker,
                grantSink = { taskId, toolName -> agentPermissionStore.grant(taskId, toolName) }
            ),
            broker = approvalBroker,
            processRegistry = agentProcessRegistry,
            taskStore = agentTaskStore,
            eventStore = agentEventStore,
            historyStore = agentHistoryStore,
            permissionStore = agentPermissionStore,
            dispatchers = dispatchers,
            scope = appScope
        )
    }

    private val approvalBroker: ApprovalBroker by lazy { ApprovalBroker() }

    /**
     * Called from [DevStationApp.onCreate] to register built-in provider adapters
     * from persisted configuration before any UI can issue AI requests.
     */
    fun initializeAiProviders() {
        appScope.launch {
            aiProviderManager.refreshFromConfig()
        }
    }

    /**
     * Phase 6 startup work: recover tasks that were interrupted by an app restart (mark them
     * INTERRUPTED, drop task-scoped permissions) and keep the agent tool policy in sync with
     * persisted AI settings. Phase 6 never auto-resumes a task.
     */
    fun initializeAgent() {
        appScope.launch {
            runCatching { agentRuntime.recoverInterruptedTasks() }
        }
        appScope.launch {
            aiSettingsRepository.observe().collect { settings ->
                agentAllowAndroidShell.set(settings.agentAllowAndroidShell)
            }
        }
    }
}
