package com.devstation.android.feature.github

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devstation.android.core.github.GitHubAccount
import com.devstation.android.core.github.GitHubIssue
import com.devstation.android.core.github.GitHubPullRequest
import com.devstation.android.core.github.GitHubRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubScreen(
    viewModel: GitHubViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var tokenInput by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }

    var isCreatePrDialogOpen by remember { mutableStateOf(false) }
    var isCreateIssueDialogOpen by remember { mutableStateOf(false) }

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
                    Text(
                        text = "GitHub Integration",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.account != null) {
                        IconButton(onClick = { viewModel.loadRepositories() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Account Banner / Auth Card
            if (uiState.account == null) {
                ConnectAccountCard(
                    token = tokenInput,
                    showToken = showToken,
                    isLoading = uiState.isLoading,
                    onTokenChange = { tokenInput = it },
                    onToggleShowToken = { showToken = !showToken },
                    onConnect = {
                        viewModel.connectAccount(tokenInput)
                        tokenInput = ""
                    }
                )
            } else {
                val account = uiState.account
                if (account == null) {
                    Text(
                        "Account unavailable. Please reconnect.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                ConnectedAccountCard(
                    account = account,
                    onDisconnect = { viewModel.disconnectAccount() }
                )

                // Tabs for Repos, PRs, Issues
                TabRow(selectedTabIndex = uiState.selectedTab) {
                    Tab(
                        selected = uiState.selectedTab == 0,
                        onClick = { viewModel.selectTab(0) },
                        text = { Text("Repositories (${uiState.repositories.size})") }
                    )
                    Tab(
                        selected = uiState.selectedTab == 1,
                        onClick = { viewModel.selectTab(1) },
                        text = { Text("Pull Requests") }
                    )
                    Tab(
                        selected = uiState.selectedTab == 2,
                        onClick = { viewModel.selectTab(2) },
                        text = { Text("Issues") }
                    )
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else {
                        when (uiState.selectedTab) {
                            0 -> RepositoriesList(
                                repositories = uiState.repositories,
                                onClone = { viewModel.openCloneDialog(it) },
                                onSelect = { viewModel.selectRepository(it) }
                            )
                            1 -> PullRequestsList(
                                pullRequests = uiState.pullRequests,
                                selectedRepo = uiState.selectedRepo,
                                onNewPr = { isCreatePrDialogOpen = true }
                            )
                            2 -> IssuesList(
                                issues = uiState.issues,
                                selectedRepo = uiState.selectedRepo,
                                onNewIssue = { isCreateIssueDialogOpen = true }
                            )
                        }
                    }
                }
                }
            }
        }
    }

    // Clone Repository Dialog
    val repoToClone = uiState.repoToClone
    if (uiState.cloneDialogOpen && repoToClone != null) {
        val repo = repoToClone
        var destFolder by remember(repo) { mutableStateOf(repo.name) }
        var branch by remember(repo) { mutableStateOf(repo.defaultBranch) }
        var shallowDepth by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { viewModel.closeCloneDialog() },
            title = { Text("Clone ${repo.fullName}") },
            text = {
                Column {
                    Text("Clone this repository into your DevStation projects workspace.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = destFolder,
                        onValueChange = { destFolder = it },
                        label = { Text("Project Folder Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = branch,
                        onValueChange = { branch = it },
                        label = { Text("Branch (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = shallowDepth,
                        onValueChange = { shallowDepth = it },
                        label = { Text("Shallow Clone Depth (e.g. 1, optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val depth = shallowDepth.toIntOrNull()
                        viewModel.confirmClone(destFolder, branch, depth)
                    },
                    enabled = destFolder.isNotBlank()
                ) {
                    Text("Clone")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.closeCloneDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Create PR Dialog
    val selectedRepoForPr = uiState.selectedRepo
    if (isCreatePrDialogOpen && selectedRepoForPr != null) {
        var prTitle by remember { mutableStateOf("") }
        var prHead by remember { mutableStateOf("") }
        var prBase by remember(selectedRepoForPr) { mutableStateOf(selectedRepoForPr.defaultBranch) }
        var prBody by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { isCreatePrDialogOpen = false },
            title = { Text("Open Pull Request") },
            text = {
                Column {
                    OutlinedTextField(
                        value = prTitle,
                        onValueChange = { prTitle = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = prHead,
                        onValueChange = { prHead = it },
                        label = { Text("Head Branch (e.g. feature/fix)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = prBase,
                        onValueChange = { prBase = it },
                        label = { Text("Base Branch") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = prBody,
                        onValueChange = { prBody = it },
                        label = { Text("Description (optional)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createPullRequest(prTitle, prHead, prBase, prBody.ifBlank { null })
                        isCreatePrDialogOpen = false
                    },
                    enabled = prTitle.isNotBlank() && prHead.isNotBlank() && prBase.isNotBlank()
                ) {
                    Text("Create PR")
                }
            },
            dismissButton = {
                TextButton(onClick = { isCreatePrDialogOpen = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Create Issue Dialog
    if (isCreateIssueDialogOpen && uiState.selectedRepo != null) {
        var issueTitle by remember { mutableStateOf("") }
        var issueBody by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { isCreateIssueDialogOpen = false },
            title = { Text("New Issue") },
            text = {
                Column {
                    OutlinedTextField(
                        value = issueTitle,
                        onValueChange = { issueTitle = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = issueBody,
                        onValueChange = { issueBody = it },
                        label = { Text("Description (optional)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createIssue(issueTitle, issueBody.ifBlank { null })
                        isCreateIssueDialogOpen = false
                    },
                    enabled = issueTitle.isNotBlank()
                ) {
                    Text("Create Issue")
                }
            },
            dismissButton = {
                TextButton(onClick = { isCreateIssueDialogOpen = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ConnectAccountCard(
    token: String,
    showToken: Boolean,
    isLoading: Boolean,
    onTokenChange: (String) -> Unit,
    onToggleShowToken: () -> Unit,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Connect GitHub Account",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Enter a GitHub Personal Access Token (classic or fine-grained) with 'repo' scope. Tokens are encrypted using Android Keystore AES-256-GCM and never stored in plaintext.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                label = { Text("Personal Access Token") },
                placeholder = { Text("ghp_...") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = onToggleShowToken) {
                        Text(if (showToken) "Hide" else "Show", fontSize = 12.sp)
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onConnect,
                enabled = token.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                } else {
                    Text("Connect Account")
                }
            }
        }
    }
}

@Composable
private fun ConnectedAccountCard(
    account: GitHubAccount,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = account.displayName ?: "@${account.username}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "@${account.username} • ${account.tokenType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            OutlinedButton(onClick = onDisconnect) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun RepositoriesList(
    repositories: List<GitHubRepository>,
    onClone: (GitHubRepository) -> Unit,
    onSelect: (GitHubRepository) -> Unit
) {
    if (repositories.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No repositories found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        items(repositories) { repo ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onSelect(repo) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (repo.isPrivate) Icons.Default.Lock else Icons.Default.Public,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = repo.fullName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (!repo.description.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = repo.description,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFFFB300))
                            Text(" ${repo.stars}", style = MaterialTheme.typography.labelSmall)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Branch: ${repo.defaultBranch}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    IconButton(onClick = { onClone(repo) }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Clone repo", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun PullRequestsList(
    pullRequests: List<GitHubPullRequest>,
    selectedRepo: GitHubRepository?,
    onNewPr: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selectedRepo?.let { "Repo: ${it.fullName}" } ?: "Select a repository",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (selectedRepo != null) {
                Button(onClick = onNewPr) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New PR")
                }
            }
        }

        if (pullRequests.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No pull requests found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            items(pullRequests) { pr ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "#${pr.number} ${pr.title}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (pr.state == "open") Color(0xFF2E7D32) else Color(0xFF757575)
                            ) {
                                Text(
                                    text = pr.state,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${pr.headRef} → ${pr.baseRef} by @${pr.user?.login ?: "unknown"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IssuesList(
    issues: List<GitHubIssue>,
    selectedRepo: GitHubRepository?,
    onNewIssue: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selectedRepo?.let { "Repo: ${it.fullName}" } ?: "Select a repository",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (selectedRepo != null) {
                Button(onClick = onNewIssue) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New Issue")
                }
            }
        }

        if (issues.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No issues found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            items(issues) { issue ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "#${issue.number} ${issue.title}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (issue.state == "open") Color(0xFF2E7D32) else Color(0xFF757575)
                            ) {
                                Text(
                                    text = issue.state,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "by @${issue.user?.login ?: "unknown"} • ${issue.commentsCount} comments",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
