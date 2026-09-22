package com.devstation.android.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devstation.android.core.common.FormatUtils
import com.devstation.android.core.model.AppTheme

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToProjects: () -> Unit,
    onNavigateToAiProviders: () -> Unit = {},
    onNavigateToAiSettings: () -> Unit = {},
    onNavigateToAgentTasks: () -> Unit = {},
    onNavigateToAgentPermissions: () -> Unit = {},
    onNavigateToSecurityActivity: () -> Unit = {},
    onNavigateToMcpServers: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
    onNavigateToAgentProfiles: () -> Unit = {},
    onNavigateToPreview: () -> Unit = {},
    onNavigateToBrowser: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showFutureNoticeDialog by remember { mutableStateOf<String?>(null) }
    var showThemeDialog by remember { mutableStateOf(false) }

    if (showFutureNoticeDialog != null) {
        AlertDialog(
            onDismissRequest = { showFutureNoticeDialog = null },
            title = { Text(showFutureNoticeDialog ?: "Section", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "This section will be fully functional in future development phases.\n\n" +
                            "Architectural interfaces and secure storage are already established in the codebase.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showFutureNoticeDialog = null }) {
                    Text("OK")
                }
            }
        )
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Choose Theme", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    AppTheme.values().forEach { theme ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setTheme(theme)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = uiState.settings.theme == theme,
                                onClick = {
                                    viewModel.setTheme(theme)
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (theme) {
                                    AppTheme.DARK -> "Dark (Default Technical)"
                                    AppTheme.LIGHT -> "Light"
                                    AppTheme.SYSTEM -> "Follow System"
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Workstation Configuration & Preferences",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Appearance
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Appearance",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    SettingsNavRow(
                        title = "Theme",
                        subtitle = when (uiState.settings.theme) {
                            AppTheme.DARK -> "Dark (Technical Workstation)"
                            AppTheme.LIGHT -> "Light"
                            AppTheme.SYSTEM -> "System Default"
                        },
                        icon = Icons.Default.Palette,
                        onClick = { showThemeDialog = true }
                    )
                }
            }
        }

        // Storage & General
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Storage & General",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    val stats = uiState.storageStats
                    val sizeText = if (stats != null) FormatUtils.formatBytes(stats.projectsBytes) else "Calculating..."

                    SettingsNavRow(
                        title = "Local Projects Allocation",
                        subtitle = "$sizeText currently in projects workspace",
                        icon = Icons.Default.Storage,
                        onClick = onNavigateToProjects
                    )
                }
            }
        }

        // Future Modules Section (Placeholders as specified)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Advanced Modules (Future Phases)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(10.dp))



                    SettingsNavRow(
                        title = "AI Model Providers",
                        subtitle = "Phase 5 • Gemini, Claude, OpenAI-compatible",
                        icon = Icons.Default.Psychology,
                        onClick = onNavigateToAiProviders
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    SettingsNavRow(
                        title = "AI Settings",
                        subtitle = "Default provider, streaming, timeouts & retries",
                        icon = Icons.Default.Settings,
                        onClick = onNavigateToAiSettings
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    SettingsNavRow(
                        title = "Agent Tools & Task History",
                        subtitle = "Phase 6 • approval-gated tools, action history",
                        icon = Icons.Default.Build,
                        onClick = onNavigateToAgentTasks
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    SettingsNavRow(
                        title = "Agent Permissions",
                        subtitle = "Phase 7 • security mode, scopes, sandbox, revocation",
                        icon = Icons.Default.Security,
                        onClick = onNavigateToAgentPermissions
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    SettingsNavRow(
                        title = "Security Activity",
                        subtitle = "Phase 7 • audit trail of every permission decision",
                        icon = Icons.Default.Lock,
                        onClick = onNavigateToSecurityActivity
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    SettingsNavRow(
                        title = "MCP Servers",
                        subtitle = "Phase 8 • external tool providers, capability discovery",
                        icon = Icons.Default.Dns,
                        onClick = onNavigateToMcpServers
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    SettingsNavRow(
                        title = "Skills",
                        subtitle = "Phase 8 • reusable AI workflows, built-in and custom",
                        icon = Icons.Default.AutoAwesome,
                        onClick = onNavigateToSkills
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    SettingsNavRow(
                        title = "Custom Agents",
                        subtitle = "Phase 8 • agent profiles for tools, skills and MCP",
                        icon = Icons.Default.SmartToy,
                        onClick = onNavigateToAgentProfiles
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    SettingsNavRow(
                        title = "Live Preview & Browser",
                        subtitle = "Phase 9 • localhost dev servers, secure embedded browser",
                        icon = Icons.Default.Public,
                        onClick = onNavigateToPreview
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    SettingsNavRow(
                        title = "Accounts & Integrations",
                        subtitle = "Phase 10 • GitHub, Cloud accounts",
                        icon = Icons.Default.Person,
                        isPlaceholder = true,
                        onClick = { showFutureNoticeDialog = "Accounts & Integrations" }
                    )
                }
            }
        }

        // About & Version
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "About DevStation",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "DevStation Mobile Workstation v1.0.0",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Phase 1: Foundation & UI Architecture",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Local filesystem storage • Jetpack Compose • Room Database • Keystore Foundation",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsNavRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isPlaceholder: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isPlaceholder) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        if (isPlaceholder) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.Default.Lock, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
