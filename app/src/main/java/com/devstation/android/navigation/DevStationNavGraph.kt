package com.devstation.android.navigation

import androidx.compose.runtime.Composable
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
                onOpenEditor = { /* Configured in editor module */ }
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
                onOpenFileInEditor = { /* Configured in editor module */ },
                onOpenTerminal = { termPath ->
                    navController.navigate(Screen.Terminal.createRoute(termPath))
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
                    initialProjectPath = projectPath
                )
            )
            com.devstation.android.feature.terminal.TerminalScreen(
                viewModel = viewModel,
                onNavigateToLinuxRuntime = { /* Configured in runtime module */ }
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
            val viewModel: ConversationDetailViewModel = viewModel(
                key = conversationId,
                factory = ConversationDetailViewModel.provideFactory(
                    conversationRepository = container.conversationRepository,
                    conversationId = conversationId
                )
            )
            ConversationDetailScreen(
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
                onNavigateToProjects = { navController.navigate(Screen.Projects.route) }
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
