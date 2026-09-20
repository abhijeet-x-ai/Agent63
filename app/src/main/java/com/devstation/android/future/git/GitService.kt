package com.devstation.android.future.git

import kotlinx.coroutines.flow.Flow

data class GitStatus(
    val branch: String,
    val isClean: Boolean,
    val stagedFiles: List<String>,
    val unstagedFiles: List<String>,
    val untrackedFiles: List<String>
)

/**
 * Extension contract for Phase 10: Git & GitHub Integration.
 */
interface GitService {
    suspend fun status(repoPath: String): Result<GitStatus>
    suspend fun init(repoPath: String): Result<Unit>
    suspend fun clone(remoteUrl: String, destinationPath: String): Result<Unit>
    suspend fun commit(repoPath: String, message: String): Result<String>
    suspend fun push(repoPath: String, remote: String, branch: String): Result<Unit>
    suspend fun pull(repoPath: String, remote: String, branch: String): Result<Unit>
}
