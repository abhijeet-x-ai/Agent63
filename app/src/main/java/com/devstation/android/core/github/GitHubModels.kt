package com.devstation.android.core.github

/**
 * Phase 10: GitHub Domain Models.
 */
data class GitHubUser(
    val id: Long,
    val login: String,
    val name: String?,
    val avatarUrl: String?,
    val htmlUrl: String,
    val publicRepos: Int = 0,
    val totalPrivateRepos: Int = 0
)

data class GitHubRepository(
    val id: Long,
    val name: String,
    val fullName: String,
    val description: String?,
    val isPrivate: Boolean,
    val htmlUrl: String,
    val cloneUrl: String,
    val defaultBranch: String,
    val stars: Int = 0,
    val forks: Int = 0,
    val openIssues: Int = 0,
    val updatedAt: String = ""
)

data class GitHubPullRequest(
    val id: Long,
    val number: Int,
    val title: String,
    val body: String?,
    val state: String,
    val htmlUrl: String,
    val user: GitHubUser?,
    val headRef: String,
    val baseRef: String,
    val isDraft: Boolean = false,
    val createdAt: String = "",
    val mergedAt: String? = null
)

data class GitHubIssue(
    val id: Long,
    val number: Int,
    val title: String,
    val body: String?,
    val state: String,
    val htmlUrl: String,
    val user: GitHubUser?,
    val commentsCount: Int = 0,
    val createdAt: String = ""
)

data class GitHubAccount(
    val id: String,
    val username: String,
    val displayName: String?,
    val avatarUrl: String?,
    val tokenType: String = "PAT",
    val scopes: List<String> = emptyList(),
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

data class CreateRepositoryRequest(
    val name: String,
    val description: String? = null,
    val isPrivate: Boolean = false,
    val autoInit: Boolean = false
)

data class CreatePullRequestRequest(
    val title: String,
    val head: String,
    val base: String,
    val body: String? = null,
    val draft: Boolean = false
)

data class CreateIssueRequest(
    val title: String,
    val body: String? = null,
    val labels: List<String> = emptyList()
)

data class GitHubRateLimit(
    val limit: Int,
    val remaining: Int,
    val resetEpochSeconds: Long
)
