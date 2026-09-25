package com.devstation.android.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector? = null,
    val selectedIcon: ImageVector? = null
) {
    data object Editor : Screen(
        route = "editor?projectPath={projectPath}&filePath={filePath}&line={line}",
        title = "Editor",
        icon = Icons.Outlined.Code,
        selectedIcon = Icons.Filled.Code
    ) {
        fun createRoute(projectPath: String = "", filePath: String = "", line: Int? = null): String {
            val encodedPath = java.net.URLEncoder.encode(projectPath, "UTF-8")
            val encodedFile = java.net.URLEncoder.encode(filePath, "UTF-8")
            val lineArg = if (line != null) "&line=$line" else ""
            return "editor?projectPath=$encodedPath&filePath=$encodedFile$lineArg"
        }
    }

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

    data object Terminal : Screen(
        route = "terminal?projectPath={projectPath}",
        title = "Terminal",
        // v1.1.5: stock icons instead of the custom lazy vector — the bottom bar
        // must never depend on custom init paths during startup composition.
        icon = Icons.Outlined.Terminal,
        selectedIcon = Icons.Filled.Terminal
    ) {
        fun createRoute(projectPath: String = ""): String {
            val encodedPath = java.net.URLEncoder.encode(projectPath, "UTF-8")
            return "terminal?projectPath=$encodedPath"
        }
    }

    data object LinuxRuntime : Screen(
        route = "runtime",
        title = "Linux Environment"
    )

    // Phase 5: AI provider system destinations
    data object AiProviders : Screen(
        route = "ai_providers",
        title = "AI Providers",
        icon = Icons.Outlined.Psychology,
        selectedIcon = Icons.Filled.Psychology
    )

    data object AiProviderDetail : Screen(
        route = "ai_provider/{providerId}",
        title = "AI Provider"
    ) {
        fun createRoute(providerId: String) = "ai_provider/$providerId"
    }

    data object AiSettings : Screen(
        route = "ai_settings",
        title = "AI Settings"
    )

    // Phase 6: agent task history + details
    data object AgentTasks : Screen(
        route = "agent_tasks",
        title = "Agent Tasks"
    )

    data object AgentTaskDetail : Screen(
        route = "agent_task/{taskId}",
        title = "Agent Task"
    ) {
        fun createRoute(taskId: String) = "agent_task/$taskId"
    }

    // Phase 7: permissions, sandbox + security hardening
    data object AgentPermissions : Screen(
        route = "agent_permissions",
        title = "Agent Permissions",
        icon = Icons.Outlined.Security,
        selectedIcon = Icons.Filled.Security
    )

    data object SecurityActivity : Screen(
        route = "security_activity",
        title = "Security Activity"
    )

    data object SecurityDiagnostics : Screen(
        route = "security_diagnostics",
        title = "Security Diagnostics"
    )

    // Phase 8: MCP, Skills, Custom Agents
    data object McpServers : Screen(
        route = "mcp_servers",
        title = "MCP Servers"
    )

    data object McpServerDetail : Screen(
        route = "mcp_server/{serverId}",
        title = "MCP Server"
    ) {
        fun createRoute(serverId: String) = "mcp_server/$serverId"
    }

    data object Skills : Screen(
        route = "skills",
        title = "Skills"
    )

    data object SkillDetail : Screen(
        route = "skill/{skillId}",
        title = "Skill"
    ) {
        fun createRoute(skillId: String) = "skill/$skillId"
    }

    data object AgentProfiles : Screen(
        route = "agent_profiles",
        title = "Custom Agents"
    )

    data object AgentBuilder : Screen(
        route = "agent_builder/{profileId}",
        title = "Agent Builder"
    ) {
        fun createRoute(profileId: String = "new") = "agent_builder/$profileId"
    }

    // Phase 9: browser + live preview
    data object Browser : Screen(
        route = "browser?url={url}",
        title = "Browser"
    ) {
        fun createRoute(url: String = ""): String {
            val encoded = java.net.URLEncoder.encode(url, "UTF-8")
            return "browser?url=$encoded"
        }
    }

    data object Preview : Screen(
        route = "preview",
        title = "Preview"
    )

    // Phase 10: Git + GitHub
    data object Git : Screen(
        route = "git?projectPath={projectPath}&projectName={projectName}",
        title = "Git"
    ) {
        fun createRoute(projectPath: String = "", projectName: String = ""): String {
            val encodedPath = java.net.URLEncoder.encode(projectPath, "UTF-8")
            val encodedName = java.net.URLEncoder.encode(projectName, "UTF-8")
            return "git?projectPath=$encodedPath&projectName=$encodedName"
        }
    }

    data object GitHub : Screen(
        route = "github",
        title = "GitHub"
    )

    companion object {
        val bottomNavScreens = listOf(
            Home,
            Projects,
            Files,
            Terminal,
            Conversations,
            Storage,
            Settings
        )
    }

    /** v1.1.3: base route without query placeholders for bottom-tab navigation. */
    val tabRoute: String
        get() = route.substringBefore("?")
}
