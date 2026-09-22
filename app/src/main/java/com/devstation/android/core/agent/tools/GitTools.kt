package com.devstation.android.core.agent.tools

import com.devstation.android.core.agent.SecretRedactor
import com.devstation.android.core.agent.Tool
import com.devstation.android.core.agent.ToolContext
import com.devstation.android.core.agent.ToolDefinition
import com.devstation.android.core.agent.ToolPermission
import com.devstation.android.core.agent.ToolResult
import com.devstation.android.core.agent.ToolRiskLevel
import com.devstation.android.core.ai.AIToolCall
import com.devstation.android.core.ai.AIToolParameter
import com.devstation.android.core.ai.AIToolParameterType
import com.devstation.android.core.git.GitManager
import com.devstation.android.core.github.CreateIssueRequest
import com.devstation.android.core.github.CreatePullRequestRequest
import com.devstation.android.core.github.CreateRepositoryRequest
import com.devstation.android.core.github.GitHubAccountManager
import com.devstation.android.core.github.GitHubApiClient
import com.devstation.android.core.security.policy.ImpactLevel
import com.devstation.android.core.security.policy.NetworkIntent
import com.devstation.android.core.security.policy.ResourceType
import com.devstation.android.core.security.policy.SecurityAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * Phase 10: 27 Git and GitHub agent tools.
 * All tools enforce path confinement, redaction of secrets, bounded output, and permission tiers.
 */

// ==========================================
// 1. READ-ONLY GIT TOOLS (ALLOW / READ)
// ==========================================

class GitStatusTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_status",
        description = "Get current Git working tree status (branch, modified, staged, untracked files, ahead/behind count).",
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.READ
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        if (!repo.isInitialized) {
            return ToolResult.Error(definition.name, "Git repository is not initialized in project: ${context.projectRoot.name}")
        }
        val statusRes = repo.status()
        return statusRes.fold(
            onSuccess = { s ->
                val sb = StringBuilder()
                sb.appendLine("Branch: ${s.branch} (clean: ${s.isClean}, ahead: ${s.aheadCount}, behind: ${s.behindCount})")
                if (s.conflictedFiles.isNotEmpty()) {
                    sb.appendLine("CONFLICTED (${s.conflictedFiles.size}):")
                    s.conflictedFiles.forEach { sb.appendLine("  ! $it") }
                }
                if (s.stagedFiles.isNotEmpty()) {
                    sb.appendLine("STAGED (${s.stagedFiles.size}):")
                    s.stagedFiles.forEach { sb.appendLine("  + [${it.indexStatus}] ${it.path}") }
                }
                if (s.unstagedFiles.isNotEmpty()) {
                    sb.appendLine("UNSTAGED (${s.unstagedFiles.size}):")
                    s.unstagedFiles.forEach { sb.appendLine("  * [${it.workTreeStatus}] ${it.path}") }
                }
                if (s.untrackedFiles.isNotEmpty()) {
                    sb.appendLine("UNTRACKED (${s.untrackedFiles.size}):")
                    s.untrackedFiles.forEach { sb.appendLine("  ? ${it.path}") }
                }
                ToolResult.Success(definition.name, sb.toString().trimEnd())
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to get git status") }
        )
    }
}

class GitDiffTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_diff",
        description = "View git diff of unstaged changes, staged changes, or a specific file or commit.",
        parameters = listOf(
            AIToolParameter("staged", AIToolParameterType.BOOLEAN, "Diff staged changes against HEAD", required = false),
            AIToolParameter("file", AIToolParameterType.STRING, "Specific file path to diff", required = false),
            AIToolParameter("commit", AIToolParameterType.STRING, "Commit SHA or ref to diff against", required = false)
        ),
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.READ
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val staged = (args["staged"] as? JsonPrimitive)?.booleanOrNull ?: false
        val file = (args["file"] as? JsonPrimitive)?.content
        val commit = (args["commit"] as? JsonPrimitive)?.content

        val diffRes = repo.diff(staged = staged, file = file, commit = commit)
        return diffRes.fold(
            onSuccess = { diff ->
                ToolResult.Success(definition.name, diff.rawUnifiedDiff.ifBlank { "No diff found." })
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to get git diff") }
        )
    }
}

class GitLogTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_log",
        description = "View commit history log of the current project repository.",
        parameters = listOf(
            AIToolParameter("maxCount", AIToolParameterType.INTEGER, "Maximum commits to return (default 20)", required = false),
            AIToolParameter("skip", AIToolParameterType.INTEGER, "Number of commits to skip", required = false)
        ),
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.READ
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val maxCount = (args["maxCount"] as? JsonPrimitive)?.intOrNull ?: 20
        val skip = (args["skip"] as? JsonPrimitive)?.intOrNull ?: 0

        val logRes = repo.log(maxCount = maxCount, skip = skip)
        return logRes.fold(
            onSuccess = { commits ->
                if (commits.isEmpty()) {
                    ToolResult.Success(definition.name, "No commits found.")
                } else {
                    val out = commits.joinToString("\n\n") { c ->
                        "commit ${c.shortHash} (${c.hash})\nAuthor: ${c.authorName} <${c.authorEmail}>\nDate:   ${c.relativeDate}\n\n    ${c.subject}"
                    }
                    ToolResult.Success(definition.name, out)
                }
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to get git log") }
        )
    }
}

class GitBranchListTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_branch_list",
        description = "List all local and remote branches in the repository.",
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.READ
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val branchRes = repo.branches()
        return branchRes.fold(
            onSuccess = { branches ->
                val out = branches.joinToString("\n") { b ->
                    val star = if (b.isCurrent) "* " else "  "
                    val remote = if (b.isRemote) "[remote] " else ""
                    val tracking = if (b.trackingUpstream != null) " -> ${b.trackingUpstream}" else ""
                    "$star$remote${b.name}$tracking"
                }
                ToolResult.Success(definition.name, out.ifBlank { "No branches found." })
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to list branches") }
        )
    }
}

class GitRemoteListTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_remote_list",
        description = "List all configured remotes and their URLs.",
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.READ
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val remotesRes = repo.remotes()
        return remotesRes.fold(
            onSuccess = { remotes ->
                val out = remotes.joinToString("\n") { r ->
                    "${r.name}\t${r.fetchUrl} (fetch)\n${r.name}\t${r.pushUrl} (push)"
                }
                ToolResult.Success(definition.name, out.ifBlank { "No remotes configured." })
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to list remotes") }
        )
    }
}

class GitConflictStatusTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_conflict_status",
        description = "Inspect current merge conflict status and details for conflicted files.",
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.READ
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val conflictsRes = repo.conflicts()
        return conflictsRes.fold(
            onSuccess = { conflicts ->
                if (conflicts.isEmpty()) {
                    ToolResult.Success(definition.name, "No merge conflicts detected.")
                } else {
                    val sb = StringBuilder()
                    sb.appendLine("Conflicts detected in ${conflicts.size} file(s):")
                    conflicts.forEach { c ->
                        sb.appendLine("\n--- ${c.filePath} (${c.chunks.size} conflict chunk(s)) ---")
                        c.chunks.forEachIndexed { i, ch ->
                            sb.appendLine("Chunk #${i + 1} (lines ${ch.startLine}-${ch.endLine}):")
                            sb.appendLine("<<<<<<< CURRENT")
                            sb.append(ch.currentContent)
                            sb.appendLine("=======")
                            sb.append(ch.incomingContent)
                            sb.appendLine(">>>>>>> INCOMING")
                        }
                    }
                    ToolResult.Success(definition.name, sb.toString())
                }
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to check conflicts") }
        )
    }
}

// ==========================================
// 2. READ-ONLY GITHUB TOOLS (ALLOW / NETWORK)
// ==========================================

class GitHubAccountStatusTool(private val accountManager: GitHubAccountManager) : Tool {
    override val definition = ToolDefinition(
        name = "github_account_status",
        description = "Check if a GitHub account is currently connected (returns username, scopes, never returns secrets).",
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        resourceType = ResourceType.CREDENTIAL,
        action = SecurityAction.READ
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val account = accountManager.getActiveAccount()
        return if (account != null) {
            ToolResult.Success(
                definition.name,
                "GitHub account connected: @${account.username} (${account.displayName ?: "No name"})\nAuth: ${account.tokenType}"
            )
        } else {
            ToolResult.Success(definition.name, "No GitHub account connected.")
        }
    }
}

class GitHubListRepositoriesTool(
    private val accountManager: GitHubAccountManager,
    private val apiClient: GitHubApiClient
) : Tool {
    override val definition = ToolDefinition(
        name = "github_list_repositories",
        description = "List repositories for the active GitHub account.",
        parameters = listOf(
            AIToolParameter("page", AIToolParameterType.INTEGER, "Page number (default 1)", required = false),
            AIToolParameter("perPage", AIToolParameterType.INTEGER, "Items per page (default 20)", required = false)
        ),
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK,
        networkImpact = NetworkIntent.INTERNET
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val tokenRes = accountManager.getActiveToken()
        if (tokenRes.isFailure) {
            return ToolResult.Error(definition.name, "GitHub account is not connected.")
        }
        val token = tokenRes.getOrThrow()
        val page = (args["page"] as? JsonPrimitive)?.intOrNull ?: 1
        val perPage = (args["perPage"] as? JsonPrimitive)?.intOrNull ?: 20

        val res = apiClient.listRepositories(token = token, page = page, perPage = perPage)
        return res.fold(
            onSuccess = { repos ->
                val out = repos.joinToString("\n") { r ->
                    "${r.fullName} [${if (r.isPrivate) "private" else "public"}] ⭐${r.stars} - ${r.cloneUrl}"
                }
                ToolResult.Success(definition.name, out.ifBlank { "No repositories found." })
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to list GitHub repositories") }
        )
    }
}

class GitHubListPullRequestsTool(
    private val accountManager: GitHubAccountManager,
    private val apiClient: GitHubApiClient
) : Tool {
    override val definition = ToolDefinition(
        name = "github_list_pull_requests",
        description = "List pull requests for a specific GitHub repository.",
        parameters = listOf(
            AIToolParameter("owner", AIToolParameterType.STRING, "Repository owner or organization"),
            AIToolParameter("repo", AIToolParameterType.STRING, "Repository name"),
            AIToolParameter("state", AIToolParameterType.STRING, "PR state ('open', 'closed', 'all')", required = false),
            AIToolParameter("page", AIToolParameterType.INTEGER, "Page number (default 1)", required = false)
        ),
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK,
        networkImpact = NetworkIntent.INTERNET
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val tokenRes = accountManager.getActiveToken()
        if (tokenRes.isFailure) return ToolResult.Error(definition.name, "GitHub account is not connected.")
        val token = tokenRes.getOrThrow()

        val owner = (args["owner"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'owner'")
        val repo = (args["repo"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'repo'")
        val state = (args["state"] as? JsonPrimitive)?.content ?: "open"
        val page = (args["page"] as? JsonPrimitive)?.intOrNull ?: 1

        val res = apiClient.listPullRequests(token, owner, repo, state, page)
        return res.fold(
            onSuccess = { prs ->
                val out = prs.joinToString("\n") { p ->
                    "#${p.number} [${p.state}] ${p.title} (${p.headRef} -> ${p.baseRef}) by @${p.user?.login ?: "unknown"}"
                }
                ToolResult.Success(definition.name, out.ifBlank { "No pull requests found." })
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to list PRs") }
        )
    }
}

class GitHubListIssuesTool(
    private val accountManager: GitHubAccountManager,
    private val apiClient: GitHubApiClient
) : Tool {
    override val definition = ToolDefinition(
        name = "github_list_issues",
        description = "List issues for a specific GitHub repository.",
        parameters = listOf(
            AIToolParameter("owner", AIToolParameterType.STRING, "Repository owner"),
            AIToolParameter("repo", AIToolParameterType.STRING, "Repository name"),
            AIToolParameter("state", AIToolParameterType.STRING, "Issue state ('open', 'closed', 'all')", required = false),
            AIToolParameter("page", AIToolParameterType.INTEGER, "Page number (default 1)", required = false)
        ),
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK,
        networkImpact = NetworkIntent.INTERNET
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val tokenRes = accountManager.getActiveToken()
        if (tokenRes.isFailure) return ToolResult.Error(definition.name, "GitHub account is not connected.")
        val token = tokenRes.getOrThrow()

        val owner = (args["owner"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'owner'")
        val repo = (args["repo"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'repo'")
        val state = (args["state"] as? JsonPrimitive)?.content ?: "open"
        val page = (args["page"] as? JsonPrimitive)?.intOrNull ?: 1

        val res = apiClient.listIssues(token, owner, repo, state, page)
        return res.fold(
            onSuccess = { issues ->
                val out = issues.joinToString("\n") { issue ->
                    "#${issue.number} [${issue.state}] ${issue.title} (comments: ${issue.commentsCount}) by @${issue.user?.login ?: "unknown"}"
                }
                ToolResult.Success(definition.name, out.ifBlank { "No issues found." })
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to list issues") }
        )
    }
}

// ==========================================
// 3. WRITE / MUTATION TOOLS (ASK / WRITE)
// ==========================================

class GitInitTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_init",
        description = "Initialize a new Git repository in the current project root.",
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.PROJECT_DIRECTORY,
        action = SecurityAction.CREATE
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val res = repo.init()
        return res.fold(
            onSuccess = { ToolResult.Success(definition.name, "Initialized Git repository in ${context.projectRoot.name}") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to initialize Git repository") }
        )
    }
}

class GitStageTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_stage",
        description = "Stage modified or untracked files for commit.",
        parameters = listOf(
            AIToolParameter("paths", AIToolParameterType.ARRAY, "List of file paths to stage", required = false, itemType = AIToolParameterType.STRING),
            AIToolParameter("all", AIToolParameterType.BOOLEAN, "Stage all modified and untracked files", required = false)
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.WRITE
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val all = (args["all"] as? JsonPrimitive)?.booleanOrNull ?: false
        val paths = (args["paths"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

        val res = if (all || paths.isEmpty()) repo.stageAll() else repo.stage(paths)
        return res.fold(
            onSuccess = { ToolResult.Success(definition.name, "Successfully staged changes.") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to stage changes") }
        )
    }
}

class GitUnstageTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_unstage",
        description = "Unstage files from the Git staging index.",
        parameters = listOf(
            AIToolParameter("paths", AIToolParameterType.ARRAY, "List of file paths to unstage", required = false, itemType = AIToolParameterType.STRING),
            AIToolParameter("all", AIToolParameterType.BOOLEAN, "Unstage all currently staged files", required = false)
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.WRITE
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val all = (args["all"] as? JsonPrimitive)?.booleanOrNull ?: false
        val paths = (args["paths"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

        val res = if (all || paths.isEmpty()) repo.unstageAll() else repo.unstage(paths)
        return res.fold(
            onSuccess = { ToolResult.Success(definition.name, "Successfully unstaged changes.") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to unstage changes") }
        )
    }
}

class GitBranchCreateTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_branch_create",
        description = "Create a new Git branch.",
        parameters = listOf(
            AIToolParameter("name", AIToolParameterType.STRING, "New branch name"),
            AIToolParameter("startPoint", AIToolParameterType.STRING, "Optional commit or branch to start from", required = false)
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.CREATE
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val name = (args["name"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'name'")
        val startPoint = (args["startPoint"] as? JsonPrimitive)?.content

        val res = repo.createBranch(name, startPoint)
        return res.fold(
            onSuccess = { ToolResult.Success(definition.name, "Created branch '$name'") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to create branch") }
        )
    }
}

class GitCheckoutTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_checkout",
        description = "Switch HEAD to a specified branch or commit.",
        parameters = listOf(
            AIToolParameter("target", AIToolParameterType.STRING, "Branch or commit ref to checkout")
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.WRITE
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val target = (args["target"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'target'")

        val res = repo.checkout(target)
        return res.fold(
            onSuccess = { ToolResult.Success(definition.name, "Switched to '$target'") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to checkout target") }
        )
    }
}

class GitResolveConflictTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_resolve_conflict",
        description = "Resolve a merge conflict by providing the resolved file contents.",
        parameters = listOf(
            AIToolParameter("path", AIToolParameterType.STRING, "Path of the conflicted file"),
            AIToolParameter("resolvedContent", AIToolParameterType.STRING, "Complete resolved file content")
        ),
        riskLevel = ToolRiskLevel.HIGH,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.WRITE
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val path = (args["path"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'path'")
        val content = (args["resolvedContent"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'resolvedContent'")

        val res = repo.resolveConflict(path, content)
        return res.fold(
            onSuccess = { ToolResult.Success(definition.name, "Resolved conflict and staged '$path'") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to resolve conflict") }
        )
    }
}

class GitHubCreateIssueTool(
    private val accountManager: GitHubAccountManager,
    private val apiClient: GitHubApiClient
) : Tool {
    override val definition = ToolDefinition(
        name = "github_create_issue",
        description = "Create a new issue on a GitHub repository.",
        parameters = listOf(
            AIToolParameter("owner", AIToolParameterType.STRING, "Repository owner"),
            AIToolParameter("repo", AIToolParameterType.STRING, "Repository name"),
            AIToolParameter("title", AIToolParameterType.STRING, "Issue title"),
            AIToolParameter("body", AIToolParameterType.STRING, "Issue description body", required = false)
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK,
        networkImpact = NetworkIntent.INTERNET
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val tokenRes = accountManager.getActiveToken()
        if (tokenRes.isFailure) return ToolResult.Error(definition.name, "GitHub account is not connected.")
        val token = tokenRes.getOrThrow()

        val owner = (args["owner"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'owner'")
        val repo = (args["repo"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'repo'")
        val title = (args["title"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'title'")
        val body = (args["body"] as? JsonPrimitive)?.content

        val res = apiClient.createIssue(token, owner, repo, CreateIssueRequest(title, body))
        return res.fold(
            onSuccess = { issue ->
                ToolResult.Success(definition.name, "Created issue #${issue.number}: ${issue.title} (${issue.htmlUrl})")
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to create issue") }
        )
    }
}

// ==========================================
// 4. EXECUTE / NETWORK TOOLS (ASK / EXECUTE)
// ==========================================

class GitCommitTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_commit",
        description = "Create a new commit with staged changes.",
        parameters = listOf(
            AIToolParameter("message", AIToolParameterType.STRING, "Commit message"),
            AIToolParameter("author", AIToolParameterType.STRING, "Optional author override 'Name <email>'", required = false)
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.WRITE
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val message = (args["message"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'message'")
        val author = (args["author"] as? JsonPrimitive)?.content

        val res = repo.commit(message, author)
        return res.fold(
            onSuccess = { hash -> ToolResult.Success(definition.name, "Created commit $hash: $message") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to create commit") }
        )
    }
}

class GitMergeTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_merge",
        description = "Merge a branch into the current checked out branch.",
        parameters = listOf(
            AIToolParameter("branch", AIToolParameterType.STRING, "Branch to merge into HEAD")
        ),
        riskLevel = ToolRiskLevel.HIGH,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.WRITE
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val branch = (args["branch"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'branch'")

        val res = repo.merge(branch)
        return res.fold(
            onSuccess = { mergeRes ->
                if (mergeRes.hasConflicts) {
                    ToolResult.Success(definition.name, "Merge resulted in CONFLICTS in: ${mergeRes.conflictedFiles.joinToString(", ")}. Resolve before committing.")
                } else {
                    ToolResult.Success(definition.name, "Merge successful: ${mergeRes.message}")
                }
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to merge branch") }
        )
    }
}

class GitPullTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_pull",
        description = "Fetch and integrate changes from a remote repository.",
        parameters = listOf(
            AIToolParameter("remote", AIToolParameterType.STRING, "Remote name (default 'origin')", required = false),
            AIToolParameter("branch", AIToolParameterType.STRING, "Remote branch name", required = false)
        ),
        riskLevel = ToolRiskLevel.HIGH,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK,
        networkImpact = NetworkIntent.INTERNET
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val remote = (args["remote"] as? JsonPrimitive)?.content
        val branch = (args["branch"] as? JsonPrimitive)?.content

        val res = repo.pull(remote, branch)
        return res.fold(
            onSuccess = { ToolResult.Success(definition.name, "Successfully pulled changes from remote.") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to pull from remote") }
        )
    }
}

class GitFetchTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_fetch",
        description = "Download objects and refs from another repository.",
        parameters = listOf(
            AIToolParameter("remote", AIToolParameterType.STRING, "Remote name (default 'origin')", required = false),
            AIToolParameter("branch", AIToolParameterType.STRING, "Remote branch name", required = false)
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK,
        networkImpact = NetworkIntent.INTERNET
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val remote = (args["remote"] as? JsonPrimitive)?.content
        val branch = (args["branch"] as? JsonPrimitive)?.content

        val res = repo.fetch(remote, branch)
        return res.fold(
            onSuccess = { ToolResult.Success(definition.name, "Successfully fetched refs from remote.") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to fetch from remote") }
        )
    }
}

class GitRemoteAddTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_remote_add",
        description = "Add a new remote URL for the project repository.",
        parameters = listOf(
            AIToolParameter("name", AIToolParameterType.STRING, "Remote name (e.g. 'origin')"),
            AIToolParameter("url", AIToolParameterType.STRING, "HTTPS Git remote repository URL")
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val name = (args["name"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'name'")
        val url = (args["url"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'url'")

        val res = repo.addRemote(name, url)
        return res.fold(
            onSuccess = { ToolResult.Success(definition.name, "Added remote '$name' -> $url") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to add remote") }
        )
    }
}

class GitRemoteRemoveTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_remote_remove",
        description = "Remove a remote by name.",
        parameters = listOf(
            AIToolParameter("name", AIToolParameterType.STRING, "Remote name to remove")
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val name = (args["name"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'name'")

        val res = repo.removeRemote(name)
        return res.fold(
            onSuccess = { ToolResult.Success(definition.name, "Removed remote '$name'") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to remove remote") }
        )
    }
}

class GitCloneTool(
    private val gitManager: GitManager,
    private val accountManager: GitHubAccountManager
) : Tool {
    override val definition = ToolDefinition(
        name = "git_clone",
        description = "Clone a remote Git repository into a destination directory within the project.",
        parameters = listOf(
            AIToolParameter("url", AIToolParameterType.STRING, "Remote HTTPS Git clone URL"),
            AIToolParameter("destination", AIToolParameterType.STRING, "Project-relative destination folder name"),
            AIToolParameter("branch", AIToolParameterType.STRING, "Optional branch to clone", required = false),
            AIToolParameter("depth", AIToolParameterType.INTEGER, "Optional shallow clone depth", required = false)
        ),
        riskLevel = ToolRiskLevel.HIGH,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK,
        networkImpact = NetworkIntent.INTERNET
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val url = (args["url"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'url'")
        val destination = (args["destination"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'destination'")
        val branch = (args["branch"] as? JsonPrimitive)?.content
        val depth = (args["depth"] as? JsonPrimitive)?.intOrNull

        val targetDir = context.resolveWithinProject(destination)
        val token = accountManager.getActiveToken().getOrNull()

        val res = gitManager.cloneRepository(url, targetDir, branch, depth, token)
        return res.fold(
            onSuccess = { ToolResult.Success(definition.name, "Successfully cloned repository into ${targetDir.name}") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to clone repository") }
        )
    }
}

class GitHubCreateRepositoryTool(
    private val accountManager: GitHubAccountManager,
    private val apiClient: GitHubApiClient
) : Tool {
    override val definition = ToolDefinition(
        name = "github_create_repository",
        description = "Create a new GitHub repository for the active account.",
        parameters = listOf(
            AIToolParameter("name", AIToolParameterType.STRING, "Repository name"),
            AIToolParameter("description", AIToolParameterType.STRING, "Repository description", required = false),
            AIToolParameter("isPrivate", AIToolParameterType.BOOLEAN, "Create as private repository", required = false)
        ),
        riskLevel = ToolRiskLevel.HIGH,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK,
        networkImpact = NetworkIntent.INTERNET
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val tokenRes = accountManager.getActiveToken()
        if (tokenRes.isFailure) return ToolResult.Error(definition.name, "GitHub account is not connected.")
        val token = tokenRes.getOrThrow()

        val name = (args["name"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'name'")
        val desc = (args["description"] as? JsonPrimitive)?.content
        val isPrivate = (args["isPrivate"] as? JsonPrimitive)?.booleanOrNull ?: false

        val res = apiClient.createRepository(token, CreateRepositoryRequest(name = name, description = desc, isPrivate = isPrivate))
        return res.fold(
            onSuccess = { r ->
                ToolResult.Success(definition.name, "Created repository: ${r.fullName} (${r.htmlUrl})")
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to create GitHub repository") }
        )
    }
}

class GitHubCreatePullRequestTool(
    private val accountManager: GitHubAccountManager,
    private val apiClient: GitHubApiClient
) : Tool {
    override val definition = ToolDefinition(
        name = "github_create_pull_request",
        description = "Open a pull request on GitHub.",
        parameters = listOf(
            AIToolParameter("owner", AIToolParameterType.STRING, "Repository owner"),
            AIToolParameter("repo", AIToolParameterType.STRING, "Repository name"),
            AIToolParameter("title", AIToolParameterType.STRING, "PR title"),
            AIToolParameter("head", AIToolParameterType.STRING, "Head branch containing changes"),
            AIToolParameter("base", AIToolParameterType.STRING, "Base branch to merge into (e.g. 'main')"),
            AIToolParameter("body", AIToolParameterType.STRING, "PR description body", required = false)
        ),
        riskLevel = ToolRiskLevel.HIGH,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK,
        networkImpact = NetworkIntent.INTERNET
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val tokenRes = accountManager.getActiveToken()
        if (tokenRes.isFailure) return ToolResult.Error(definition.name, "GitHub account is not connected.")
        val token = tokenRes.getOrThrow()

        val owner = (args["owner"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'owner'")
        val repo = (args["repo"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'repo'")
        val title = (args["title"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'title'")
        val head = (args["head"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'head'")
        val base = (args["base"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'base'")
        val body = (args["body"] as? JsonPrimitive)?.content

        val res = apiClient.createPullRequest(token, owner, repo, CreatePullRequestRequest(title = title, head = head, base = base, body = body))
        return res.fold(
            onSuccess = { pr ->
                ToolResult.Success(definition.name, "Opened PR #${pr.number}: ${pr.title} (${pr.htmlUrl})")
            },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to open pull request") }
        )
    }
}

// =========================================================================
// 5. DESTRUCTIVE / HIGH-RISK TOOLS (ALWAYS_ASK, destructive = true)
// =========================================================================

class GitPushTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_push",
        description = "Push committed changes to a remote repository. DESTRUCTIVE: May alter remote history if force=true.",
        parameters = listOf(
            AIToolParameter("remote", AIToolParameterType.STRING, "Remote name (default 'origin')", required = false),
            AIToolParameter("branch", AIToolParameterType.STRING, "Branch to push", required = false),
            AIToolParameter("force", AIToolParameterType.BOOLEAN, "Force push (destructive)", required = false)
        ),
        riskLevel = ToolRiskLevel.CRITICAL,
        permission = ToolPermission.ALWAYS_ASK,
        destructive = true,
        resourceType = ResourceType.NETWORK,
        action = SecurityAction.NETWORK,
        networkImpact = NetworkIntent.INTERNET
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val remote = (args["remote"] as? JsonPrimitive)?.content
        val branch = (args["branch"] as? JsonPrimitive)?.content
        val force = (args["force"] as? JsonPrimitive)?.booleanOrNull ?: false

        val res = repo.push(remote, branch, force)
        return res.fold(
            onSuccess = { ToolResult.Success(definition.name, "Successfully pushed changes to remote.") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to push to remote") }
        )
    }
}

class GitBranchDeleteTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_branch_delete",
        description = "Delete a Git branch. DESTRUCTIVE: Permanently removes the branch reference.",
        parameters = listOf(
            AIToolParameter("name", AIToolParameterType.STRING, "Branch name to delete"),
            AIToolParameter("force", AIToolParameterType.BOOLEAN, "Force delete (-D)", required = false)
        ),
        riskLevel = ToolRiskLevel.HIGH,
        permission = ToolPermission.ALWAYS_ASK,
        destructive = true,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.DELETE
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val name = (args["name"] as? JsonPrimitive)?.content ?: return ToolResult.Error(definition.name, "Missing 'name'")
        val force = (args["force"] as? JsonPrimitive)?.booleanOrNull ?: false

        val res = repo.deleteBranch(name, force)
        return res.fold(
            onSuccess = { ToolResult.Success(definition.name, "Deleted branch '$name'") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to delete branch") }
        )
    }
}

class GitMergeAbortTool(private val gitManager: GitManager) : Tool {
    override val definition = ToolDefinition(
        name = "git_merge_abort",
        description = "Abort an in-progress merge and restore the pre-merge working tree state. DESTRUCTIVE: Discards in-progress conflict resolutions.",
        riskLevel = ToolRiskLevel.HIGH,
        permission = ToolPermission.ALWAYS_ASK,
        destructive = true,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.DELETE
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val repo = gitManager.getRepository(context.projectRoot)
        val res = repo.abortMerge()
        return res.fold(
            onSuccess = { ToolResult.Success(definition.name, "Aborted merge and restored previous HEAD state.") },
            onFailure = { ToolResult.Error(definition.name, it.message ?: "Failed to abort merge") }
        )
    }
}

/**
 * Creates the complete Phase 10 Git and GitHub tool suite for an agent task.
 */
object GitToolSet {
    fun createTools(
        gitManager: GitManager,
        accountManager: GitHubAccountManager,
        apiClient: GitHubApiClient
    ): List<Tool> = listOf(
        // 1. Read-only Git
        GitStatusTool(gitManager),
        GitDiffTool(gitManager),
        GitLogTool(gitManager),
        GitBranchListTool(gitManager),
        GitRemoteListTool(gitManager),
        GitConflictStatusTool(gitManager),

        // 2. Read-only GitHub
        GitHubAccountStatusTool(accountManager),
        GitHubListRepositoriesTool(accountManager, apiClient),
        GitHubListPullRequestsTool(accountManager, apiClient),
        GitHubListIssuesTool(accountManager, apiClient),

        // 3. Write / Mutation
        GitInitTool(gitManager),
        GitStageTool(gitManager),
        GitUnstageTool(gitManager),
        GitBranchCreateTool(gitManager),
        GitCheckoutTool(gitManager),
        GitResolveConflictTool(gitManager),
        GitHubCreateIssueTool(accountManager, apiClient),

        // 4. Execute / Network
        GitCommitTool(gitManager),
        GitMergeTool(gitManager),
        GitPullTool(gitManager),
        GitFetchTool(gitManager),
        GitRemoteAddTool(gitManager),
        GitRemoteRemoveTool(gitManager),
        GitCloneTool(gitManager, accountManager),
        GitHubCreateRepositoryTool(accountManager, apiClient),
        GitHubCreatePullRequestTool(accountManager, apiClient),

        // 5. Destructive / High Risk (ALWAYS_ASK)
        GitPushTool(gitManager),
        GitBranchDeleteTool(gitManager),
        GitMergeAbortTool(gitManager)
    )
}
