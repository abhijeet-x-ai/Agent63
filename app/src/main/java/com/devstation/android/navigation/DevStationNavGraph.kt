package com.devstation.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.devstation.android.core.di.AppContainer
import com.devstation.android.feature.conversations.ConversationDetailScreen
import com.devstation.android.feature.conversations.ConversationDetailViewModel
import com.devstation.android.feature.conversations.ConversationsScreen
import com.devstation.android.feature.conversations.ConversationsViewModel
import com.devstation.android.feature.files.FilesScreen
import com.devstation.android.feature.files.FilesViewModel
import com.devstation.android.feature.home.HomeScreen
import com.devstation.android.feature.home.HomeViewModel
import com.devstation.android.feature.projects.ProjectSettingsScreen
import com.devstation.android.feature.projects.ProjectsScreen
import com.devstation.android.feature.projects.ProjectsViewModel
import com.devstation.android.feature.settings.SettingsScreen
import com.devstation.android.feature.settings.SettingsViewModel
import com.devstation.android.feature.storage.StorageScreen
import com.devstation.android.feature.storage.StorageViewModel

@Composable
fun DevStationNavGraph(
    navController: NavHostController,
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        composable(Screen.Home.route) {
            val viewModel: HomeViewModel = viewModel(
                factory = HomeViewModel.provideFactory(
                    projectRepository = container.projectRepository,
                    conversationRepository = container.conversationRepository
                )
            )
            HomeScreen(
                viewModel = viewModel,
                onNavigateToProjectFiles = { path, name ->
                    navController.navigate(Screen.Files.createRoute(path, name))
                },
                onNavigateToConversation = { convId ->
                    navController.navigate(Screen.ConversationDetail.createRoute(convId))
                },
                onNavigateToProjects = {
                    navController.navigate(Screen.Projects.route)
                },
                onNavigateToTerminal = { path ->
                    navController.navigate(Screen.Terminal.createRoute(path))
                }
            )
        }

        composable(Screen.Projects.route) {
            val viewModel: ProjectsViewModel = viewModel(
                factory = ProjectsViewModel.provideFactory(
                    projectRepository = container.projectRepository,
                    fileSystemManager = container.fileSystemManager
                )
            )
            ProjectsScreen(
                viewModel = viewModel,
                onOpenProjectFiles = { path: String, name: String ->
                    navController.navigate(Screen.Files.createRoute(path, name))
                },
                onOpenProjectSettings = { projectId: String ->
                    navController.navigate(Screen.ProjectSettings.createRoute(projectId))
                },
                onOpenTerminal = { path: String ->
                    navController.navigate(Screen.Terminal.createRoute(path))
                },
                onOpenEditor = { path: String ->
                    navController.navigate(Screen.Editor.createRoute(projectPath = path))
                }
            )
        }

        composable(
            route = Screen.Files.route,
            arguments = listOf(
                navArgument("projectPath") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("projectName") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val rawPath = backStackEntry.arguments?.getString("projectPath") ?: ""
            val rawName = backStackEntry.arguments?.getString("projectName") ?: ""
            val projectPath = if (rawPath.isNotBlank()) java.net.URLDecoder.decode(rawPath, "UTF-8") else ""
            val projectName = if (rawName.isNotBlank()) java.net.URLDecoder.decode(rawName, "UTF-8") else ""

            val viewModel: FilesViewModel = viewModel(
                key = projectPath,
                factory = FilesViewModel.provideFactory(
                    fileSystemManager = container.fileSystemManager,
                    initialPath = projectPath,
                    initialName = projectName
                )
            )
            FilesScreen(
                viewModel = viewModel,
                onOpenFileInEditor = { filePath ->
                    navController.navigate(Screen.Editor.createRoute(projectPath = projectPath, filePath = filePath))
                },
                onOpenTerminal = { termPath ->
                    navController.navigate(Screen.Terminal.createRoute(termPath))
                }
            )
        }

        composable(
            route = Screen.Editor.route,
            arguments = listOf(
                navArgument("projectPath") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("filePath") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("line") {
                    type = NavType.IntType
                    defaultValue = -1
                }
            )
        ) { backStackEntry ->
            val rawProjectPath = backStackEntry.arguments?.getString("projectPath") ?: ""
            val rawFilePath = backStackEntry.arguments?.getString("filePath") ?: ""
            val lineArg = backStackEntry.arguments?.getInt("line") ?: -1
            val projectPath = if (rawProjectPath.isNotBlank()) java.net.URLDecoder.decode(rawProjectPath, "UTF-8") else ""
            val filePath = if (rawFilePath.isNotBlank()) java.net.URLDecoder.decode(rawFilePath, "UTF-8") else ""
            val targetLine = if (lineArg > 0) lineArg else null

            val projectDir = if (projectPath.isNotBlank()) java.io.File(projectPath) else container.fileSystemManager.defaultWorkspaceDir

            val viewModel: com.devstation.android.feature.editor.EditorViewModel = viewModel(
                key = "editor_${projectDir.absolutePath}",
                factory = com.devstation.android.feature.editor.EditorViewModel.provideFactory(
                    projectRootDir = projectDir,
                    recentFileDao = container.database.recentFileDao(),
                    editorSettingsDao = container.database.editorSettingsDao(),
                    initialFilePath = filePath,
                    initialLine = targetLine
                )
            )

            // Phase 6: report the real editor state so the agent's editor tools answer truthfully.
            val editorState = viewModel.uiState.collectAsState().value
            LaunchedEffect(editorState.activeTabId, editorState.tabs) {
                container.editorBridge.reportState(
                    currentFilePath = editorState.activeTab?.filePath?.let { absolute ->
                        runCatching { java.io.File(absolute).relativeTo(projectDir).path }.getOrDefault(absolute)
                    },
                    openFiles = editorState.tabs.map { tab ->
                        runCatching { java.io.File(tab.filePath).relativeTo(projectDir).path }
                            .getOrDefault(tab.fileName)
                    }
                )
            }

            com.devstation.android.feature.editor.ui.EditorScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTerminal = { termPath ->
                    navController.navigate(Screen.Terminal.createRoute(termPath))
                },
                onNavigateToFiles = { pPath, pName ->
                    navController.navigate(Screen.Files.createRoute(pPath, pName))
                }
            )
        }

        composable(
            route = Screen.Terminal.route,
            arguments = listOf(
                navArgument("projectPath") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val rawPath = backStackEntry.arguments?.getString("projectPath") ?: ""
            val projectPath = if (rawPath.isNotBlank()) java.net.URLDecoder.decode(rawPath, "UTF-8") else ""

            val viewModel: com.devstation.android.feature.terminal.TerminalViewModel = viewModel(
                key = "terminal_$projectPath",
                factory = com.devstation.android.feature.terminal.TerminalViewModel.provideFactory(
                    terminalManager = container.terminalManager,
                    fileSystemManager = container.fileSystemManager,
                    linuxRuntimeManager = container.linuxRuntimeManager,
                    initialProjectPath = projectPath
                )
            )
            com.devstation.android.feature.terminal.TerminalScreen(
                viewModel = viewModel,
                onNavigateToLinuxRuntime = { navController.navigate(Screen.LinuxRuntime.route) }
            )
        }

        composable(Screen.LinuxRuntime.route) {
            val viewModel: com.devstation.android.feature.runtime.LinuxRuntimeViewModel = viewModel(
                factory = com.devstation.android.feature.runtime.LinuxRuntimeViewModel.Factory(
                    runtimeManager = container.linuxRuntimeManager
                )
            )
            com.devstation.android.feature.runtime.LinuxRuntimeScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onStartLinuxTerminal = {
                    navController.navigate(Screen.Terminal.createRoute())
                }
            )
        }

        composable(Screen.Conversations.route) {
            val viewModel: ConversationsViewModel = viewModel(
                factory = ConversationsViewModel.provideFactory(
                    conversationRepository = container.conversationRepository,
                    projectRepository = container.projectRepository
                )
            )
            ConversationsScreen(
                viewModel = viewModel,
                onOpenConversation = { convId ->
                    navController.navigate(Screen.ConversationDetail.createRoute(convId))
                }
            )
        }

        composable(
            route = Screen.ConversationDetail.route,
            arguments = listOf(
                navArgument("conversationId") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: ""
            val viewModel: com.devstation.android.feature.ai.AiChatViewModel = viewModel(
                key = conversationId,
                factory = com.devstation.android.feature.ai.AiChatViewModel.provideFactory(
                    conversationId = conversationId,
                    conversationRepository = container.conversationRepository,
                    orchestrator = container.chatOrchestrator,
                    providerManager = container.aiProviderManager,
                    aiSettingsRepository = container.aiSettingsRepository
                )
            )
            // Phase 6: the same screen hosts Agent mode, backed by the app-scoped AgentRuntime.
            val agentViewModel: com.devstation.android.feature.agent.AgentViewModel = viewModel(
                key = "agent_$conversationId",
                factory = com.devstation.android.feature.agent.AgentViewModel.provideFactory(
                    runtime = container.agentRuntime,
                    conversationRepository = container.conversationRepository,
                    aiSettingsRepository = container.aiSettingsRepository,
                    conversationId = conversationId,
                    editorBridge = container.editorBridge
                )
            )
            com.devstation.android.feature.ai.AiChatScreen(
                viewModel = viewModel,
                agentViewModel = agentViewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenFile = { request ->
                    navController.navigate(
                        Screen.Editor.createRoute(
                            projectPath = request.projectRoot,
                            filePath = request.filePath
                        )
                    )
                },
                onOpenAgentTasks = { navController.navigate(Screen.AgentTasks.route) }
            )
        }

        // ---- Phase 6: agent task history ----

        composable(Screen.AgentTasks.route) {
            val viewModel: com.devstation.android.feature.agent.AgentTasksViewModel = viewModel(
                factory = com.devstation.android.feature.agent.AgentTasksViewModel.provideFactory(
                    repository = container.agentTaskHistoryRepository
                )
            )
            com.devstation.android.feature.agent.AgentTasksScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenTask = { taskId ->
                    navController.navigate(Screen.AgentTaskDetail.createRoute(taskId))
                }
            )
        }

        composable(
            route = Screen.AgentTaskDetail.route,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType })
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId") ?: ""
            val viewModel: com.devstation.android.feature.agent.AgentTaskDetailViewModel = viewModel(
                key = "agent_task_$taskId",
                factory = com.devstation.android.feature.agent.AgentTaskDetailViewModel.provideFactory(
                    repository = container.agentTaskHistoryRepository,
                    taskId = taskId
                )
            )
            com.devstation.android.feature.agent.AgentTaskDetailScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // ---- Phase 5: AI provider system ----

        composable(Screen.AiProviders.route) {
            val viewModel: com.devstation.android.feature.ai.AiProvidersViewModel = viewModel(
                factory = com.devstation.android.feature.ai.AiProvidersViewModel.provideFactory(
                    providerManager = container.aiProviderManager,
                    configRepository = container.aiProviderConfigRepository,
                    modelCacheRepository = container.aiModelCacheRepository,
                    aiSettingsRepository = container.aiSettingsRepository,
                    dispatchers = container.dispatchers
                )
            )
            com.devstation.android.feature.ai.AiProvidersScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenProvider = { providerId ->
                    navController.navigate(Screen.AiProviderDetail.createRoute(providerId))
                }
            )
        }

        composable(
            route = Screen.AiProviderDetail.route,
            arguments = listOf(
                navArgument("providerId") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val providerId = backStackEntry.arguments?.getString("providerId") ?: ""
            val viewModel: com.devstation.android.feature.ai.AiProviderDetailViewModel = viewModel(
                key = providerId,
                factory = com.devstation.android.feature.ai.AiProviderDetailViewModel.provideFactory(
                    providerId = providerId,
                    providerManager = container.aiProviderManager,
                    configRepository = container.aiProviderConfigRepository,
                    modelCacheRepository = container.aiModelCacheRepository,
                    credentialManager = container.aiCredentialManager,
                    dispatchers = container.dispatchers
                )
            )
            com.devstation.android.feature.ai.AiProviderDetailScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.AiSettings.route) {
            val viewModel: com.devstation.android.feature.ai.AiSettingsViewModel = viewModel(
                factory = com.devstation.android.feature.ai.AiSettingsViewModel.provideFactory(
                    settingsRepository = container.aiSettingsRepository,
                    configRepository = container.aiProviderConfigRepository
                )
            )
            com.devstation.android.feature.ai.AiSettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Storage.route) {
            val viewModel: StorageViewModel = viewModel(
                factory = StorageViewModel.provideFactory(
                    storageStatsCalculator = container.storageStatsCalculator,
                    projectRepository = container.projectRepository
                )
            )
            StorageScreen(viewModel = viewModel)
        }

        composable(Screen.Settings.route) {
            val viewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.provideFactory(
                    settingsRepository = container.settingsRepository,
                    storageStatsCalculator = container.storageStatsCalculator
                )
            )
            SettingsScreen(
                viewModel = viewModel,
                onNavigateToProjects = { navController.navigate(Screen.Projects.route) },
                onNavigateToAiProviders = { navController.navigate(Screen.AiProviders.route) },
                onNavigateToAiSettings = { navController.navigate(Screen.AiSettings.route) },
                onNavigateToAgentTasks = { navController.navigate(Screen.AgentTasks.route) }
            )
        }

        composable(
            route = Screen.ProjectSettings.route,
            arguments = listOf(
                navArgument("projectId") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
            ProjectSettingsScreen(
                projectId = projectId,
                projectRepository = container.projectRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
