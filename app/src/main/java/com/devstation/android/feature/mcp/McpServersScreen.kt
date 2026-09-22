package com.devstation.android.feature.mcp

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
import com.devstation.android.core.mcp.McpServerConfig
import com.devstation.android.core.mcp.McpServerState
import com.devstation.android.core.mcp.McpTransportType

/**
 * Phase 8 §14: MCP Server management screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(
    servers: List<McpServerConfig>,
    statuses: Map<String, McpServerState>,
    onServerClick: (String) -> Unit,
    onAddServer: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("MCP Servers") },
            actions = {
                IconButton(onClick = onAddServer) {
                    Icon(Icons.Default.Add, contentDescription = "Add Server")
                }
            }
        )

        if (servers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No MCP servers configured", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Add an MCP server to extend the agent with external tools and resources.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onAddServer) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Server")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(servers, key = { it.id }) { server ->
                    McpServerCard(
                        server = server,
                        state = statuses[server.id] ?: McpServerState.DISCONNECTED,
                        onClick = { onServerClick(server.id) },
                        onConnect = { onConnect(server.id) },
                        onDisconnect = { onDisconnect(server.id) },
                        onRemove = { onRemove(server.id) },
                        onToggleEnabled = { onToggleEnabled(server.id, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun McpServerCard(
    server: McpServerConfig,
    state: McpServerState,
    onClick: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRemove: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        server.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (server.description.isNotBlank()) {
                        Text(
                            server.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Status indicator
                    StatusChip(state)

                    Spacer(modifier = Modifier.width(8.dp))

                    // Enable/disable switch
                    Switch(
                        checked = server.enabled,
                        onCheckedChange = onToggleEnabled
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Transport info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when (server.transportType) {
                                McpTransportType.STDIO -> "STDIO: ${server.command}"
                                McpTransportType.HTTP -> "HTTP: ${server.endpoint ?: ""}"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    leadingIcon = {
                        Icon(
                            when (server.transportType) {
                                McpTransportType.STDIO -> Icons.Outlined.Terminal
                                McpTransportType.HTTP -> Icons.Outlined.Language
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state == McpServerState.CONNECTED) {
                    TextButton(onClick = onDisconnect) {
                        Text("Disconnect")
                    }
                } else if (server.enabled && state != McpServerState.CONNECTING) {
                    TextButton(onClick = onConnect) {
                        Text("Connect")
                    }
                }

                TextButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Remove")
                }
            }
        }
    }
}

@Composable
private fun StatusChip(state: McpServerState) {
    val (color, text) = when (state) {
        McpServerState.CONNECTED -> MaterialTheme.colorScheme.primary to "Connected"
        McpServerState.CONNECTING -> MaterialTheme.colorScheme.tertiary to "Connecting…"
        McpServerState.DISABLED -> MaterialTheme.colorScheme.outline to "Disabled"
        McpServerState.ERROR -> MaterialTheme.colorScheme.error to "Error"
        McpServerState.STOPPING -> MaterialTheme.colorScheme.tertiary to "Stopping…"
        McpServerState.DISCONNECTED -> MaterialTheme.colorScheme.outline to "Disconnected"
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
