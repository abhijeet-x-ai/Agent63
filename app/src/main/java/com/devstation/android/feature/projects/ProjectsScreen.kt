package com.devstation.android.feature.projects

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.devstation.android.core.model.Project

@Composable
fun ProjectsScreen(
    viewModel: ProjectsViewModel,
    onOpenProjectFiles: (String, String) -> Unit,
    onOpenProjectSettings: (String) -> Unit,
    onOpenTerminal: (String) -> Unit = {},
    onOpenEditor: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var projectToRename by remember { mutableStateOf<Project?>(null) }
    var projectToDelete by remember { mutableStateOf<Project?>(null) }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissUserMessage()
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            defaultLocation = uiState.defaultWorkspacePath,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, location ->
                viewModel.createProject(name, location) { newProj ->
                    onOpenProjectFiles(newProj.localPath, newProj.name)
                }
                showCreateDialog = false
            }
        )
    }

    if (showImportDialog) {
        ImportFolderDialog(
            defaultLocation = uiState.defaultWorkspacePath,
            onDismiss = { showImportDialog = false },
            onConfirm = { path, name ->
                viewModel.importExistingFolder(path, name)
                showImportDialog = false
            }
        )
    }

    projectToRename?.let { project ->
        RenameProjectDialog(
            currentName = project.name,
            onDismiss = { projectToRename = null },
            onConfirm = { newName ->
                viewModel.renameProject(project.id, newName)
                projectToRename = null
            }
        )
    }

    projectToDelete?.let { project ->
        DeleteProjectDialog(
            projectName = project.name,
            onDismiss = { projectToDelete = null },
            onConfirm = { deleteFiles ->
                viewModel.deleteProject(project.id, deleteFiles)
                projectToDelete = null
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header Title & Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Projects",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showImportDialog = true },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Folder", style = MaterialTheme.typography.labelSmall)
                    }

                    Button(
                        onClick = { showCreateDialog = true },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Project", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search Bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                placeholder = { Text("Search local projects...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Project List
            if (uiState.projects.isEmpty()) {
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
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No projects found",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Create a project or link an existing folder",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.projects, key = { it.id }) { project ->
                        ProjectListItem(
                            project = project,
                            onClick = { onOpenProjectFiles(project.localPath, project.name) },
                            onOpenTerminal = { onOpenTerminal(project.localPath) },
                            onOpenEditor = { onOpenEditor(project.localPath) },
                            onTogglePin = { viewModel.togglePin(project.id, !project.isPinned) },
                            onRename = { projectToRename = project },
                            onSettings = { onOpenProjectSettings(project.id) },
                            onDelete = { projectToDelete = project }
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
fun ProjectListItem(
    project: Project,
    onClick: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onOpenEditor: () -> Unit = {},
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onSettings: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (project.isPinned) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "Pinned",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Text(
                        text = project.localPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Size: ${FormatUtils.formatBytes(project.sizeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Updated: ${FormatUtils.formatRelativeTime(project.updatedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onTogglePin) {
                    Icon(
                        imageVector = if (project.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = "Pin",
                        tint = if (project.isPinned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open Files") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                            onClick = {
                                showMenu = false
                                onClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Open Editor") },
                            leadingIcon = { Icon(Icons.Default.Code, null) },
                            onClick = {
                                showMenu = false
                                onOpenEditor()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Open Terminal") },
                            leadingIcon = { Icon(com.devstation.android.core.ui.DevStationIcons.Terminal, null) },
                            onClick = {
                                showMenu = false
                                onOpenTerminal()
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
                            text = { Text("Project Settings") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = {
                                showMenu = false
                                onSettings()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete / Unlink", color = MaterialTheme.colorScheme.error) },
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
}

@Composable
fun CreateProjectDialog(
    defaultLocation: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, location: String) -> Unit
) {
    var projectName by remember { mutableStateOf("") }
    var location by remember { mutableStateOf(defaultLocation) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Project", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = projectName,
                    onValueChange = {
                        projectName = it
                        errorMessage = null
                    },
                    label = { Text("Project Name") },
                    placeholder = { Text("e.g. StoryTimes, MyApi") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Project Location (Local Disk)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                errorMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (projectName.isBlank()) {
                        errorMessage = "Project name cannot be empty"
                    } else {
                        onConfirm(projectName.trim(), location.trim())
                    }
                }
            ) {
                Text("Create Project")
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
fun ImportFolderDialog(
    defaultLocation: String,
    onDismiss: () -> Unit,
    onConfirm: (folderPath: String, name: String) -> Unit
) {
    var folderPath by remember { mutableStateOf("") }
    var aliasName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Existing Folder", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Link a folder that already exists on device storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = folderPath,
                    onValueChange = {
                        folderPath = it
                        errorMessage = null
                    },
                    label = { Text("Folder Path") },
                    placeholder = { Text("/storage/emulated/0/...") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = aliasName,
                    onValueChange = { aliasName = it },
                    label = { Text("Project Name (Optional)") },
                    placeholder = { Text("Defaults to folder name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                errorMessage?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (folderPath.isBlank()) {
                        errorMessage = "Folder path cannot be empty"
                    } else {
                        onConfirm(folderPath.trim(), aliasName.trim())
                    }
                }
            ) {
                Text("Link Folder")
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
fun RenameProjectDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Project", style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newName.isNotBlank()) onConfirm(newName.trim())
                }
            ) {
                Text("Rename")
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
fun DeleteProjectDialog(
    projectName: String,
    onDismiss: () -> Unit,
    onConfirm: (deleteFiles: Boolean) -> Unit
) {
    var deleteFilesFromDisk by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Project", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Are you sure you want to remove '$projectName' from DevStation workspace?",
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { deleteFilesFromDisk = !deleteFilesFromDisk }
                ) {
                    Checkbox(
                        checked = deleteFilesFromDisk,
                        onCheckedChange = { deleteFilesFromDisk = it }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Also delete files from device storage permanently",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(deleteFilesFromDisk) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
