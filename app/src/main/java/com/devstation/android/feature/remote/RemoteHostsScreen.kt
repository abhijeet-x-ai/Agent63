package com.devstation.android.feature.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.devstation.android.core.remote.SshHostProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteHostsScreen(
    viewModel: RemoteViewModel,
    onBack: () -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (String) -> Unit,
    onOpenTerminal: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<SshHostProfile?>(null) }
    var syncTarget by remember { mutableStateOf<SshHostProfile?>(null) }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote Workspace") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddHost) {
                Icon(Icons.Default.Add, contentDescription = "Add host")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No SSH hosts yet",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Add a server to open remote terminals and sync projects over SSH.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onAddHost) { Text("Add host") }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.profiles, key = { it.id }) { profile ->
                    HostCard(
                        profile = profile,
                        isActive = uiState.activeProfileId == profile.id,
                        isBusy = uiState.busyProfileId == profile.id,
                        isSyncing = uiState.isSyncing && uiState.activeProfileId == profile.id,
                        syncProgress = uiState.syncProgress,
                        onConnect = { viewModel.connect(profile) },
                        onDisconnect = { viewModel.disconnect() },
                        onOpenTerminal = { viewModel.openTerminal(profile, onOpenTerminal) },
                        onSync = { syncTarget = profile },
                        onEdit = { onEditHost(profile.id) },
                        onDelete = { deleteTarget = profile }
                    )
                }
            }
        }
    }

    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete host?") },
            text = { Text("\"${profile.name}\" and its saved secret will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteHost(profile)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    uiState.trustRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissTrust() },
            title = { Text("Trust this server?") },
            text = {
                Column {
                    Text("First connection to \"${request.profileName}\". Verify this fingerprint with the server owner before trusting it:")
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${request.keyType} ${request.fingerprint}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmTrust() }) { Text("Trust & connect") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissTrust() }) { Text("Cancel") }
            }
        )
    }

    uiState.changedKeyWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissChangedKey() },
            title = { Text("Host key changed", color = MaterialTheme.colorScheme.error) },
            text = {
                Column {
                    Text(
                        "The key for \"${warning.profileName}\" does not match the trusted one. " +
                            "This can mean the server was reinstalled — or that someone is intercepting the connection."
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Trusted:", style = MaterialTheme.typography.labelMedium)
                    Text(warning.storedFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Presented now:", style = MaterialTheme.typography.labelMedium)
                    Text(warning.presentedFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmChangedKey() }) {
                    Text("Trust new key", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissChangedKey() }) { Text("Keep blocked") }
            }
        )
    }

    syncTarget?.let { profile ->
        var localPath by remember(profile.id) { mutableStateOf(viewModel.defaultLocalPath) }
        var remotePath by remember(profile.id) { mutableStateOf("~/devstation") }
        AlertDialog(
            onDismissRequest = { syncTarget = null },
            title = { Text("Sync to ${profile.name}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = localPath,
                        onValueChange = { localPath = it },
                        label = { Text("Local project path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = remotePath,
                        onValueChange = { remotePath = it },
                        label = { Text("Remote path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Only new or changed files are uploaded.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.sync(profile, localPath.trim(), remotePath.trim())
                    syncTarget = null
                }) { Text("Sync") }
            },
            dismissButton = {
                TextButton(onClick = { syncTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun HostCard(
    profile: SshHostProfile,
    isActive: Boolean,
    isBusy: Boolean,
    isSyncing: Boolean,
    syncProgress: com.devstation.android.core.remote.SyncProgress?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenTerminal: () -> Unit,
    onSync: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit host")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete host")
                    }
                }
            }
            Text(
                profile.displayAddress,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    isActive -> "● Connected"
                    profile.hostKeyFingerprint != null -> "Key trusted · not connected"
                    else -> "Key not trusted yet · not connected"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (isActive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isSyncing && syncProgress != null && syncProgress.totalFiles > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = syncProgress.completedFiles.toFloat() / syncProgress.totalFiles.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Syncing ${syncProgress.completedFiles}/${syncProgress.totalFiles}…",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isActive) {
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                } else {
                    Button(onClick = onConnect, enabled = !isBusy) {
                        if (isBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        } else {
                            Text("Connect")
                        }
                    }
                }
                OutlinedButton(onClick = onOpenTerminal, enabled = isActive) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Terminal")
                }
                OutlinedButton(onClick = onSync, enabled = isActive && !isSyncing) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Sync")
                }
            }
        }
    }
}
