package com.devstation.android.feature.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devstation.android.core.common.FormatUtils
import com.devstation.android.future.runtime.LinuxDiagnostics
import com.devstation.android.future.runtime.LinuxRuntimeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinuxRuntimeScreen(
    viewModel: LinuxRuntimeViewModel,
    onNavigateBack: () -> Unit,
    onStartLinuxTerminal: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showResetDialog by remember { mutableStateOf(false) }
    var showUninstallDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.infoMessage, uiState.errorMessage) {
        uiState.infoMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Linux Environment",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshState() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // 1. Status Overview Banner
            item {
                StatusOverviewCard(
                    state = uiState.state,
                    isReady = uiState.state == LinuxRuntimeState.READY,
                    onOpenTerminal = onStartLinuxTerminal
                )
            }

            // 2. Installation / Progress Card (if not ready or installing)
            if (uiState.state != LinuxRuntimeState.READY) {
                item {
                    InstallationCard(
                        state = uiState.state,
                        progress = uiState.installProgress,
                        onStartInstall = { viewModel.startInstallation() }
                    )
                }
            }

            // 3. Environment Specs (when installed)
            if (uiState.state == LinuxRuntimeState.READY) {
                item {
                    EnvironmentSpecsCard(
                        storage = uiState.storageUsage,
                        onRunDiagnostics = {
                            viewModel.runDiagnostics()
                            showDiagnosticsDialog = true
                        }
                    )
                }

                // 4. Development Tools Card
                item {
                    DevToolsCard(
                        tools = uiState.devToolsStatus,
                        isInstalling = uiState.isInstallingTools,
                        logMessage = uiState.toolsInstallLog,
                        onInstallTools = { viewModel.installDevelopmentTools() }
                    )
                }

                // 5. Destructive Actions (Reset / Uninstall)
                item {
                    DangerZoneCard(
                        onResetClick = { showResetDialog = true },
                        onUninstallClick = { showUninstallDialog = true }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    // Diagnostics Dialog
    if (showDiagnosticsDialog) {
        DiagnosticsDialog(
            diagnostics = uiState.diagnostics,
            isLoading = uiState.isRunningDiagnostics,
            onDismiss = { showDiagnosticsDialog = false }
        )
    }

    // Reset Confirmation Dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Reset Linux Environment?") },
            text = {
                Text(
                    "This will wipe the Linux rootfs and package cache. Your persistent DevStation projects in /projects will NOT be affected and remain safe."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        viewModel.resetEnvironment(keepHome = true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Reset Environment")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Uninstall Confirmation Dialog
    if (showUninstallDialog) {
        AlertDialog(
            onDismissRequest = { showUninstallDialog = false },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Uninstall Linux Runtime?") },
            text = {
                Text(
                    "This will completely delete the Linux runtime, rootfs, and HOME configuration. All DevStation projects in /projects are completely preserved."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUninstallDialog = false
                        viewModel.uninstallLinux()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Uninstall")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StatusOverviewCard(
    state: LinuxRuntimeState,
    isReady: Boolean,
    onOpenTerminal: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isReady) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isReady) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = when (state) {
                                    LinuxRuntimeState.READY -> Color(0xFF4CAF50)
                                    LinuxRuntimeState.NOT_INSTALLED -> MaterialTheme.colorScheme.outline
                                    LinuxRuntimeState.FAILED -> MaterialTheme.colorScheme.error
                                    else -> Color(0xFFFF9800)
                                },
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (state) {
                            LinuxRuntimeState.READY -> "Alpine Linux (Installed)"
                            LinuxRuntimeState.NOT_INSTALLED -> "Not Installed"
                            LinuxRuntimeState.DOWNLOADING -> "Downloading..."
                            LinuxRuntimeState.EXTRACTING -> "Extracting Filesystem..."
                            LinuxRuntimeState.FAILED -> "Installation Failed"
                            else -> "Setting Up Runtime..."
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isReady) {
                    Button(
                        onClick = onOpenTerminal,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Terminal, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Terminal")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Unprivileged Linux userspace on Android kernel. No root required.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InstallationCard(
    state: LinuxRuntimeState,
    progress: com.devstation.android.future.runtime.InstallProgress?,
    onStartInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Installation",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (state == LinuxRuntimeState.NOT_INSTALLED || state == LinuxRuntimeState.FAILED) {
                Text(
                    text = "Install Alpine Linux minirootfs (~3.6 MB download, ~12 MB installed). This enables standard Linux shell, package manager, and development tools.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onStartInstall,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Install Linux Runtime")
                }
            } else {
                Text(
                    text = progress?.stepTitle ?: "Installing...",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = { (progress?.progressPercentage ?: 0) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = progress?.detailMessage ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${progress?.progressPercentage ?: 0}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun EnvironmentSpecsCard(
    storage: com.devstation.android.future.runtime.LinuxStorageUsage,
    onRunDiagnostics: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Environment Specifications",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(
                    onClick = onRunDiagnostics,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Diagnostics", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SpecRow("Distribution", "Alpine Linux 3.19 (Minirootfs)")
            SpecRow("Architecture", "ARM64 (aarch64) / x86_64")
            SpecRow("Rootfs Storage", FormatUtils.formatBytes(storage.rootfsBytes))
            SpecRow("Persistent HOME", "/home/devstation (${FormatUtils.formatBytes(storage.homeBytes)})")
            SpecRow("Project Mount", "/workspace -> <project>")
            SpecRow("Package Manager", "apk (Alpine Package Keeper)")
            SpecRow("Default Shell", "/bin/sh")
            SpecRow("PTY Status", "Pipe-Bridge (fallback)")
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun DevToolsCard(
    tools: com.devstation.android.future.runtime.DevToolsStatus,
    isInstalling: Boolean,
    logMessage: String,
    onInstallTools: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Development Tools",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            ToolRow("Node.js", tools.nodeInstalled, tools.nodeVersion)
            ToolRow("npm", tools.npmInstalled, tools.npmVersion)
            ToolRow("Python 3", tools.pythonInstalled, tools.pythonVersion)
            ToolRow("pip", tools.pipInstalled, tools.pipVersion)
            ToolRow("Git", tools.gitInstalled, tools.gitVersion)

            Spacer(modifier = Modifier.height(12.dp))

            if (isInstalling) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = logMessage.lines().lastOrNull { it.isNotBlank() } ?: "Installing dev tools...",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Button(
                    onClick = onInstallTools,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Install / Update Dev Tools (~150 MB)")
                }
            }
        }
    }
}

@Composable
private fun ToolRow(name: String, installed: Boolean, version: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (installed) Icons.Default.CheckCircle else Icons.Default.Close,
                contentDescription = null,
                tint = if (installed) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = version ?: "Not installed",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (installed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun DangerZoneCard(
    onResetClick: () -> Unit,
    onUninstallClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Maintenance & Reset",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "DevStation user projects in /projects are never touched by reset or uninstall.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onResetClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Reset Linux")
                }
                OutlinedButton(
                    onClick = onUninstallClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Uninstall Linux")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsDialog(
    diagnostics: LinuxDiagnostics?,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Linux Environment Diagnostics") },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (diagnostics != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DiagRow("Architecture", diagnostics.architectureDetected, diagnostics.architectureSupported)
                    DiagRow("Rootfs", diagnostics.rootfsDirectory, diagnostics.isRootfsAvailable)
                    DiagRow("Shell", diagnostics.shellPath, diagnostics.isShellAvailable)
                    DiagRow("HOME", diagnostics.homeDirectory, diagnostics.isHomeAvailable)
                    DiagRow("Workspace", diagnostics.workspaceDirectory, diagnostics.isWorkspaceAvailable)
                    DiagRow("Package Manager", diagnostics.packageManager, diagnostics.isPackageManagerAvailable)
                    DiagRow("Node.js", diagnostics.nodeVersion ?: "missing", diagnostics.isNodeInstalled)
                    DiagRow("npm", diagnostics.npmVersion ?: "missing", diagnostics.isNpmInstalled)
                    DiagRow("Python 3", diagnostics.pythonVersion ?: "missing", diagnostics.isPythonInstalled)
                    DiagRow("Git", diagnostics.gitVersion ?: "missing", diagnostics.isGitInstalled)
                    DiagRow("PTY", diagnostics.ptyStatusMessage, diagnostics.ptySupported)
                    DiagRow("Network", if (diagnostics.isInternetAvailable) "Connected" else "Offline", diagnostics.isInternetAvailable)
                }
            } else {
                Text("No diagnostics data available.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun DiagRow(label: String, value: String, isOk: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(
                text = if (isOk) "✓" else "⚠",
                color = if (isOk) Color(0xFF4CAF50) else Color(0xFFFF9800),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        Text(
            text = value.takeLast(25),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
