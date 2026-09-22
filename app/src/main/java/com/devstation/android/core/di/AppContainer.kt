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
import com.devstation.android.core.security.policy.RoomSecurityAuditStore
import com.devstation.android.core.security.policy.SecurityAuditLogger
import com.devstation.android.core.security.policy.SecurityDiagnostics
import com.devstation.android.core.security.policy.SecurityGrantLookup
import com.devstation.android.core.security.policy.SecurityManager
import com.devstation.android.core.security.policy.SecurityPolicyEngine
import com.devstation.android.core.security.policy.SecurityPolicyRepository
import com.devstation.android.core.agent.profiles.AgentProfileManager
import com.devstation.android.core.database.RoomAgentProfileConfigStore
import com.devstation.android.core.mcp.McpCapabilityRegistry
import com.devstation.android.core.mcp.McpServerManager
import com.devstation.android.core.database.RoomMcpConfigStore
import com.devstation.android.core.database.RoomSkillConfigStore
import com.devstation.android.core.skills.SkillExecutor
import com.devstation.android.core.skills.SkillManager
import com.devstation.android.core.skills.SkillValidator
import com.devstation.android.core.preview.BrowserConsoleManager
import com.devstation.android.core.preview.BrowserSecurityPolicy
import com.devstation.android.core.preview.PreviewPortManager
import com.devstation.android.core.preview.PreviewServerManager
import com.devstation.android.core.diagnostics.StartupDiagnostics
import com.devstation.android.core.diagnostics.Subsystem
import com.devstation.android.core.diagnostics.SubsystemState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID

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

    // Phase 7: permissions, sandbox + security hardening
    val securityManager: SecurityManager

    // Phase 8: MCP + Skills + Custom Agents
    val mcpServerManager: McpServerManager
    val mcpCapabilityRegistry: McpCapabilityRegistry
    val skillManager: SkillManager
    val agentProfileManager: AgentProfileManager

    // Phase 9: browser + live preview
    val previewServerManager: PreviewServerManager
    val previewPortManager: PreviewPortManager
    val browserSecurityPolicy: BrowserSecurityPolicy
    val browserConsoleManager: BrowserConsoleManager

    // Phase 10: Git + GitHub
    val gitManager: com.devstation.android.core.git.GitManager
    val gitHubApiClient: com.devstation.android.core.github.GitHubApiClient
    val gitHubAccountManager: com.devstation.android.core.github.GitHubAccountManager
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    override val dispatchers: DispatcherProvider by lazy {
        DefaultDispatcherProvider()
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        StartupDiagnostics.recordUncaughtException(Thread.currentThread(), throwable)
    }

    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + dispatchers.main + exceptionHandler
    )

    override val database: DevStationDatabase by lazy {
        try {
            val db = DevStationDatabase.getInstance(context)
            StartupDiagnostics.record(Subsystem.DATABASE, SubsystemState.READY, "Room database initialized")
            db
        } catch (t: Throwable) {
            StartupDiagnostics.record(Subsystem.DATABASE, SubsystemState.FAILED, "Room database open failed", t)
            throw t
        }
    }

    override val fileSystemManager: ProjectFileSystemManager by lazy {
        try {
            val fsm = ProjectFileSystemManager(context)
            StartupDiagnostics.record(Subsystem.STORAGE, SubsystemState.READY, "Workspace path ready: ${fsm.defaultWorkspaceDir.name}")
            fsm
        } catch (t: Throwable) {
            StartupDiagnostics.record(Subsystem.STORAGE, SubsystemState.DEGRADED, "Workspace initialization issue", t)
            ProjectFileSystemManager(context)
        }
    }

    override val storageStatsCalculator: StorageStatsCalculator by lazy {
        StorageStatsCalculator(context, fileSystemManager)
    }

    override val secureCredentialStore: SecureCredentialStore by lazy {
        try {
            val store = KeystoreCredentialStore(context)
            StartupDiagnostics.record(Subsystem.SECURITY, SubsystemState.READY, "Secure credential store active")
            store
        } catch (t: Throwable) {
            StartupDiagnostics.record(Subsystem.SECURITY, SubsystemState.DEGRADED, "Credential store warning", t)
            KeystoreCredentialStore(context)
        }
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

    override val terminalManager: com.devstation.android.future.terminal.TerminalManager by lazy {
        StartupDiagnostics.record(Subsystem.TERMINAL_LINUX, SubsystemState.READY, "Terminal manager ready")
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

    override val secureStore: SecureCredentialStore get() = secureCredentialStore

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

    // ---- Phase 7: centralized security ----

    /** Identity of this DevStation session. A new process is a new session (§60). */
    private val appSessionId: String by lazy { UUID.randomUUID().toString() }

    override val securityManager: SecurityManager by lazy {
        SecurityManager(
            repository = securityPolicyRepository,
            permissions = agentPermissionManager,
            audit = securityAuditLogger,
            diagnostics = securityDiagnostics,
            sessionId = appSessionId
        )
    }

    private val securityPolicyRepository: SecurityPolicyRepository by lazy {
        SecurityPolicyRepository(
            settingsDao = database.securitySettingsDao(),
            projectDao = database.projectSecuritySettingsDao(),
            grantDao = database.permissionGrantDao(),
            dispatchers = dispatchers
        )
    }

    private val securityAuditLogger: SecurityAuditLogger by lazy {
        SecurityAuditLogger(RoomSecurityAuditStore(database.securityEventDao(), dispatchers))
    }

    /** §2: the single decision point. Every agent tool call is evaluated here. */
    private val securityPolicyEngine: SecurityPolicyEngine by lazy {
        SecurityPolicyEngine(
            policyProvider = securityPolicyRepository,
            grants = SecurityGrantLookup { scope, toolName, taskId, sessionId, projectId ->
                agentPermissionManager.hasGrant(scope, toolName, taskId, sessionId, projectId)
            },
            audit = securityAuditLogger,
            projectSettings = { projectId ->
                projectId?.let { runCatching { securityPolicyRepository.projectSettings(it) }.getOrNull() }
            },
            linuxGuestAvailable = { linuxRuntimeManager.isInstalled() }
        )
    }

    private val securityDiagnostics: SecurityDiagnostics by lazy {
        SecurityDiagnostics(
            engine = securityPolicyEngine,
            policyProvider = securityPolicyRepository,
            audit = securityAuditLogger,
            processes = agentProcessRegistry,
            protectedPaths = listOf(
                "/data/data/",
                "/data/user/",
                "/data/misc/keystore"
            )
        )
    }

    private val agentPermissionManager: PermissionManager by lazy {
        PermissionManager(
            broker = approvalBroker,
            grantSink = { taskId, toolName -> agentPermissionStore.grant(taskId, toolName) },
            scopedGrantSink = { scope, scopeId, toolName ->
                when (scope) {
                    com.devstation.android.core.agent.PermissionScope.SESSION ->
                        securityPolicyRepository.grant(
                            scope = scope,
                            toolName = toolName,
                            sessionId = scopeId
                        )
                    com.devstation.android.core.agent.PermissionScope.PROJECT,
                    com.devstation.android.core.agent.PermissionScope.GLOBAL ->
                        securityPolicyRepository.grant(
                            scope = scope,
                            toolName = toolName,
                            projectId = scopeId
                        )
                    else -> Unit
                }
            }
        )
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
            allowAndroidFallback = { agentAllowAndroidShell.get() },
            audit = securityAuditLogger,
            mcpTools = {
                // Phase 8: connected MCP servers' tools become wrapped agent tools. They are
                // still evaluated by the SecurityPolicyEngine on every call — never a bypass.
                runCatching {
                    mcpCapabilityRegistry.allCapabilities()
                        .filter { it.enabled }
                        .map { capability ->
                            com.devstation.android.core.mcp.McpToolWrapper(
                                capability = capability,
                                serverManager = mcpServerManager,
                                serverName = mcpServerManager.servers.value[capability.serverId]?.name
                                    ?: capability.serverId
                            )
                        }
                }.getOrDefault(emptyList())
            },
            previewTools = {
                // Phase 9 §31: preview tools delegate to PreviewServerManager, which enforces
                // the terminal policy, sandbox, env sanitization and loopback-only binding.
                runCatching {
                    com.devstation.android.core.agent.tools.previewTools(
                        previewManager = previewServerManager,
                        projectName = { "" }
                    )
                }.getOrDefault(emptyList())
            },
            gitTools = {
                // Phase 10: Git and GitHub agent tools
                runCatching {
                    com.devstation.android.core.agent.tools.GitToolSet.createTools(
                        gitManager = gitManager,
                        accountManager = gitHubAccountManager,
                        apiClient = gitHubApiClient
                    )
                }.getOrDefault(emptyList())
            }
        )

        AgentRuntime(
            providerManager = aiProviderManager,
            aiSettingsRepository = aiSettingsRepository,
            projectLocator = projectLocator,
            conversationPort = conversationPort,
            toolFactory = toolFactory,
            permissionManager = agentPermissionManager,
            broker = approvalBroker,
            processRegistry = agentProcessRegistry,
            taskStore = agentTaskStore,
            eventStore = agentEventStore,
            historyStore = agentHistoryStore,
            permissionStore = agentPermissionStore,
            dispatchers = dispatchers,
            scope = appScope,
            securityEngine = securityPolicyEngine,
            audit = securityAuditLogger,
            sessionId = appSessionId
        )
    }

    private val approvalBroker: ApprovalBroker by lazy { ApprovalBroker() }

    // ---- Phase 8: MCP + Skills + Custom Agents ----

    override val mcpCapabilityRegistry: McpCapabilityRegistry by lazy { McpCapabilityRegistry() }

    override val mcpServerManager: McpServerManager by lazy {
        McpServerManager(
            transportFactory = { type ->
                // Phase 8.1: real transports, each gated by the Phase 7 security layers.
                when (type) {
                    com.devstation.android.core.mcp.McpTransportType.STDIO ->
                        com.devstation.android.core.mcp.McpStdioTransport(
                            workingDir = mcpWorkingDir(),
                            terminalPolicy = com.devstation.android.core.security.policy.TerminalSecurityPolicy()
                        )
                    com.devstation.android.core.mcp.McpTransportType.HTTP ->
                        com.devstation.android.core.mcp.McpHttpTransport(
                            networkPolicy = com.devstation.android.core.security.policy.NetworkSecurityPolicy()
                        )
                }
            },
            capabilityRegistry = mcpCapabilityRegistry,
            audit = securityAuditLogger,
            dispatchers = dispatchers,
            scope = appScope,
            configStore = RoomMcpConfigStore(database.mcpServerDao(), database.mcpCapabilityDao(), dispatchers)
        )
    }

    override val skillManager: SkillManager by lazy {
        SkillManager(
            validator = SkillValidator(),
            skillExecutor = SkillExecutor(agentRuntime),
            audit = securityAuditLogger,
            dispatchers = dispatchers,
            configStore = RoomSkillConfigStore(database.skillDao(), dispatchers)
        )
    }

    override val agentProfileManager: AgentProfileManager by lazy {
        AgentProfileManager(
            audit = securityAuditLogger,
            dispatchers = dispatchers,
            configStore = RoomAgentProfileConfigStore(database.agentProfileDao(), dispatchers)
        )
    }

    // ---- Phase 9: browser + live preview ----

    override val previewPortManager: PreviewPortManager by lazy { PreviewPortManager() }

    override val browserSecurityPolicy: BrowserSecurityPolicy by lazy { BrowserSecurityPolicy() }

    override val browserConsoleManager: BrowserConsoleManager by lazy { BrowserConsoleManager() }

    override val previewServerManager: PreviewServerManager by lazy {
        PreviewServerManager(
            portManager = previewPortManager,
            audit = securityAuditLogger,
            dispatchers = dispatchers,
            scope = appScope
        )
    }

    // ---- Phase 10: Git + GitHub ----

    override val gitManager: com.devstation.android.core.git.GitManager by lazy {
        com.devstation.android.core.git.DefaultGitManager(
            commandRunner = com.devstation.android.core.git.DefaultGitCommandRunner(
                linuxRuntimeManager = linuxRuntimeManager,
                linuxLauncher = linuxRuntimeManager.launcher,
                auditLogger = securityAuditLogger
            )
        )
    }

    override val gitHubApiClient: com.devstation.android.core.github.GitHubApiClient by lazy {
        com.devstation.android.core.github.DefaultGitHubApiClient()
    }

    override val gitHubAccountManager: com.devstation.android.core.github.GitHubAccountManager by lazy {
        com.devstation.android.core.github.DefaultGitHubAccountManager(
            accountDao = database.gitHubAccountDao(),
            credentialStore = secureCredentialStore,
            apiClient = gitHubApiClient
        )
    }

    /** Phase 8.1 §7: the single app-controlled directory MCP STDIO servers may run in. */
    private fun mcpWorkingDir(): java.io.File {
        val dir = java.io.File(context.filesDir, "mcp")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Phase 8 startup: load MCP servers, skills and agent profiles from Room. */
    fun initializePhase8() {
        appScope.launch {
            StartupDiagnostics.record(Subsystem.MCP, SubsystemState.INITIALIZING, "Loading MCP servers")
            runCatching {
                mcpServerManager.loadServers()
                StartupDiagnostics.record(Subsystem.MCP, SubsystemState.READY, "MCP servers initialized")
            }.onFailure { err ->
                StartupDiagnostics.record(Subsystem.MCP, SubsystemState.DEGRADED, "MCP fallback active", err)
            }
        }
        appScope.launch {
            StartupDiagnostics.record(Subsystem.SKILLS, SubsystemState.INITIALIZING, "Loading skills and profiles")
            runCatching {
                skillManager.loadSkills()
                agentProfileManager.loadProfiles()
                StartupDiagnostics.record(Subsystem.SKILLS, SubsystemState.READY, "Skills and profiles ready")
            }.onFailure { err ->
                StartupDiagnostics.record(Subsystem.SKILLS, SubsystemState.DEGRADED, "Skills fallback active", err)
            }
        }
    }

    /**
     * Called from [DevStationApp.onCreate] to register built-in provider adapters
     * from persisted configuration before any UI can issue AI requests.
     */
    fun initializeAiProviders() {
        appScope.launch {
            StartupDiagnostics.record(Subsystem.AI, SubsystemState.INITIALIZING, "Refreshing AI provider configuration")
            runCatching {
                aiProviderManager.refreshFromConfig()
                StartupDiagnostics.record(Subsystem.AI, SubsystemState.READY, "AI providers ready")
            }.onFailure { err ->
                StartupDiagnostics.record(Subsystem.AI, SubsystemState.DEGRADED, "AI running in offline fallback mode", err)
            }
        }
    }

    /**
     * Phase 6 startup work: recover tasks that were interrupted by an app restart (mark them
     * INTERRUPTED, drop task-scoped permissions) and keep the agent tool policy in sync with
     * persisted AI settings. Phase 6 never auto-resumes a task.
     */
    fun initializeAgent() {
        appScope.launch {
            runCatching {
                agentRuntime.recoverInterruptedTasks()
            }.onFailure { err ->
                StartupDiagnostics.record(Subsystem.APPLICATION, SubsystemState.DEGRADED, "Interrupted task recovery warning", err)
            }
        }
        appScope.launch {
            runCatching {
                aiSettingsRepository.observe()
                    .catch { err ->
                        StartupDiagnostics.record(Subsystem.AI, SubsystemState.DEGRADED, "AI settings stream warning", err)
                    }
                    .collect { settings ->
                        agentAllowAndroidShell.set(settings.agentAllowAndroidShell)
                    }
            }.onFailure { err ->
                StartupDiagnostics.record(Subsystem.AI, SubsystemState.DEGRADED, "AI settings listener failed", err)
            }
        }
    }

    /**
     * Phase 7 startup work: end grants from previous sessions, drop expired grants, restore explicit
     * project grants and apply audit retention. Never widens a permission.
     */
    fun initializeSecurity() {
        appScope.launch {
            StartupDiagnostics.record(Subsystem.SECURITY, SubsystemState.INITIALIZING, "Initializing session security store")
            runCatching {
                securityManager.initializeSessionStore()
                StartupDiagnostics.record(Subsystem.SECURITY, SubsystemState.READY, "Security session store ready")
            }.onFailure { err ->
                StartupDiagnostics.record(Subsystem.SECURITY, SubsystemState.DEGRADED, "Security session fallback active", err)
            }
        }
    }
}
