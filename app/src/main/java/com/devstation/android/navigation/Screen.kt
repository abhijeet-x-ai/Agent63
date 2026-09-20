package com.devstation.android.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector? = null,
    val selectedIcon: ImageVector? = null
) {
    data object Home : Screen(
        route = "home",
        title = "Home",
        icon = Icons.Outlined.Dashboard,
        selectedIcon = Icons.Filled.Dashboard
    )

    data object Projects : Screen(
        route = "projects",
        title = "Projects",
        icon = Icons.Outlined.Folder,
        selectedIcon = Icons.Filled.Folder
    )

    data object Files : Screen(
        route = "files?projectPath={projectPath}&projectName={projectName}",
        title = "Files",
        icon = Icons.Outlined.InsertDriveFile,
        selectedIcon = Icons.Filled.InsertDriveFile
    ) {
        fun createRoute(projectPath: String = "", projectName: String = ""): String {
            val encodedPath = java.net.URLEncoder.encode(projectPath, "UTF-8")
            val encodedName = java.net.URLEncoder.encode(projectName, "UTF-8")
            return "files?projectPath=$encodedPath&projectName=$encodedName"
        }
    }

    data object Conversations : Screen(
        route = "conversations",
        title = "Conversations",
        icon = Icons.Outlined.ChatBubbleOutline,
        selectedIcon = Icons.Filled.ChatBubble
    )

    data object ConversationDetail : Screen(
        route = "conversation/{conversationId}",
        title = "Session"
    ) {
        fun createRoute(conversationId: String) = "conversation/$conversationId"
    }

    data object Storage : Screen(
        route = "storage",
        title = "Storage",
        icon = Icons.Outlined.Storage,
        selectedIcon = Icons.Filled.Storage
    )

    data object Settings : Screen(
        route = "settings",
        title = "Settings",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Filled.Settings
    )

    data object ProjectSettings : Screen(
        route = "project_settings/{projectId}",
        title = "Project Settings"
    ) {
        fun createRoute(projectId: String) = "project_settings/$projectId"
    }

    companion object {
        val bottomNavScreens = listOf(
            Home,
            Projects,
            Files,
            Conversations,
            Storage,
            Settings
        )
    }
}
