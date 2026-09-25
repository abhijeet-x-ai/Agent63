package com.devstation.android.core.ui.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.devstation.android.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevStationResponsiveScaffold(
    navController: NavController,
    activeProjectName: String? = null,
    content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    var showExecutionDialog by remember { mutableStateOf(false) }
    var showAgentDialog by remember { mutableStateOf(false) }

    if (showExecutionDialog) {
        AlertDialog(
            onDismissRequest = { showExecutionDialog = false },
            title = { Text("Execution Environment", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "Current Target: Local Device Storage\n\n" +
                            "All project files, configurations, and conversation sessions are saved locally in internal/external storage on this device.\n\n" +
                            "Linux PRoot runtime and container execution will be introduced in Phase 3.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showExecutionDialog = false }) {
                    Text("Understood")
                }
            }
        )
    }

    if (showAgentDialog) {
        AlertDialog(
            onDismissRequest = { showAgentDialog = false },
            title = { Text("Agent Engine", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "Agent Mode: Main Agent (Local Standby)\n\n" +
                            "Autonomous AI coding agents, tool dispatch, and MCP client are scheduled for Phase 5 & 6.\n\n" +
                            "The UI shell is currently running in local disconnected mode.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showAgentDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = isLandscape || maxWidth >= 600.dp

        if (useRail) {
            // Landscape / Tablet layout: Rail + Main Content
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    header = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            Text(
                                text = "DEV",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                ) {
                    Screen.bottomNavScreens.forEach { screen ->
                        val selected = currentRoute?.startsWith(screen.route.split("?").first()) == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = {
                                navigateToScreen(navController, screen)
                            },
                            icon = {
                                Icon(
                                    imageVector = tabIcon(screen, selected),
                                    contentDescription = screen.title
                                )
                            },
                            label = {
                                Text(
                                    text = screen.title,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }

                // Content Area with Top Status Bar
                Column(modifier = Modifier.fillMaxSize()) {
                    TopStatusBar(
                        activeProjectName = activeProjectName,
                        onExecutionClick = { showExecutionDialog = true },
                        onAgentClick = { showAgentDialog = true }
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        content()
                    }
                }
            }
        } else {
            // Portrait phone layout: Top Status Bar + Content + Bottom Nav
            Column(modifier = Modifier.fillMaxSize()) {
                TopStatusBar(
                    activeProjectName = activeProjectName,
                    onExecutionClick = { showExecutionDialog = true },
                    onAgentClick = { showAgentDialog = true }
                )

                Box(modifier = Modifier.weight(1f)) {
                    content()
                }

                // Show bottom bar for main top-level routes
                val isTopLevel = Screen.bottomNavScreens.any {
                    currentRoute?.startsWith(it.route.split("?").first()) == true
                }

                if (isTopLevel || currentRoute == null) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 4.dp
                    ) {
                        Screen.bottomNavScreens.forEach { screen ->
                            val selected = currentRoute?.startsWith(screen.route.split("?").first()) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navigateToScreen(navController, screen)
                                },
                                icon = {
                                    Icon(
                                        imageVector = tabIcon(screen, selected),
                                        contentDescription = screen.title
                                    )
                                },
                                label = {
                                    Text(
                                        text = screen.title,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun navigateToScreen(navController: NavController, screen: Screen) {
    // v1.1.3: navigate to the base tab route so bottom tabs never carry literal
    // "{projectPath}" placeholders. Args have defaults so "files"/"terminal" resolve.
    navController.navigate(screen.tabRoute) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * v1.1.5: bottom-bar icons resolved by a `when` over the Screen object — never by
 * reading `Screen.icon`/`selectedIcon` properties during composition. `is` checks are
 * null-safe, so even a broken/null entry falls through to the Dashboard fallback
 * instead of throwing NPE (see v1.1.3 `safeIcon` crash on `Screen.getIcon()`).
 */
private fun tabIcon(screen: Screen?, selected: Boolean): androidx.compose.ui.graphics.vector.ImageVector {
    return when (screen) {
        is Screen.Home ->
            if (selected) Icons.Filled.Dashboard else Icons.Outlined.Dashboard
        is Screen.Projects ->
            if (selected) Icons.Filled.Folder else Icons.Outlined.Folder
        is Screen.Files ->
            // Same glyphs as Screen.Files (deprecated non-mirrored set is what this BOM ships).
            if (selected) Icons.Filled.InsertDriveFile else Icons.Outlined.InsertDriveFile
        is Screen.Terminal ->
            if (selected) Icons.Filled.Terminal else Icons.Outlined.Terminal
        is Screen.Conversations ->
            if (selected) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline
        is Screen.Storage ->
            if (selected) Icons.Filled.Storage else Icons.Outlined.Storage
        is Screen.Settings ->
            if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
        else -> Icons.Filled.Dashboard
    }
}

@Composable
fun TopStatusBar(
    activeProjectName: String?,
    onExecutionClick: () -> Unit,
    onAgentClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Brand and Active Project
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "AGENT 63",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (activeProjectName != null) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = activeProjectName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Status Indicators
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Local Execution Badge
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onExecutionClick() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Local",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Target details",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Agent Engine Badge
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onAgentClick() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = "Agent status",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Main Agent",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Agent selector",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
