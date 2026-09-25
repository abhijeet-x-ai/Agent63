package com.devstation.android.feature.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devstation.android.core.git.GitConflict
import com.devstation.android.core.git.GitDiff
import com.devstation.android.core.git.GitFileStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
    viewModel: GitViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.userMessage, uiState.errorMessage) {
        uiState.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissUserMessage()
        }
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar("Error: $it")
            viewModel.dismissUserMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Git: ${uiState.projectName}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (uiState.isInitialized) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier
                                    .clickable { viewModel.openBranchSheet() }
                                    .padding(top = 2.dp)
                            ) {
                                Text(
                                    text = "branch: ${uiState.branch}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.isInitialized) {
                        IconButton(onClick = { viewModel.openLogSheet() }) {
                            Icon(Icons.Default.History, contentDescription = "History")
                        }
                        IconButton(onClick = { viewModel.pull() }) {
                            Icon(Icons.Default.CloudDownload, contentDescription = "Pull")
                        }
                        IconButton(onClick = { viewModel.requestPush() }) {
                            Icon(Icons.Default.CloudUpload, contentDescription = "Push")
                        }
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading && uiState.status == null -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                !uiState.isInitialized -> {
                    UninitializedGitView(
                        onInit = { viewModel.initRepository() }
                    )
                }
                else -> {
                    GitRepositoryView(
                        uiState = uiState,
                        onStage = { viewModel.stage(it) },
                        onUnstage = { viewModel.unstage(it) },
                        onStageAll = { viewModel.stageAll() },
                        onUnstageAll = { viewModel.unstageAll() },
                        onOpenCommit = { viewModel.openCommitDialog() },
                        onOpenDiff = { file, staged -> viewModel.openDiff(file, staged) },
                        onOpenConflict = { viewModel.openConflict(it) }
                    )
                }
            }
        }
    }

    // Commit Dialog
    if (uiState.isCommitDialogOpen) {
        CommitDialog(
            message = uiState.commitMessage,
            onMessageChange = { viewModel.setCommitMessage(it) },
            onCommit = { viewModel.commit(it) },
            onDismiss = { viewModel.closeCommitDialog() }
        )
    }

    // Push Confirmation Dialog
    if (uiState.pushConfirmationPending) {
        PushConfirmationDialog(
            onConfirm = { force -> viewModel.confirmPush(force) },
            onDismiss = { viewModel.cancelPush() }
        )
    }

    // Diff Viewer Sheet
    val selectedDiff = uiState.selectedDiff
    if (uiState.isDiffViewerOpen && selectedDiff != null) {
        DiffViewerSheet(
            diff = selectedDiff,
            fileName = uiState.selectedDiffFile ?: "Diff",
            isStaged = uiState.isDiffStaged,
            onDismiss = { viewModel.closeDiff() }
        )
    }

    // Branch Sheet
    if (uiState.isBranchSheetOpen) {
        BranchSheet(
            currentBranch = uiState.branch,
            branches = uiState.branches,
            onCheckout = { viewModel.checkoutBranch(it) },
            onCreateBranch = { viewModel.createBranch(it) },
            onDismiss = { viewModel.closeBranchSheet() }
        )
    }

    // Log Sheet
    if (uiState.isLogSheetOpen) {
        LogSheet(
            commits = uiState.commits,
            onDismiss = { viewModel.closeLogSheet() }
        )
    }

    // Conflict Resolver Sheet
    uiState.selectedConflict?.let { conflict ->
        ConflictResolverSheet(
            conflict = conflict,
            onResolve = { path, resolved -> viewModel.resolveConflict(path, resolved) },
            onDismiss = { viewModel.closeConflict() }
        )
    }
}

@Composable
private fun UninitializedGitView(onInit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CallMerge,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Git Repository",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "This project is not yet tracked with Git version control.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onInit) {
            Text("Initialize Git Repository")
        }
    }
}

@Composable
private fun GitRepositoryView(
    uiState: GitUiState,
    onStage: (String) -> Unit,
    onUnstage: (String) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    onOpenCommit: () -> Unit,
    onOpenDiff: (String?, Boolean) -> Unit,
    onOpenConflict: (GitConflict) -> Unit
) {
    val status = uiState.status
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Controls bar
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (status?.isClean == true) "Working tree clean" else "Changes detected",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (status != null && (status.aheadCount > 0 || status.behindCount > 0)) {
                            Text(
                                text = "↑ ${status.aheadCount} ahead  ↓ ${status.behindCount} behind",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row {
                        if (status?.stagedFiles?.isNotEmpty() == true) {
                            Button(
                                onClick = onOpenCommit,
                                modifier = Modifier.padding(end = 6.dp)
                            ) {
                                Text("Commit")
                            }
                        }
                        if (status?.unstagedFiles?.isNotEmpty() == true || status?.untrackedFiles?.isNotEmpty() == true) {
                            OutlinedButton(onClick = onStageAll) {
                                Text("Stage All")
                            }
                        } else if (status?.stagedFiles?.isNotEmpty() == true) {
                            OutlinedButton(onClick = onUnstageAll) {
                                Text("Unstage All")
                            }
                        }
                    }
                }
            }
        }

        // Conflicts
        if (uiState.conflicts.isNotEmpty()) {
            item {
                SectionHeader("Merge Conflicts (${uiState.conflicts.size})", isError = true)
            }
            items(uiState.conflicts) { conflict ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onOpenConflict(conflict) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = conflict.filePath,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Text(
                            text = "Resolve",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Staged Files
        val staged = status?.stagedFiles.orEmpty()
        if (staged.isNotEmpty()) {
            item {
                SectionHeader("Staged Changes (${staged.size})")
            }
            items(staged) { file ->
                FileItemRow(
                    file = file,
                    actionIcon = Icons.Default.Remove,
                    onAction = { onUnstage(file.path) },
                    onClick = { onOpenDiff(file.path, true) }
                )
            }
        }

        // Unstaged Files
        val unstaged = status?.unstagedFiles.orEmpty()
        if (unstaged.isNotEmpty()) {
            item {
                SectionHeader("Changes (${unstaged.size})")
            }
            items(unstaged) { file ->
                FileItemRow(
                    file = file,
                    actionIcon = Icons.Default.Add,
                    onAction = { onStage(file.path) },
                    onClick = { onOpenDiff(file.path, false) }
                )
            }
        }

        // Untracked Files
        val untracked = status?.untrackedFiles.orEmpty()
        if (untracked.isNotEmpty()) {
            item {
                SectionHeader("Untracked Files (${untracked.size})")
            }
            items(untracked) { file ->
                FileItemRow(
                    file = file,
                    actionIcon = Icons.Default.Add,
                    onAction = { onStage(file.path) },
                    onClick = { onOpenDiff(file.path, false) }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, isError: Boolean = false) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun FileItemRow(
    file: GitFileStatus,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onAction: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = when (file.indexStatus.ifBlank { file.workTreeStatus }) {
                        "A", "?" -> Color(0xFF2E7D32)
                        "M" -> Color(0xFF1565C0)
                        "D" -> Color(0xFFC62828)
                        else -> MaterialTheme.colorScheme.secondary
                    },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = file.indexStatus.ifBlank { file.workTreeStatus },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }

                Text(
                    text = file.path,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }

            IconButton(onClick = onAction) {
                Icon(actionIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun CommitDialog(
    message: String,
    onMessageChange: (String) -> Unit,
    onCommit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Commit Changes") },
        text = {
            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                label = { Text("Commit Message") },
                placeholder = { Text("feat: describe your change...") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5
            )
        },
        confirmButton = {
            Button(
                onClick = { onCommit(message) },
                enabled = message.isNotBlank()
            ) {
                Text("Commit")
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
private fun PushConfirmationDialog(
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var forcePush by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Push to Remote") },
        text = {
            Column {
                Text("Are you sure you want to push local commits to the remote repository?")
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { forcePush = !forcePush }
                ) {
                    Checkbox(checked = forcePush, onCheckedChange = { forcePush = it })
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Force Push (--force)",
                        color = if (forcePush) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (forcePush) {
                    Text(
                        text = "Warning: Force push can overwrite remote history.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 36.dp, top = 2.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(forcePush) },
                colors = if (forcePush) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
            ) {
                Text(if (forcePush) "Force Push" else "Push")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffViewerSheet(
    diff: GitDiff,
    fileName: String,
    isStaged: Boolean,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isStaged) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = if (isStaged) "staged" else "unstaged",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            val scrollState = rememberScrollState()
            val hScrollState = rememberScrollState()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .horizontalScroll(hScrollState)
            ) {
                Column {
                    diff.files.forEach { file ->
                        file.hunks.forEach { hunk ->
                            Text(
                                text = hunk.header,
                                color = Color(0xFF0288D1),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFE1F5FE).copy(alpha = 0.3f))
                                    .padding(vertical = 2.dp)
                            )
                            hunk.lines.forEach { line ->
                                val (bgColor, textColor, prefix) = when (line.type) {
                                    com.devstation.android.core.git.GitDiffLine.LineType.ADDITION -> Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), "+")
                                    com.devstation.android.core.git.GitDiffLine.LineType.DELETION -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828), "-")
                                    else -> Triple(Color.Transparent, MaterialTheme.colorScheme.onSurface, " ")
                                }
                                Text(
                                    text = "$prefix ${line.content}",
                                    color = textColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(bgColor)
                                        .padding(vertical = 1.dp)
                                )
                            }
                        }
                    }
                    if (diff.files.isEmpty()) {
                        Text(
                            text = diff.rawUnifiedDiff.ifBlank { "No changes in this diff." },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchSheet(
    currentBranch: String,
    branches: List<com.devstation.android.core.git.GitBranch>,
    onCheckout: (String) -> Unit,
    onCreateBranch: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Branches",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Button(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New Branch")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(branches) { b ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCheckout(b.name) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (b.isCurrent) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Spacer(modifier = Modifier.width(32.dp))
                            }
                            Text(
                                text = b.name,
                                fontWeight = if (b.isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (b.isRemote) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (b.trackingUpstream != null) {
                            Text(
                                text = "-> ${b.trackingUpstream}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Branch") },
            text = {
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    label = { Text("Branch name") },
                    placeholder = { Text("feature/my-branch") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newBranchName.isNotBlank()) {
                            onCreateBranch(newBranchName.trim())
                            showCreateDialog = false
                        }
                    },
                    enabled = newBranchName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSheet(
    commits: List<com.devstation.android.core.git.GitCommit>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Commit History",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(commits) { commit ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = commit.shortHash,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = commit.relativeDate,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = commit.subject,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${commit.authorName} <${commit.authorEmail}>",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConflictResolverSheet(
    conflict: GitConflict,
    onResolve: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Resolve Conflict: ${conflict.filePath}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Select resolution strategy for this file:",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val resolved = com.devstation.android.core.git.DefaultGitConflictParser.resolve(
                        conflict,
                        com.devstation.android.core.git.ConflictResolutionStrategy.ACCEPT_CURRENT
                    )
                    onResolve(conflict.filePath, resolved)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Accept Current (HEAD / Ours)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val resolved = com.devstation.android.core.git.DefaultGitConflictParser.resolve(
                        conflict,
                        com.devstation.android.core.git.ConflictResolutionStrategy.ACCEPT_INCOMING
                    )
                    onResolve(conflict.filePath, resolved)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Accept Incoming (Theirs)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val resolved = com.devstation.android.core.git.DefaultGitConflictParser.resolve(
                        conflict,
                        com.devstation.android.core.git.ConflictResolutionStrategy.ACCEPT_BOTH
                    )
                    onResolve(conflict.filePath, resolved)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Accept Both (Current then Incoming)")
            }
        }
    }
}
