package com.devstation.android.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devstation.android.feature.terminal.components.TerminalConsole
import com.devstation.android.feature.terminal.components.TerminalKeyboardBar
import com.devstation.android.feature.terminal.components.TerminalSessionTabs
import com.devstation.android.future.terminal.AnsiParser
import com.devstation.android.future.terminal.TerminalConfig
import com.devstation.android.future.terminal.TerminalState
import java.io.File

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onNavigateToLinuxRuntime: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showMenu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showShellInfoDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissUserMessage()
        }
    }

    if (showShellInfoDialog) {
        AlertDialog(
            onDismissRequest = { showShellInfoDialog = false },
            title = { Text("Shell Information", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Shell: ${uiState.shellInfo.name}", fontWeight = FontWeight.Bold)
                    Text("Executable Path: ${uiState.shellInfo.path}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text("Status: ${if (uiState.state == TerminalState.RUNNING) "Connected" else uiState.state.name}")
                    Text("PTY Driver: Standard Pipe Bridge (NDK PTY planned for Phase 3)")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "DevStation automatically detects and connects to the native Android shell without assuming a desktop environment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showShellInfoDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showSettingsDialog) {
        TerminalSettingsDialog(
            currentConfig = uiState.config,
            onDismiss = { showSettingsDialog = false },
            onSave = { newConfig ->
                viewModel.updateConfig(newConfig)
                showSettingsDialog = false
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Title and Working Directory
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Terminal",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        val displayDir = if (uiState.currentWorkingDir.isNotBlank()) {
                            File(uiState.currentWorkingDir).name.ifBlank { uiState.currentWorkingDir }
                        } else "~"

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = displayDir,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                    }

                    // Right: Shell Badge and Actions Menu
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Shell Status Badge
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { showShellInfoDialog = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val dotColor = when (uiState.state) {
                                TerminalState.RUNNING -> MaterialTheme.colorScheme.secondary
                                TerminalState.STARTING -> MaterialTheme.colorScheme.tertiary
                                TerminalState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                                TerminalState.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = uiState.shellInfo.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp
                            )
                        }

                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Terminal Options")
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy Output") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                onClick = {
                                    showMenu = false
                                    val fullText = uiState.outputLines.joinToString("\n") { AnsiParser.stripAnsi(it.text) }
                                    clipboardManager.setText(AnnotatedString(fullText))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Terminal") },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.clearBuffer()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Restart Session") },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.restartActiveSession()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Stop Process") },
                                leadingIcon = { Icon(Icons.Default.Stop, null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.stopActiveSession()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Terminal Settings") },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = {
                                    showMenu = false
                                    showSettingsDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Linux Environment") },
                                leadingIcon = { Icon(Icons.Default.Build, null) },
                                onClick = {
                                    showMenu = false
                                    onNavigateToLinuxRuntime()
                                }
                            )
                        }
                    }
                }
            }

            // Runtime Selector Bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val isAndroid = uiState.selectedRuntime == com.devstation.android.future.runtime.RuntimeType.ANDROID_SHELL
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isAndroid) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            modifier = Modifier.clickable {
                                viewModel.selectRuntime(com.devstation.android.future.runtime.RuntimeType.ANDROID_SHELL)
                            }
                        ) {
                            Text(
                                text = "Android Shell",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isAndroid) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontWeight = if (isAndroid) FontWeight.Bold else FontWeight.Normal
                            )
                        }

                        val isLinux = uiState.selectedRuntime == com.devstation.android.future.runtime.RuntimeType.LINUX_USERSPACE
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isLinux) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            modifier = Modifier.clickable {
                                viewModel.selectRuntime(com.devstation.android.future.runtime.RuntimeType.LINUX_USERSPACE)
                            }
                        ) {
                            Text(
                                text = "Linux Environment",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isLinux) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontWeight = if (isLinux) FontWeight.Bold else FontWeight.Normal
                            )
                        }

                        Surface(shape = RoundedCornerShape(4.dp), color = Color.Transparent) {
                            Text(
                                text = "Remote (Phase 12)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    TextButton(
                        onClick = onNavigateToLinuxRuntime,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text("Runtime", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Linux Not Installed Warning Banner
            if (uiState.selectedRuntime == com.devstation.android.future.runtime.RuntimeType.LINUX_USERSPACE && !uiState.isLinuxInstalled) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Linux runtime is not installed yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Button(
                            onClick = onNavigateToLinuxRuntime,
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Install", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Session Tabs
            TerminalSessionTabs(
                sessions = uiState.sessions,
                activeSessionId = uiState.activeSession?.id,
                onSelectSession = { viewModel.selectSession(it) },
                onCloseSession = { viewModel.closeSession(it) },
                onNewSession = { viewModel.createNewSession() }
            )

            // Terminal Output Console
            TerminalConsole(
                lines = uiState.outputLines,
                fontSizeSp = uiState.config.fontSizeSp,
                autoScrollEnabled = uiState.isAutoScroll,
                modifier = Modifier.weight(1f)
            )

            // Mobile Keyboard Helper Bar
            TerminalKeyboardBar(
                onEscape = { viewModel.sendEscape() },
                onTab = { viewModel.sendTab() },
                onCtrlC = { viewModel.sendCtrlC() },
                onCtrlD = { viewModel.sendCtrlD() },
                onCtrlL = { viewModel.sendCtrlL() },
                onHistoryUp = { viewModel.navigateHistoryUp() },
                onHistoryDown = { viewModel.navigateHistoryDown() },
                onInsertSymbol = { sym ->
                    viewModel.onInputChange(uiState.inputBuffer + sym)
                }
            )

            // Bottom Command Input Field
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$ ",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp
                    )

                    OutlinedTextField(
                        value = uiState.inputBuffer,
                        onValueChange = { viewModel.onInputChange(it) },
                        placeholder = {
                            Text(
                                "Enter command (e.g. ls, pwd, whoami)...",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = { viewModel.executeCurrentCommand() }
                        )
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = { viewModel.executeCurrentCommand() },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Run command",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun TerminalSettingsDialog(
    currentConfig: TerminalConfig,
    onDismiss: () -> Unit,
    onSave: (TerminalConfig) -> Unit
) {
    var fontSize by remember { mutableStateOf(currentConfig.fontSizeSp.toFloat()) }
    var autoScroll by remember { mutableStateOf(currentConfig.autoScroll) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Terminal Settings", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Font Size", style = MaterialTheme.typography.bodyMedium)
                        Text("${fontSize.toInt()} sp", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = fontSize,
                        onValueChange = { fontSize = it },
                        valueRange = 9f..20f,
                        steps = 10
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Scroll on Output", style = MaterialTheme.typography.bodyMedium)
                        Text("Automatically scroll to bottom when new text arrives", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = autoScroll,
                        onCheckedChange = { autoScroll = it }
                    )
                }

                Column {
                    Text("Scrollback Buffer Limit", style = MaterialTheme.typography.bodyMedium)
                    Text("Maximum 5,000 lines (bounded buffer prevents memory leaks)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    currentConfig.copy(
                        fontSizeSp = fontSize.toInt(),
                        autoScroll = autoScroll
                    )
                )
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
