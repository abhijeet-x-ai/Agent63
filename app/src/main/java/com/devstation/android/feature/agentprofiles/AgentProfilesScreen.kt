package com.devstation.android.feature.agentprofiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devstation.android.core.agent.profiles.AgentProfile
import com.devstation.android.core.agent.profiles.AgentProfileSecurityScope

/**
 * Phase 8 §28: Custom Agent Profiles screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentProfilesScreen(
    profiles: List<AgentProfile>,
    activeProfileId: String?,
    onProfileClick: (String) -> Unit,
    onCreateProfile: () -> Unit,
    onSetActive: (String?) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Custom Agents") },
            actions = {
                IconButton(onClick = onCreateProfile) {
                    Icon(Icons.Default.Add, contentDescription = "Create Agent")
                }
            }
        )

        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No custom agents", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Create a custom agent profile to configure tools, skills, and MCP servers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onCreateProfile) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Agent")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    AgentProfileCard(
                        profile = profile,
                        isActive = profile.id == activeProfileId,
                        onClick = { onProfileClick(profile.id) },
                        onSetActive = { onSetActive(if (profile.id == activeProfileId) null else profile.id) },
                        onDelete = { onDelete(profile.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentProfileCard(
    profile: AgentProfile,
    isActive: Boolean,
    onClick: () -> Unit,
    onSetActive: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = if (isActive) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ) else CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (isActive) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    "Active",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                    if (profile.description.isNotBlank()) {
                        Text(
                            profile.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Security mode indicator
                    AssistChip(
                        onClick = {},
                        label = { Text(profile.securityScope.name, style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(
                                when (profile.securityScope) {
                                    AgentProfileSecurityScope.SAFE -> Icons.Outlined.Shield
                                    AgentProfileSecurityScope.BALANCED -> Icons.Outlined.Balance
                                    AgentProfileSecurityScope.CUSTOM -> Icons.Outlined.Tune
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(onClick = onSetActive) {
                        Icon(
                            if (isActive) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (isActive) "Deactivate" else "Set Active",
                            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Capabilities summary
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (profile.enabledTools.isNotEmpty()) {
                    AssistChip(
                        onClick = {},
                        label = { Text("${profile.enabledTools.size} tools", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(Icons.Outlined.Build, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                }
                if (profile.enabledSkills.isNotEmpty()) {
                    AssistChip(
                        onClick = {},
                        label = { Text("${profile.enabledSkills.size} skills", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                }
                if (profile.enabledMcpServers.isNotEmpty()) {
                    AssistChip(
                        onClick = {},
                        label = { Text("${profile.enabledMcpServers.size} MCP", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                }
            }

            if (profile.hasHighRiskCapabilities()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "High-risk capabilities requested",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
