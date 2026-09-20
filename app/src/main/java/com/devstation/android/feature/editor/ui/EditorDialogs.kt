package com.devstation.android.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devstation.android.core.common.FormatUtils
import com.devstation.android.feature.editor.model.EditorDiagnostics
import com.devstation.android.feature.editor.model.EditorSearchState
import com.devstation.android.feature.editor.model.EditorTab
import com.devstation.android.feature.editor.model.ProjectSearchResult
import com.devstation.android.feature.editor.model.ProjectSearchState
import java.io.File

@Composable
fun UnsavedChangesDialog(
    tab: EditorTab,
    onSaveAndClose: () -> Unit,
    onDiscardAndClose: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Unsaved Changes", style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                "Do you want to save the changes made to '${tab.fileName}' before closing?",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = onSaveAndClose) {
                Text("Save")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDiscardAndClose,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Discard")
                }
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
fun ExternalChangeDialog(
    tab: EditorTab,
    onReload: () -> Unit,
    onKeep: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("File Changed Externally", style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                "The file '${tab.fileName}' has been modified outside DevStation editor (e.g. by Linux shell or terminal).\n\n" +
                        "Do you want to reload the latest content from disk or keep your editor version?",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = onReload) {
                Text("Reload from Disk")
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) {
                Text("Keep Editor Version")
            }
        }
    )
}

@Composable
fun GoToLineDialog(
    maxLines: Int,
    currentLine: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var lineInput by remember { mutableStateOf(currentLine.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to Line", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text("Enter line number (1 to $maxLines):", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = lineInput,
                    onValueChange = { lineInput = it.filter { ch -> ch.isDigit() } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val lineNum = lineInput.toIntOrNull() ?: currentLine
                    onConfirm(lineNum.coerceIn(1, maxLines))
                }
            ) {
                Text("Go")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun EditorDiagnosticsDialog(
    diagnostics: EditorDiagnostics?,
    onDismiss: () -> Unit
) {
    if (diagnostics == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File Diagnostics", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DiagnosticItemRow("File Name", diagnostics.fileName)
                DiagnosticItemRow("File Size", FormatUtils.formatFileSize(diagnostics.fileSizeBytes))
                DiagnosticItemRow("Encoding", diagnostics.encodingName + if (diagnostics.hasBom) " (BOM)" else "")
                DiagnosticItemRow("Line Ending", diagnostics.lineEnding.displayName)
                DiagnosticItemRow("Language", diagnostics.language.displayName)
                DiagnosticItemRow("Total Lines", diagnostics.lineCount.toString())
                DiagnosticItemRow("Cursor Position", "Ln ${diagnostics.cursorLine}, Col ${diagnostics.cursorColumn}")
                DiagnosticItemRow("Status", if (diagnostics.isModified) "Unsaved Changes" else "Clean")
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
private fun DiagnosticItemRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun InFileSearchBar(
    searchState: EditorSearchState,
    onQueryChange: (String) -> Unit,
    onReplaceChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onReplaceCurrent: () -> Unit,
    onReplaceAll: () -> Unit,
    onOptionsChange: (Boolean, Boolean, Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Find Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = searchState.query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Find...", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )

                Text(
                    text = if (searchState.matchCount > 0) "${searchState.currentMatchIndex + 1}/${searchState.matchCount}" else "0/0",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                IconButton(onClick = onPrevious, enabled = searchState.matchCount > 0) {
                    Icon(imageVector = Icons.Default.ChevronLeft, contentDescription = "Previous match")
                }

                IconButton(onClick = onNext, enabled = searchState.matchCount > 0) {
                    Icon(imageVector = Icons.Default.ChevronRight, contentDescription = "Next match")
                }

                IconButton(onClick = onClose) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close search")
                }
            }

            // Replace Row (if in replace mode)
            if (searchState.isReplaceMode) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = searchState.replaceText,
                        onValueChange = onReplaceChange,
                        placeholder = { Text("Replace with...", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )

                    Button(
                        onClick = onReplaceCurrent,
                        enabled = searchState.currentMatch != null,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Replace", fontSize = 11.sp)
                    }

                    Button(
                        onClick = onReplaceAll,
                        enabled = searchState.matchCount > 0,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("All", fontSize = 11.sp)
                    }
                }
            }

            // Options Row (Case, Word, Regex)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                SearchOptionChip(
                    label = "Aa",
                    tooltip = "Case Sensitive",
                    isSelected = searchState.isCaseSensitive,
                    onClick = { onOptionsChange(!searchState.isCaseSensitive, searchState.isWholeWord, searchState.isRegex) }
                )
                SearchOptionChip(
                    label = "\\b",
                    tooltip = "Whole Word",
                    isSelected = searchState.isWholeWord,
                    onClick = { onOptionsChange(searchState.isCaseSensitive, !searchState.isWholeWord, searchState.isRegex) }
                )
                SearchOptionChip(
                    label = ".*",
                    tooltip = "Regular Expression",
                    isSelected = searchState.isRegex,
                    onClick = { onOptionsChange(searchState.isCaseSensitive, searchState.isWholeWord, !searchState.isRegex) }
                )
            }
        }
    }
}

@Composable
private fun SearchOptionChip(
    label: String,
    tooltip: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val textCol = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = textCol
        )
    }
}

@Composable
fun ProjectSearchDialog(
    projectSearchState: ProjectSearchState,
    onSearch: (String, Boolean, Boolean) -> Unit,
    onSelectResult: (String, Int) -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf(projectSearchState.query) }
    var caseSensitive by remember { mutableStateOf(projectSearchState.isCaseSensitive) }
    var wholeWord by remember { mutableStateOf(projectSearchState.isWholeWord) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Find in Project", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search text across files...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onSearch(query, caseSensitive, wholeWord) },
                        enabled = query.isNotBlank() && !projectSearchState.isSearching
                    ) {
                        Text("Find")
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = caseSensitive, onCheckedChange = { caseSensitive = it })
                    Text("Case sensitive", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.width(12.dp))
                    Checkbox(checked = wholeWord, onCheckedChange = { wholeWord = it })
                    Text("Whole word", style = MaterialTheme.typography.labelSmall)
                }

                projectSearchState.searchSummary?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                if (projectSearchState.isSearching) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .padding(top = 8.dp)
                    ) {
                        items(projectSearchState.results) { result ->
                            ProjectSearchResultRow(
                                result = result,
                                onClick = { onSelectResult(result.filePath, result.lineNumber) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ProjectSearchResultRow(
    result: ProjectSearchResult,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "${result.relativePath}:${result.lineNumber}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = result.lineContent,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2
            )
        }
    }
}
