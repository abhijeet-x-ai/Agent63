package com.devstation.android.feature.github

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.filesystem.ProjectFileSystemManager
import com.devstation.android.core.git.GitManager
import com.devstation.android.core.github.CreateIssueRequest
import com.devstation.android.core.github.CreatePullRequestRequest
import com.devstation.android.core.github.GitHubAccount
import com.devstation.android.core.github.GitHubAccountManager
import com.devstation.android.core.github.GitHubApiClient
import com.devstation.android.core.github.GitHubIssue
import com.devstation.android.core.github.GitHubPullRequest
import com.devstation.android.core.github.GitHubRepository
import com.devstation.android.core.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class GitHubUiState(
    val account: GitHubAccount? = null,
    val isLoading: Boolean = false,
    val repositories: List<GitHubRepository> = emptyList(),
    val pullRequests: List<GitHubPullRequest> = emptyList(),
    val issues: List<GitHubIssue> = emptyList(),
    val selectedRepo: GitHubRepository? = null,
    val selectedTab: Int = 0, // 0 = Repos, 1 = Pull Requests, 2 = Issues
    val cloneDialogOpen: Boolean = false,
    val repoToClone: GitHubRepository? = null,
    val cloneDestination: String = "",
    val cloneBranch: String = "",
    val cloneDepth: Int? = null,
    val userMessage: String? = null,
    val errorMessage: String? = null
)

class GitHubViewModel(
    private val accountManager: GitHubAccountManager,
    private val apiClient: GitHubApiClient,
    private val gitManager: GitManager,
    private val fileSystemManager: ProjectFileSystemManager,
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GitHubUiState())
    val uiState: StateFlow<GitHubUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            accountManager.getActiveAccountFlow().collect { acc ->
                _uiState.update { it.copy(account = acc) }
                if (acc != null) {
                    loadRepositories()
                }
            }
        }
    }

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
    }

    fun connectAccount(token: String) {
        viewModelScope.launch {
            if (token.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Token cannot be blank") }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = accountManager.connectAccount(token)
            res.fold(
                onSuccess = { acc ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            account = acc,
                            userMessage = "Connected as @${acc.username}"
                        )
                    }
                    loadRepositories()
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = err.message ?: "Failed to authenticate with GitHub"
                        )
                    }
                }
            )
        }
    }

    fun disconnectAccount() {
        viewModelScope.launch {
            val acc = _uiState.value.account ?: return@launch
            _uiState.update { it.copy(isLoading = true) }
            accountManager.disconnectAccount(acc.id)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    account = null,
                    repositories = emptyList(),
                    pullRequests = emptyList(),
                    issues = emptyList(),
                    userMessage = "Disconnected from GitHub"
                )
            }
        }
    }

    fun loadRepositories() {
        viewModelScope.launch {
            val tokenRes = accountManager.getActiveToken()
            if (tokenRes.isFailure) return@launch
            val token = tokenRes.getOrThrow()

            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = apiClient.listRepositories(token = token, page = 1, perPage = 50)
            res.fold(
                onSuccess = { repos ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            repositories = repos,
                            selectedRepo = if (it.selectedRepo == null && repos.isNotEmpty()) repos.first() else it.selectedRepo
                        )
                    }
                    val currentRepo = _uiState.value.selectedRepo
                    if (currentRepo != null) {
                        loadPullRequests(currentRepo)
                        loadIssues(currentRepo)
                    }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
            )
        }
    }

    fun selectRepository(repo: GitHubRepository) {
        _uiState.update { it.copy(selectedRepo = repo) }
        loadPullRequests(repo)
        loadIssues(repo)
    }

    fun loadPullRequests(repo: GitHubRepository) {
        viewModelScope.launch {
            val tokenRes = accountManager.getActiveToken()
            if (tokenRes.isFailure) return@launch
            val token = tokenRes.getOrThrow()

            val parts = repo.fullName.split("/")
            if (parts.size != 2) return@launch
            val owner = parts[0]
            val repoName = parts[1]

            val res = apiClient.listPullRequests(token, owner, repoName, "all", 1)
            res.fold(
                onSuccess = { prs -> _uiState.update { it.copy(pullRequests = prs) } },
                onFailure = { err -> _uiState.update { it.copy(errorMessage = err.message) } }
            )
        }
    }

    fun loadIssues(repo: GitHubRepository) {
        viewModelScope.launch {
            val tokenRes = accountManager.getActiveToken()
            if (tokenRes.isFailure) return@launch
            val token = tokenRes.getOrThrow()

            val parts = repo.fullName.split("/")
            if (parts.size != 2) return@launch
            val owner = parts[0]
            val repoName = parts[1]

            val res = apiClient.listIssues(token, owner, repoName, "all", 1)
            res.fold(
                onSuccess = { issues -> _uiState.update { it.copy(issues = issues) } },
                onFailure = { err -> _uiState.update { it.copy(errorMessage = err.message) } }
            )
        }
    }

    fun openCloneDialog(repo: GitHubRepository) {
        _uiState.update {
            it.copy(
                cloneDialogOpen = true,
                repoToClone = repo,
                cloneDestination = repo.name,
                cloneBranch = repo.defaultBranch
            )
        }
    }

    fun closeCloneDialog() {
        _uiState.update { it.copy(cloneDialogOpen = false, repoToClone = null) }
    }

    fun confirmClone(targetName: String, branch: String?, depth: Int?) {
        viewModelScope.launch {
            val repo = _uiState.value.repoToClone ?: return@launch
            _uiState.update { it.copy(isLoading = true, cloneDialogOpen = false) }

            val targetDir = File(fileSystemManager.defaultWorkspaceDir, targetName.trim())
            val token = accountManager.getActiveToken().getOrNull()

            val cloneRes = gitManager.cloneRepository(
                url = repo.cloneUrl,
                targetDir = targetDir,
                branch = branch?.takeIf { it.isNotBlank() },
                depth = depth,
                token = token
            )

            cloneRes.fold(
                onSuccess = {
                    // Register the cloned repository as a DevStation project
                    projectRepository.importExistingFolder(
                        name = targetName.trim(),
                        folderPath = targetDir.absolutePath
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            userMessage = "Cloned ${repo.name} and created project."
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = err.message ?: "Clone failed"
                        )
                    }
                }
            )
        }
    }

    fun createPullRequest(title: String, head: String, base: String, body: String?) {
        viewModelScope.launch {
            val repo = _uiState.value.selectedRepo ?: return@launch
            val tokenRes = accountManager.getActiveToken()
            if (tokenRes.isFailure) return@launch
            val token = tokenRes.getOrThrow()

            val parts = repo.fullName.split("/")
            if (parts.size != 2) return@launch

            _uiState.update { it.copy(isLoading = true) }
            val res = apiClient.createPullRequest(
                token, parts[0], parts[1],
                CreatePullRequestRequest(title = title, head = head, base = base, body = body)
            )
            res.fold(
                onSuccess = { pr ->
                    _uiState.update {
                        it.copy(isLoading = false, userMessage = "Opened PR #${pr.number}: ${pr.title}")
                    }
                    loadPullRequests(repo)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
            )
        }
    }

    fun createIssue(title: String, body: String?) {
        viewModelScope.launch {
            val repo = _uiState.value.selectedRepo ?: return@launch
            val tokenRes = accountManager.getActiveToken()
            if (tokenRes.isFailure) return@launch
            val token = tokenRes.getOrThrow()

            val parts = repo.fullName.split("/")
            if (parts.size != 2) return@launch

            _uiState.update { it.copy(isLoading = true) }
            val res = apiClient.createIssue(
                token, parts[0], parts[1],
                CreateIssueRequest(title = title, body = body)
            )
            res.fold(
                onSuccess = { issue ->
                    _uiState.update {
                        it.copy(isLoading = false, userMessage = "Created issue #${issue.number}: ${issue.title}")
                    }
                    loadIssues(repo)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
            )
        }
    }

    fun dismissUserMessage() {
        _uiState.update { it.copy(userMessage = null, errorMessage = null) }
    }

    companion object {
        fun provideFactory(
            accountManager: GitHubAccountManager,
            apiClient: GitHubApiClient,
            gitManager: GitManager,
            fileSystemManager: ProjectFileSystemManager,
            projectRepository: ProjectRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return GitHubViewModel(
                    accountManager,
                    apiClient,
                    gitManager,
                    fileSystemManager,
                    projectRepository
                ) as T
            }
        }
    }
}
