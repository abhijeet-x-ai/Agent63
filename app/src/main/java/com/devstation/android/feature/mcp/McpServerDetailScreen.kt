package com.devstation.android.feature.mcp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devstation.android.core.mcp.McpCapability
import com.devstation.android.core.mcp.McpSecurityClassification
import com.devstation.android.core.mcp.McpServerConfig
import com.devstation.android.core.mcp.McpServerState
import com.devstation.android.core.mcp.McpTransportType
import java.util.UUID

/**
 * Phase 8.1 §25–§27: MCP server detail screen.
 *
 * Shows overview, live status, transport/endpoint, capabilities (tools/resources/prompts) with
 * their security classifications, a security section, and all lifecycle actions. Secrets are never
 * displayed — credential references show as "Authenticated" only (§17/§49).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServerDetailScreen(
    viewModel: McpServerDetailViewModel,
    serverId: String,
    isNew: Boolean,
    onNavigateBack: () -> Unit
) {
    LaunchedEffect(serverId) { viewModel.bind(serverId) }
    val state by viewModel.uiState.collectAsState()
    val saveError by viewModel.saveError.collectAsState()

    var showEditDialog by remember { mutableStateOf(isNew) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var showRefreshNote by remember { mutableStateOf(false) }

    val config = state.config

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(config?.name ?: "New MCP Server") },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (!isNew && config != null) {
                    TextButton(onClick = { showEditDialog = true }) { Text("Edit") }
                }
            }
        )

        if (config == null && !isNew) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Server not found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        saveError?.let { error ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = viewModel::clearSaveError) { Text("Dismiss") }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (config != null) {
                item {
                    SectionCard("Overview") {
                        DetailRow("Transport", config.transportType.name)
                        when (config.transportType) {
                            McpTransportType.STDIO -> {
                                DetailRow("Command", config.command)
                                if (config.arguments.isNotEmpty()) {
                                    DetailRow("Arguments", config.arguments.joinToString(" "))
                                }
                            }
                            McpTransportType.HTTP -> DetailRow("Endpoint", config.endpoint ?: "—")
                        }
                        DetailRow(
                            "Project scope",
                            config.projectScope ?: "All projects"
                        )
                        DetailRow("Security mode", config.securityMode)
                        if (config.credentialReferenceId != null) {
                            // §17: never show the secret itself.
                            DetailRow("Authentication", "Authenticated (credential reference)")
                        }
                    }
                }
                item {
                    SectionCard("Status") {
                        val statusState = state.status?.state ?: McpServerState.DISCONNECTED
                        DetailRow("State", statusState.name.lowercase().replaceFirstChar { it.uppercase() })
                        state.status?.lastError?.let { DetailRow("Last error", it) }
                        state.status?.lastConnectedAt?.let {
                            DetailRow("Last connected", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)))
                        }
                    }
                }
                item {
                    SectionCard("Capabilities") {
                        Text(
                            "Classifications are assigned by DevStation's security engine — server " +
                                "descriptions never influence them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        CapabilityGroup("Tools", state.tools()) { it.name }
                        CapabilityGroup("Resources", state.resources()) { it.name }
                        CapabilityGroup("Prompts", state.prompts()) { it.name }
                        if (state.capabilities.isEmpty()) {
                            Text(
                                "No capabilities discovered yet — connect to the server and refresh.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { viewModel.refresh(); showRefreshNote = true }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Refresh capabilities")
                        }
                    }
                }
                item {
                    SectionCard("Security") {
                        BulletLine("Unknown capabilities always require explicit approval.")
                        BulletLine("MCP output is size-limited and passed through secret redaction.")
                        BulletLine("STDIO servers run with a sanitized environment — DevStation secrets are never inherited.")
                        BulletLine("HTTP endpoints require HTTPS (except localhost) and are re-validated on every redirect.")
                        BulletLine("Server-provided descriptions are treated as untrusted data.")
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    if (config != null) {
                        if (state.connected) {
                            Button(onClick = viewModel::disconnect) { Text("Disconnect") }
                        } else if (config.enabled) {
                            Button(onClick = viewModel::connect) { Text("Connect") }
                        }
                        OutlinedButton(onClick = { viewModel.setEnabled(!config.enabled) }) {
                            Text(if (config.enabled) "Disable" else "Enable")
                        }
                        OutlinedButton(
                            onClick = { showRemoveConfirm = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("Remove") }
                    } else {
                        Button(onClick = { showEditDialog = true }) { Text("Configure Server") }
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        McpServerEditDialog(
            existing = config,
            onDismiss = { showEditDialog = false },
            onSave = { edited ->
                viewModel.clearSaveError()
                viewModel.save(edited) { success ->
                    if (success) showEditDialog = false
                }
            }
        )
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove MCP server?") },
            text = { Text("The server will be disconnected and its configuration removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showRemoveConfirm = false; viewModel.remove(onNavigateBack) }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BulletLine(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("• ", style = MaterialTheme.typography.bodySmall)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CapabilityGroup(
    title: String,
    capabilities: List<McpCapability>,
    label: (McpCapability) -> String
) {
    if (capabilities.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 4.dp))
    capabilities.forEach { capability ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label(capability),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            ClassificationChip(capability.securityClassification)
        }
    }
}

@Composable
private fun ClassificationChip(classification: McpSecurityClassification) {
    val (color, text) = when (classification) {
        McpSecurityClassification.READ_ONLY -> MaterialTheme.colorScheme.primary to "Read"
        McpSecurityClassification.PROJECT_WRITE -> MaterialTheme.colorScheme.tertiary to "Write"
        McpSecurityClassification.NETWORK -> MaterialTheme.colorScheme.tertiary to "Network"
        McpSecurityClassification.PACKAGE_INSTALL -> MaterialTheme.colorScheme.error to "Packages"
        McpSecurityClassification.DESTRUCTIVE -> MaterialTheme.colorScheme.error to "Destructive"
        McpSecurityClassification.SYSTEM -> MaterialTheme.colorScheme.error to "System"
        McpSecurityClassification.UNKNOWN -> MaterialTheme.colorScheme.error to "Unknown"
    }
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/**
 * Phase 8.1 §26: server add/edit dialog. Everything is validated by McpServerManager on save;
 * secret-looking environment names are rejected with an explanation rather than stored.
 */
@Composable
fun McpServerEditDialog(
    existing: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var transport by remember { mutableStateOf(existing?.transportType ?: McpTransportType.STDIO) }
    var command by remember { mutableStateOf(existing?.command ?: "") }
    var arguments by remember { mutableStateOf(existing?.arguments?.joinToString(" ") ?: "") }
    var endpoint by remember { mutableStateOf(existing?.endpoint ?: "") }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var autoConnect by remember { mutableStateOf(existing?.autoConnect ?: false) }
    var envKey by remember { mutableStateOf("") }
    var envValue by remember { mutableStateOf("") }
    var envEntries by remember {
        mutableStateOf(existing?.environment?.map { it.key to it.value } ?: emptyList())
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add MCP Server" else "Edit MCP Server") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") })
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = transport == McpTransportType.STDIO,
                        onClick = { transport = McpTransportType.STDIO },
                        label = { Text("STDIO") }
                    )
                    FilterChip(
                        selected = transport == McpTransportType.HTTP,
                        onClick = { transport = McpTransportType.HTTP },
                        label = { Text("HTTP") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                when (transport) {
                    McpTransportType.STDIO -> {
                        OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Command") }, singleLine = true)
                        OutlinedTextField(value = arguments, onValueChange = { arguments = it }, label = { Text("Arguments (space-separated)") })
                    }
                    McpTransportType.HTTP -> {
                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = { endpoint = it },
                            label = { Text("Endpoint URL (https://…)") },
                            singleLine = true
                        )
                        Text(
                            "HTTPS is required except for localhost. Redirects are re-validated.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Environment (non-secret)", style = MaterialTheme.typography.labelLarge)
                if (envEntries.isNotEmpty()) {
                    envEntries.forEach { (key, value) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$key=$value", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { envEntries = envEntries - (key to value) }) { Text("Remove") }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(value = envKey, onValueChange = { envKey = it }, label = { Text("Name") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = envValue, onValueChange = { envValue = it }, label = { Text("Value") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                TextButton(onClick = {
                    val key = envKey.trim()
                    if (key.isEmpty() || envValue.isEmpty()) {
                        validationError = "Environment entries need both a name and a value."
                        return@TextButton
                    }
                    if (McpServerConfigSensitiveCheck(key)) {
                        validationError = "Environment name '$key' looks like a secret. Use credential references instead."
                        return@TextButton
                    }
                    envEntries = envEntries + (key to envValue)
                    envKey = ""; envValue = ""
                    validationError = null
                }) { Text("Add environment variable") }

                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("Enabled", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(12.dp))
                    Checkbox(checked = autoConnect, onCheckedChange = { autoConnect = it })
                    Text("Auto-connect", style = MaterialTheme.typography.bodySmall)
                }

                validationError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) {
                    validationError = "A name is required."
                    return@TextButton
                }
                if (transport == McpTransportType.STDIO && command.isBlank()) {
                    validationError = "STDIO transport requires a command."
                    return@TextButton
                }
                if (transport == McpTransportType.HTTP && endpoint.isBlank()) {
                    validationError = "HTTP transport requires an endpoint."
                    return@TextButton
                }
                onSave(
                    McpServerConfig(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        description = description.trim(),
                        transportType = transport,
                        command = command.trim(),
                        arguments = parseArguments(arguments),
                        environment = envEntries.toMap(),
                        endpoint = endpoint.trim().ifBlank { null },
                        enabled = enabled,
                        autoConnect = autoConnect,
                        securityMode = existing?.securityMode ?: "BALANCED",
                        projectScope = existing?.projectScope,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis()
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Mirrors McpServerConfig's sensitive-name detection for immediate dialog feedback. */
internal fun McpServerConfigSensitiveCheck(key: String): Boolean =
    listOf("API_KEY", "TOKEN", "SECRET", "PASSWORD", "PRIVATE_KEY", "CREDENTIAL", "AUTH", "ACCESS_KEY", "SESSION_KEY", "OAUTH")
        .any { key.uppercase().contains(it) }
