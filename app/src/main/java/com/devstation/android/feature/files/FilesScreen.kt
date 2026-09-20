package com.devstation.android.feature.files

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import com.devstation.android.core.ui.DevStationIcons
import com.devstation.android.feature.editor.model.EditorLanguage
import com.devstation.android.feature.editor.service.EditorLanguageDetector
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devstation.android.core.common.FormatUtils
import com.devstation.android.core.model.FileItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel,
    onOpenFileInEditor: ((String) -> Unit)? = null,
    onOpenTerminal: ((String) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var itemToRename by remember { mutableStateOf<FileItem?>(null) }
    var itemToDelete by remember { mutableStateOf<FileItem?>(null) }
    var itemForMetadata by remember { mutableStateOf<FileItem?>(null) }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissUserMessage()
        }
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { folderName ->
                viewModel.createFolder(folderName)
                showNewFolderDialog = false
            }
        )
    }

    if (showNewFileDialog) {
        NewFileDialog(
            onDismiss = { showNewFileDialog = false },
            onConfirm = { fileName, content ->
                viewModel.createFile(fileName, content)
                showNewFileDialog = false
            }
        )
    }

    itemToRename?.let { item ->
        RenameItemDialog(
            currentName = item.name,
            onDismiss = { itemToRename = null },
            onConfirm = { newName ->
                viewModel.renameItem(item.path, newName)
                itemToRename = null
            }
        )
    }

    itemToDelete?.let { item ->
        DeleteItemDialog(
            itemName = item.name,
            isDirectory = item.isDirectory,
            onDismiss = { itemToDelete = null },
            onConfirm = {
                viewModel.deleteItem(item.path)
                itemToDelete = null
            }
        )
    }

    itemForMetadata?.let { item ->
        FileMetadataDialog(
            fileItem = item,
            onDismiss = { itemForMetadata = null }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Action Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = { viewModel.navigateUp() },
                            enabled = uiState.currentPath != uiState.rootPath
                        ) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Up directory")
                        }

                        // Breadcrumb Navigation
                        LazyRow(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            items(uiState.breadcrumbs) { crumb ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = crumb.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (crumb.path == uiState.currentPath) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (crumb.path == uiState.currentPath) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier
                                            .clickable { viewModel.loadDirectory(crumb.path) }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                    if (crumb != uiState.breadcrumbs.last()) {
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(onClick = { showNewFolderDialog = true }) {
                            Icon(imageVector = Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                        }
                        IconButton(onClick = { showNewFileDialog = true }) {
                            Icon(imageVector = Icons.Default.NoteAdd, contentDescription = "New File")
                        }
                        if (onOpenTerminal != null) {
                            IconButton(onClick = { onOpenTerminal(uiState.currentPath) }) {
                                Icon(imageVector = DevStationIcons.Terminal, contentDescription = "Open in Terminal", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            // File List
            if (uiState.items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("This folder is empty", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Use the buttons above to create files or folders",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(uiState.items, key = { it.path }) { item ->
                        FileRow(
                            item = item,
                            onClick = {
                                if (item.isDirectory) {
                                    viewModel.loadDirectory(item.path)
                                } else {
                                    if (onOpenFileInEditor != null) {
                                        onOpenFileInEditor(item.path)
                                    } else {
                                        itemForMetadata = item
                                    }
                                }
                            },
                            onInspect = { itemForMetadata = item },
                            onRename = { itemToRename = item },
                            onDelete = { itemToDelete = item },
                            onOpenInEditor = { onOpenFileInEditor?.invoke(item.path) },
                            onOpenInTerminal = { onOpenTerminal?.invoke(item.path) }
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
fun FileRow(
    item: FileItem,
    onClick: () -> Unit,
    onInspect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenInEditor: (() -> Unit)? = null,
    onOpenInTerminal: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    val isCode = !item.isDirectory && EditorLanguageDetector.detect(item.name) != EditorLanguage.TEXT

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = when {
                        item.isDirectory -> Icons.Default.Folder
                        isCode -> Icons.Default.Code
                        else -> Icons.Default.Description
                    },
                    contentDescription = null,
                    tint = when {
                        item.isDirectory -> MaterialTheme.colorScheme.primary
                        isCode -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (item.isDirectory) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = FormatUtils.formatBytes(item.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = FormatUtils.formatRelativeTime(item.lastModified),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (!item.isDirectory && onOpenInEditor != null) {
                        DropdownMenuItem(
                            text = { Text("Open in Editor") },
                            leadingIcon = { Icon(Icons.Default.Code, null) },
                            onClick = {
                                showMenu = false
                                onOpenInEditor()
                            }
                        )
                    }
                    if (onOpenInTerminal != null) {
                        DropdownMenuItem(
                            text = { Text("Open in Terminal") },
                            leadingIcon = { Icon(DevStationIcons.Terminal, null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showMenu = false
                                onOpenInTerminal()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Details & Metadata") },
                        leadingIcon = { Icon(Icons.Default.Info, null) },
                        onClick = {
                            showMenu = false
                            onInspect()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun FileMetadataDialog(
    fileItem: FileItem,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (fileItem.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(fileItem.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type: ${if (fileItem.isDirectory) "Directory" else "File (${fileItem.extension.ifBlank { "unknown" }})"}")
                Text("Size: ${FormatUtils.formatBytes(fileItem.sizeBytes)}")
                Text("Last modified: ${FormatUtils.formatDateTime(fileItem.lastModified)}")
                Text("Path: ${fileItem.path}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)

                if (!fileItem.isDirectory) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Open in Editor — Coming in Phase 3",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "The embedded code editor with syntax highlighting, buffer management, and language server support will be introduced in Phase 3 / Phase 4.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
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
fun NewFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var folderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Folder", style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Folder Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { if (folderName.isNotBlank()) onConfirm(folderName.trim()) }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun NewFileDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var fileName by remember { mutableStateOf("") }
    var initialContent by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New File", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("File Name (e.g. main.py, index.js)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = initialContent,
                    onValueChange = { initialContent = it },
                    label = { Text("Initial Content (Optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (fileName.isNotBlank()) onConfirm(fileName.trim(), initialContent) }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun RenameItemDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename", style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { if (newName.isNotBlank()) onConfirm(newName.trim()) }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun DeleteItemDialog(
    itemName: String,
    isDirectory: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${if (isDirectory) "Folder" else "File"}", style = MaterialTheme.typography.titleMedium) },
        text = {
            Text("Are you sure you want to delete '$itemName'? This operation cannot be undone.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
