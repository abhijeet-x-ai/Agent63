package com.devstation.android.feature.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.git.GitAvailability
import com.devstation.android.core.git.GitBranch
import com.devstation.android.core.git.GitCommit
import com.devstation.android.core.git.GitConflict
import com.devstation.android.core.git.GitDiff
import com.devstation.android.core.git.GitManager
import com.devstation.android.core.git.GitRepository
import com.devstation.android.core.git.GitStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class GitUiState(
    val projectPath: String = "",
    val projectName: String = "",
    val isLoading: Boolean = false,
    val isInitialized: Boolean = false,
    val gitAvailability: GitAvailability = GitAvailability.RuntimeUnavailable,
    val branch: String = "HEAD",
    val branches: List<GitBranch> = emptyList(),
    val status: GitStatus? = null,
    val selectedDiff: GitDiff? = null,
    val selectedDiffFile: String? = null,
    val isDiffStaged: Boolean = false,
    val isDiffViewerOpen: Boolean = false,
    val commits: List<GitCommit> = emptyList(),
    val conflicts: List<GitConflict> = emptyList(),
    val selectedConflict: GitConflict? = null,
    val isBranchSheetOpen: Boolean = false,
    val isCommitDialogOpen: Boolean = false,
    val isLogSheetOpen: Boolean = false,
    val commitMessage: String = "",
    val pushConfirmationPending: Boolean = false,
    val userMessage: String? = null,
    val errorMessage: String? = null
)

class GitViewModel(
    private val gitManager: GitManager,
    private val projectPath: String,
    private val projectName: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        GitUiState(
            projectPath = projectPath,
            projectName = projectName,
            gitAvailability = gitManager.checkAvailability()
        )
    )
    val uiState: StateFlow<GitUiState> = _uiState.asStateFlow()

    private val projectDir = File(projectPath)
    private val repo: GitRepository
        get() = gitManager.getRepository(projectDir)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val availability = gitManager.checkAvailability()
            val initialized = repo.isInitialized

            if (!initialized) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isInitialized = false,
                        gitAvailability = availability
                    )
                }
                return@launch
            }

            val statusResult = repo.status()
            val branchesResult = repo.branches()
            val conflictsResult = repo.conflicts()

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    isInitialized = true,
                    gitAvailability = availability,
                    status = statusResult.getOrNull(),
                    branch = statusResult.getOrNull()?.branch ?: "HEAD",
                    branches = branchesResult.getOrDefault(emptyList()),
                    conflicts = conflictsResult.getOrDefault(emptyList()),
                    errorMessage = statusResult.exceptionOrNull()?.message
                )
            }
        }
    }

    fun initRepository() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = repo.init()
            res.fold(
                onSuccess = {
                    _uiState.update { it.copy(userMessage = "Git repository initialized.") }
                    refresh()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Failed to initialize Git") }
                }
            )
        }
    }

    fun stage(path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repo.stage(listOf(path)).fold(
                onSuccess = { refresh() },
                onFailure = { err -> _uiState.update { it.copy(isLoading = false, errorMessage = err.message) } }
            )
        }
    }

    fun unstage(path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repo.unstage(listOf(path)).fold(
                onSuccess = { refresh() },
                onFailure = { err -> _uiState.update { it.copy(isLoading = false, errorMessage = err.message) } }
            )
        }
    }

    fun stageAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repo.stageAll().fold(
                onSuccess = { refresh() },
                onFailure = { err -> _uiState.update { it.copy(isLoading = false, errorMessage = err.message) } }
            )
        }
    }

    fun unstageAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repo.unstageAll().fold(
                onSuccess = { refresh() },
                onFailure = { err -> _uiState.update { it.copy(isLoading = false, errorMessage = err.message) } }
            )
        }
    }

    fun openDiff(file: String?, staged: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val diffRes = repo.diff(staged = staged, file = file)
            diffRes.fold(
                onSuccess = { diff ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            selectedDiff = diff,
                            selectedDiffFile = file,
                            isDiffStaged = staged,
                            isDiffViewerOpen = true
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
            )
        }
    }

    fun closeDiff() {
        _uiState.update { it.copy(isDiffViewerOpen = false, selectedDiff = null, selectedDiffFile = null) }
    }

    fun setCommitMessage(msg: String) {
        _uiState.update { it.copy(commitMessage = msg) }
    }

    fun openCommitDialog() {
        _uiState.update { it.copy(isCommitDialogOpen = true) }
    }

    fun closeCommitDialog() {
        _uiState.update { it.copy(isCommitDialogOpen = false) }
    }

    fun commit(message: String) {
        viewModelScope.launch {
            if (message.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Commit message cannot be empty") }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true, isCommitDialogOpen = false) }
            repo.commit(message).fold(
                onSuccess = { hash ->
                    _uiState.update { it.copy(commitMessage = "", userMessage = "Committed $hash") }
                    refresh()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
            )
        }
    }

    fun openBranchSheet() {
        _uiState.update { it.copy(isBranchSheetOpen = true) }
    }

    fun closeBranchSheet() {
        _uiState.update { it.copy(isBranchSheetOpen = false) }
    }

    fun checkoutBranch(branch: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isBranchSheetOpen = false) }
            repo.checkout(branch).fold(
                onSuccess = {
                    _uiState.update { it.copy(userMessage = "Switched to branch '$branch'") }
                    refresh()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
            )
        }
    }

    fun createBranch(name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isBranchSheetOpen = false) }
            repo.createBranch(name).fold(
                onSuccess = {
                    _uiState.update { it.copy(userMessage = "Created branch '$name'") }
                    refresh()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
            )
        }
    }

    fun openLogSheet() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repo.log(50).fold(
                onSuccess = { commits ->
                    _uiState.update { it.copy(isLoading = false, commits = commits, isLogSheetOpen = true) }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
            )
        }
    }

    fun closeLogSheet() {
        _uiState.update { it.copy(isLogSheetOpen = false) }
    }

    fun fetch() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repo.fetch().fold(
                onSuccess = {
                    _uiState.update { it.copy(userMessage = "Fetch completed.") }
                    refresh()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
            )
        }
    }

    fun pull() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repo.pull().fold(
                onSuccess = {
                    _uiState.update { it.copy(userMessage = "Pull completed.") }
                    refresh()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
            )
        }
    }

    fun requestPush() {
        _uiState.update { it.copy(pushConfirmationPending = true) }
    }

    fun cancelPush() {
        _uiState.update { it.copy(pushConfirmationPending = false) }
    }

    fun confirmPush(force: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, pushConfirmationPending = false) }
            repo.push(force = force).fold(
                onSuccess = {
                    _uiState.update { it.copy(userMessage = "Pushed changes to remote.") }
                    refresh()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
            )
        }
    }

    fun openConflict(conflict: GitConflict) {
        _uiState.update { it.copy(selectedConflict = conflict) }
    }

    fun closeConflict() {
        _uiState.update { it.copy(selectedConflict = null) }
    }

    fun resolveConflict(file: String, resolvedContent: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedConflict = null) }
            repo.resolveConflict(file, resolvedContent).fold(
                onSuccess = {
                    _uiState.update { it.copy(userMessage = "Resolved conflict for '$file'") }
                    refresh()
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
            gitManager: GitManager,
            projectPath: String,
            projectName: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return GitViewModel(gitManager, projectPath, projectName) as T
            }
        }
    }
}
