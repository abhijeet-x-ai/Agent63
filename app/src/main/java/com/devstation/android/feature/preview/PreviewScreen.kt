package com.devstation.android.feature.preview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devstation.android.core.preview.PreviewLogEntry
import com.devstation.android.core.preview.PreviewServer
import com.devstation.android.core.preview.PreviewServerState

/**
 * Phase 9 §47: the Preview dashboard — server status, controls, console, embedded preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    projectName: String,
    server: PreviewServer?,
    logs: List<PreviewLogEntry>,
    onStart: (command: String, arguments: String, port: Int) -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    onClearLogs: () -> Unit,
    onNavigateBack: () -> Unit
) {
    var showStartDialog by remember { mutableStateOf(server == null) }
    var showEmbeddedPreview by remember { mutableStateOf(false) }
    var logSearch by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size, autoScroll) {
        if (autoScroll && logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Preview") },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (server != null && server.url.isNotBlank()) {
                    IconButton(onClick = { onOpenBrowser(server.url) }) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in browser")
                    }
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Server", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    DetailRow("Project", projectName.ifBlank { "—" })
                    if (server == null) {
                        DetailRow("Status", "Not running")
                        Text(
                            "Start a development server to preview this project in the built-in browser.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        DetailRow("Status", server.state.name)
                        DetailRow("Port", server.port.toString())
                        DetailRow("URL", server.url)
                        DetailRow("Command", "${server.command} ${server.arguments.joinToString(" ")}".trim())
                        DetailRow("Bound to", if (server.host == "0.0.0.0") "All interfaces (user-approved)" else "127.0.0.1 (loopback only)")
                        server.lastError?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (server?.state) {
                            PreviewServerState.RUNNING, PreviewServerState.STARTING -> {
                                Button(onClick = onStop) { Text("Stop") }
                                OutlinedButton(onClick = onRestart) { Text("Restart") }
                                if (server.state == PreviewServerState.RUNNING) {
                                    OutlinedButton(onClick = { showEmbeddedPreview = true }) { Text("Open Preview") }
                                }
                            }
                            else -> Button(onClick = { showStartDialog = true }) { Text("Start") }
                        }
                        TextButton(onClick = {
                            server?.let { onOpenBrowser(it.url) }
                        }) { Text("Copy URL") }
                    }
                }
            }

            Card(modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Console", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row {
                            IconButton(onClick = { autoScroll = !autoScroll }) {
                                Icon(
                                    if (autoScroll) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (autoScroll) "Pause auto-scroll" else "Resume auto-scroll",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(onClick = onClearLogs) {
                                Icon(Icons.Default.Delete, contentDescription = "Clear logs", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    OutlinedTextField(
                        value = logSearch,
                        onValueChange = { logSearch = it },
                        label = { Text("Search logs") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    val filtered = if (logSearch.isBlank()) logs else logs.filter {
                        it.text.contains(logSearch, ignoreCase = true)
                    }
                    if (filtered.isEmpty()) {
                        Text(
                            "No output.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(filtered) { entry ->
                                val color = when (entry.stream) {
                                    PreviewLogEntry.Stream.STDERR -> MaterialTheme.colorScheme.error
                                    PreviewLogEntry.Stream.SYSTEM -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                Text(
                                    entry.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = color,
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showStartDialog) {
        StartPreviewDialog(
            initialCommand = "npm run dev",
            onDismiss = { showStartDialog = false },
            onStart = { command, args, port ->
                showStartDialog = false
                onStart(command, args, port)
            }
        )
    }

    if (showEmbeddedPreview && server != null) {
        Dialog(onDismissRequest = { showEmbeddedPreview = false }) {
            Surface(
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
            ) {
                EmbeddedPreviewPane(url = server.url)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(90.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StartPreviewDialog(
    initialCommand: String,
    onDismiss: () -> Unit,
    onStart: (command: String, arguments: String, port: Int) -> Unit
) {
    var command by remember { mutableStateOf(initialCommand) }
    var arguments by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("3000") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start Preview Server") },
        text = {
            Column {
                OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Command (e.g. npm run dev)") }, singleLine = true)
                OutlinedTextField(value = arguments, onValueChange = { arguments = it }, label = { Text("Arguments") }, singleLine = true)
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Preferred port") }, singleLine = true)
                Text(
                    "The command is checked by the terminal security policy. The server binds to " +
                        "127.0.0.1 only. Secrets are never passed to the process.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (command.isBlank()) { error = "A command is required."; return@TextButton }
                val portNumber = port.toIntOrNull()
                if (portNumber == null || portNumber !in 1024..65535) {
                    error = "Port must be 1024–65535."
                    return@TextButton
                }
                onStart(command.trim(), arguments.trim(), portNumber)
            }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * §48/§24: embedded preview pane. Local project assets are served by the managed localhost
 * server — never by file:// URLs (§34). The fully hardened WebView lives in the shared
 * Browser screen; this pane links to it so hardening is never duplicated (or skipped).
 */
@Composable
private fun EmbeddedPreviewPane(url: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(Icons.Outlined.OpenInBrowser, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(8.dp))
            Text("Open the full browser for the live preview.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
