package com.devstation.android.feature.editor.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devstation.android.core.common.FormatUtils
import com.devstation.android.core.ui.DevStationIcons
import com.devstation.android.feature.editor.EditorViewModel
import com.devstation.android.feature.editor.service.EditorColorScheme
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToTerminal: (String) -> Unit,
    onNavigateToFiles: (String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val colorScheme = if (MaterialTheme.colorScheme.background.red < 0.5f) EditorColorScheme.Dark else EditorColorScheme.Light

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissUserMessage()
        }
    }

    // Unsaved Changes Dialog
    uiState.pendingCloseTab?.let { tab ->
        UnsavedChangesDialog(
            tab = tab,
            onSaveAndClose = {
                viewModel.saveActiveFile()
                viewModel.closeTab(tab.id, force = true)
            },
            onDiscardAndClose = {
                viewModel.closeTab(tab.id, force = true)
            },
            onCancel = {
                viewModel.dismissPendingCloseDialog()
            }
        )
    }

    // External Modification Dialog
    uiState.externalChangeNotice?.let { tab ->
        ExternalChangeDialog(
            tab = tab,
            onReload = { viewModel.reloadFromDisk(tab.id) },
            onKeep = { viewModel.keepEditorVersion(tab.id) }
        )
    }

    // Go To Line Dialog
    if (uiState.isGoToLineOpen) {
        val maxLines = uiState.activeTab?.document?.lineCount ?: 1
        val curLine = uiState.activeTab?.cursor?.line ?: 1
        GoToLineDialog(
            maxLines = maxLines,
            currentLine = curLine,
            onDismiss = { viewModel.showGoToLine(false) },
            onConfirm = { lineNum -> viewModel.goToLine(lineNum) }
        )
    }

    // Diagnostics Dialog
    if (uiState.isDiagnosticsOpen) {
        EditorDiagnosticsDialog(
            diagnostics = uiState.diagnostics,
            onDismiss = { viewModel.showDiagnostics(false) }
        )
    }

    // Project-Wide Search Dialog
    if (uiState.projectSearchState.isVisible) {
        ProjectSearchDialog(
            projectSearchState = uiState.projectSearchState,
            onSearch = { q, case, word -> viewModel.runProjectSearch(q, case, word) },
            onSelectResult = { path, line ->
                viewModel.closeProjectSearch()
                viewModel.openFile(path, line)
            },
            onClose = { viewModel.closeProjectSearch() }
        )
    }

    // Recovery Snapshot Prompt Dialog
    uiState.recoveryPromptTab?.let { (file, recoveredContent) ->
        AlertDialog(
            onDismissRequest = { viewModel.discardRecovery(file) },
            title = { Text("Unsaved Edits Recovered", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "DevStation found unsaved recovery edits for '${file.name}' from a previous session.\n\n" +
                            "Do you want to restore these changes or discard them?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.restoreRecovery(file, recoveredContent) }) {
                    Text("Restore Changes")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.discardRecovery(file) }) {
                    Text("Discard")
                }
            }
        )
    }

    // Large File Warning Dialog
    uiState.largeFileNotice?.let { file ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissLargeFileDialog() },
            title = { Text("Large File Warning", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "The file '${file.name}' is ${FormatUtils.formatFileSize(file.length())}, exceeding the safe editor limit. Loading extremely large files may degrade memory performance on mobile devices.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissLargeFileDialog() }) {
                    Text("Understood")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }

                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = uiState.projectName.ifBlank { "Editor" },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )

                                if (uiState.hasGitRepository) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer)
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = "git",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp
                                            ),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            uiState.activeTab?.let {
                                Text(
                                    text = it.fileName + if (it.isModified) " *" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(
                            onClick = {
                                val targetPath = uiState.activeTab?.filePath ?: uiState.projectPath
                                onNavigateToTerminal(targetPath)
                            }
                        ) {
                            Icon(
                                imageVector = DevStationIcons.Terminal,
                                contentDescription = "Open in Terminal",
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(onClick = { viewModel.openProjectSearch() }) {
                            Icon(
                                imageVector = Icons.Default.FindInPage,
                                contentDescription = "Search in Project"
                            )
                        }

                        IconButton(onClick = { viewModel.showDiagnostics(true) }) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "File Diagnostics"
                            )
                        }

                        IconButton(
                            onClick = { viewModel.saveActiveFile() },
                            enabled = uiState.activeTab?.isModified == true
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = "Save file",
                                tint = if (uiState.activeTab?.isModified == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }

            // Tab Bar
            if (uiState.hasOpenTabs) {
                EditorTabBar(
                    tabs = uiState.tabs,
                    activeTabId = uiState.activeTabId,
                    onSelectTab = { viewModel.selectTab(it) },
                    onCloseTab = { viewModel.closeTab(it) },
                    onCloseOtherTabs = { viewModel.closeOtherTabs(it) },
                    onCloseAllTabs = { viewModel.closeAllTabs() },
                    onCloseSavedTabs = { viewModel.closeSavedTabs() },
                    onReopenRecentlyClosed = { viewModel.reopenRecentlyClosed() }
                )
            }

            // In-File Search Bar (when active)
            if (uiState.searchState.isVisible) {
                InFileSearchBar(
                    searchState = uiState.searchState,
                    onQueryChange = { viewModel.updateSearchQuery(it) },
                    onReplaceChange = { viewModel.updateReplaceText(it) },
                    onNext = { viewModel.findNext() },
                    onPrevious = { viewModel.findPrevious() },
                    onReplaceCurrent = { viewModel.replaceCurrent() },
                    onReplaceAll = { viewModel.replaceAll() },
                    onOptionsChange = { case, word, regex -> viewModel.setSearchOptions(case, word, regex) },
                    onClose = { viewModel.closeSearch() }
                )
            }

            // Center Content
            val currentTab = uiState.activeTab
            if (currentTab != null) {
                if (currentTab.document.isBinary) {
                    // Binary File Placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Binary File", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "${currentTab.fileName} (${FormatUtils.formatFileSize(currentTab.document.fileSizeBytes)}) cannot be displayed as source code.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                } else {
                    // Code Editor Field
                    Box(modifier = Modifier.weight(1f)) {
                        CodeEditorView(
                            tab = currentTab,
                            settings = uiState.settings,
                            colorScheme = colorScheme,
                            onContentChange = { newContent, offset ->
                                viewModel.updateContent(newContent, offset)
                            },
                            onCursorChange = { offset ->
                                viewModel.updateCursor(offset)
                            },
                            onSave = { viewModel.saveActiveFile() },
                            onUndo = { viewModel.undo() },
                            onRedo = { viewModel.redo() },
                            onFind = { viewModel.openSearch(replaceMode = false) },
                            onReplace = { viewModel.openSearch(replaceMode = true) },
                            onGoToLine = { viewModel.showGoToLine(true) }
                        )
                    }

                    // Mobile Developer Toolbar
                    EditorToolbar(
                        onUndo = { viewModel.undo() },
                        onRedo = { viewModel.redo() },
                        onFind = { viewModel.openSearch(replaceMode = false) },
                        onReplace = { viewModel.openSearch(replaceMode = true) },
                        onGoToLine = { viewModel.showGoToLine(true) },
                        onIndent = { viewModel.indent() },
                        onOutdent = { viewModel.outdent() },
                        onCommentToggle = { viewModel.toggleComment() },
                        onSave = { viewModel.saveActiveFile() },
                        onInsertSymbol = { symbol ->
                            val currentText = currentTab.document.content
                            val offset = currentTab.cursor.selectionStart
                            val autoClosed = viewModel.handleAutoClose(symbol.firstOrNull() ?: ' ', currentText, offset)
                            if (autoClosed != null) {
                                viewModel.updateContent(autoClosed.first, autoClosed.second)
                            } else {
                                val updated = currentText.substring(0, offset) + symbol + currentText.substring(offset)
                                viewModel.updateContent(updated, offset + symbol.length)
                            }
                        }
                    )
                }

                // Status Footer
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ln ${currentTab.cursor.line}, Col ${currentTab.cursor.column}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = currentTab.document.lineEnding.displayName,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = currentTab.document.encoding.name(),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = currentTab.language.displayName,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                // Empty State when no files are open
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No Files Open", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Select a source file from your project to start editing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { onNavigateToFiles(uiState.projectPath, uiState.projectName) }
                        ) {
                            Text("Browse Project Files")
                        }
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
