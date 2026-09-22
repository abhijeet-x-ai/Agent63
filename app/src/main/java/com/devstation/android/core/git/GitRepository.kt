package com.devstation.android.core.git

import java.io.File

/**
 * Result of a git merge operation (§13).
 */
data class GitMergeResult(
    val isSuccess: Boolean,
    val isFastForward: Boolean,
    val hasConflicts: Boolean,
    val conflictedFiles: List<String> = emptyList(),
    val message: String
)

/**
 * Phase 10: High-level Git repository facade for a DevStation project.
 * Enforces security policies, path containment, and machine-readable parsing.
 */
interface GitRepository {
    val repositoryDir: File
    val isInitialized: Boolean

    suspend fun init(): Result<Unit>
    suspend fun status(): Result<GitStatus>
    suspend fun diff(staged: Boolean = false, file: String? = null, commit: String? = null): Result<GitDiff>
    suspend fun log(maxCount: Int = 50, skip: Int = 0): Result<List<GitCommit>>
    suspend fun stage(paths: List<String>): Result<Unit>
    suspend fun unstage(paths: List<String>): Result<Unit>
    suspend fun stageAll(): Result<Unit>
    suspend fun unstageAll(): Result<Unit>
    suspend fun commit(message: String, author: String? = null): Result<String>
    suspend fun branches(): Result<List<GitBranch>>
    suspend fun currentBranch(): Result<String>
    suspend fun createBranch(name: String, startPoint: String? = null): Result<Unit>
    suspend fun checkout(target: String, force: Boolean = false): Result<Unit>
    suspend fun deleteBranch(name: String, force: Boolean = false): Result<Unit>
    suspend fun merge(branch: String): Result<GitMergeResult>
    suspend fun abortMerge(): Result<Unit>
    suspend fun conflicts(): Result<List<GitConflict>>
    suspend fun resolveConflict(file: String, resolvedContent: String): Result<Unit>
    suspend fun remotes(): Result<List<GitRemote>>
    suspend fun addRemote(name: String, url: String): Result<Unit>
    suspend fun removeRemote(name: String): Result<Unit>
    suspend fun changeRemoteUrl(name: String, url: String): Result<Unit>
    suspend fun fetch(remote: String? = null, branch: String? = null): Result<Unit>
    suspend fun pull(remote: String? = null, branch: String? = null): Result<Unit>
    suspend fun push(remote: String? = null, branch: String? = null, force: Boolean = false): Result<Unit>
}

class DefaultGitRepository(
    override val repositoryDir: File,
    private val commandRunner: GitCommandRunner,
    private val securityPolicy: GitSecurityPolicy = GitSecurityPolicy()
) : GitRepository {

    override val isInitialized: Boolean
        get() = File(repositoryDir, ".git").exists()

    override suspend fun init(): Result<Unit> {
        val res = commandRunner.execute(repositoryDir, listOf("init"))
        return res.fold(
            onSuccess = { if (it.isSuccess) Result.success(Unit) else Result.failure(IllegalStateException(it.stderr)) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun status(): Result<GitStatus> {
        val res = commandRunner.execute(repositoryDir, listOf("status", "--porcelain=v1", "-b", "-uall"))
        return res.fold(
            onSuccess = {
                if (it.isSuccess) {
                    Result.success(GitStatusParser.parse(it.stdout, securityPolicy))
                } else {
                    Result.failure(IllegalStateException(it.stderr.ifBlank { "git status failed" }))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun diff(staged: Boolean, file: String?, commit: String?): Result<GitDiff> {
        val args = mutableListOf("diff")
        if (staged) args.add("--cached")
        if (commit != null) args.add(commit)
        if (file != null) {
            val valid = securityPolicy.validatePathWithinProject(repositoryDir, file)
            if (valid.isFailure) return Result.failure(valid.exceptionOrNull()!!)
            args.add("--")
            args.add(file)
        }
        val res = commandRunner.execute(repositoryDir, args)
        return res.fold(
            onSuccess = {
                if (it.isSuccess) {
                    Result.success(GitDiffParser.parse(it.stdout))
                } else {
                    Result.failure(IllegalStateException(it.stderr))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun log(maxCount: Int, skip: Int): Result<List<GitCommit>> {
        val boundedCount = maxCount.coerceIn(1, 200)
        val boundedSkip = maxOf(0, skip)
        val args = listOf(
            "log",
            "-n", boundedCount.toString(),
            "--skip=$boundedSkip",
            "--format=${GitLogParser.LOG_FORMAT}"
        )
        val res = commandRunner.execute(repositoryDir, args)
        return res.fold(
            onSuccess = {
                if (it.isSuccess) {
                    Result.success(GitLogParser.parse(it.stdout))
                } else {
                    // Empty repository / no commits yet
                    if (it.stderr.contains("does not have any commits yet") || it.stderr.contains("fatal: your current branch")) {
                        Result.success(emptyList())
                    } else {
                        Result.failure(IllegalStateException(it.stderr))
                    }
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun stage(paths: List<String>): Result<Unit> {
        if (paths.isEmpty()) return Result.success(Unit)
        for (path in paths) {
            val valid = securityPolicy.validatePathWithinProject(repositoryDir, path)
            if (valid.isFailure) return Result.failure(valid.exceptionOrNull()!!)
        }
        val args = listOf("add", "--") + paths
        val res = commandRunner.execute(repositoryDir, args)
        return res.fold(
            onSuccess = { if (it.isSuccess) Result.success(Unit) else Result.failure(IllegalStateException(it.stderr)) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun unstage(paths: List<String>): Result<Unit> {
        if (paths.isEmpty()) return Result.success(Unit)
        for (path in paths) {
            val valid = securityPolicy.validatePathWithinProject(repositoryDir, path)
            if (valid.isFailure) return Result.failure(valid.exceptionOrNull()!!)
        }
        val args = listOf("reset", "HEAD", "--") + paths
        val res = commandRunner.execute(repositoryDir, args)
        return res.fold(
            onSuccess = { if (it.isSuccess) Result.success(Unit) else Result.failure(IllegalStateException(it.stderr)) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun stageAll(): Result<Unit> {
        val res = commandRunner.execute(repositoryDir, listOf("add", "-A"))
        return res.fold(
            onSuccess = { if (it.isSuccess) Result.success(Unit) else Result.failure(IllegalStateException(it.stderr)) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun unstageAll(): Result<Unit> {
        val res = commandRunner.execute(repositoryDir, listOf("reset", "HEAD"))
        return res.fold(
            onSuccess = { if (it.isSuccess) Result.success(Unit) else Result.failure(IllegalStateException(it.stderr)) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun commit(message: String, author: String?): Result<String> {
        if (message.isBlank()) {
            return Result.failure(IllegalArgumentException("Commit message cannot be empty."))
        }
        val args = mutableListOf("commit", "-m", message)
        if (author != null && author.isNotBlank()) {
            args.add("--author=$author")
        }
        val res = commandRunner.execute(repositoryDir, args)
        return res.fold(
            onSuccess = {
                if (it.isSuccess) {
                    // Extract commit hash from git rev-parse HEAD
                    val headRes = commandRunner.execute(repositoryDir, listOf("rev-parse", "HEAD"))
                    val hash = headRes.getOrNull()?.stdout?.trim() ?: "HEAD"
                    Result.success(hash)
                } else {
                    Result.failure(IllegalStateException(it.stderr.ifBlank { it.stdout }))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun branches(): Result<List<GitBranch>> {
        val res = commandRunner.execute(repositoryDir, listOf("branch", "-a", "-v", "--no-color"))
        return res.fold(
            onSuccess = {
                if (it.isSuccess) {
                    Result.success(GitBranchParser.parse(it.stdout))
                } else {
                    Result.failure(IllegalStateException(it.stderr))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun currentBranch(): Result<String> {
        val res = commandRunner.execute(repositoryDir, listOf("branch", "--show-current"))
        return res.fold(
            onSuccess = {
                val branch = it.stdout.trim()
                if (branch.isNotEmpty()) Result.success(branch) else Result.success("HEAD (detached)")
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun createBranch(name: String, startPoint: String?): Result<Unit> {
        val cleanName = name.trim()
        if (cleanName.isBlank() || cleanName.contains("..") || cleanName.contains("~") || cleanName.contains("^")) {
            return Result.failure(IllegalArgumentException("Invalid branch name: '$name'"))
        }
        val args = mutableListOf("branch", cleanName)
        if (startPoint != null && startPoint.isNotBlank()) {
            args.add(startPoint)
        }
        val res = commandRunner.execute(repositoryDir, args)
        return res.fold(
            onSuccess = { if (it.isSuccess) Result.success(Unit) else Result.failure(IllegalStateException(it.stderr)) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun checkout(target: String, force: Boolean): Result<Unit> {
        val cleanTarget = target.trim()
        if (cleanTarget.isBlank()) return Result.failure(IllegalArgumentException("Target cannot be empty."))
        val args = mutableListOf("checkout")
        if (force) args.add("-f")
        args.add(cleanTarget)
        val res = commandRunner.execute(repositoryDir, args)
        return res.fold(
            onSuccess = { if (it.isSuccess) Result.success(Unit) else Result.failure(IllegalStateException(it.stderr)) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun deleteBranch(name: String, force: Boolean): Result<Unit> {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return Result.failure(IllegalArgumentException("Branch name cannot be empty."))
        val flag = if (force) "-D" else "-d"
        val args = listOf("branch", flag, cleanName)
        val res = commandRunner.execute(repositoryDir, args)
        return res.fold(
            onSuccess = { if (it.isSuccess) Result.success(Unit) else Result.failure(IllegalStateException(it.stderr)) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun merge(branch: String): Result<GitMergeResult> {
        val cleanBranch = branch.trim()
        val args = listOf("merge", "--no-commit", cleanBranch)
        val res = commandRunner.execute(repositoryDir, args)
        return res.fold(
            onSuccess = { cmdRes ->
                val stdout = cmdRes.stdout
                val stderr = cmdRes.stderr
                val output = "$stdout\n$stderr"
                val hasConflicts = output.contains("CONFLICT") || output.contains("Automatic merge failed")
                val isFastForward = output.contains("Fast-forward")
                val conflictedFiles = if (hasConflicts) {
                    conflicts().getOrDefault(emptyList()).map { it.filePath }
                } else emptyList()

                Result.success(
                    GitMergeResult(
                        isSuccess = cmdRes.isSuccess && !hasConflicts,
                        isFastForward = isFastForward,
                        hasConflicts = hasConflicts,
                        conflictedFiles = conflictedFiles,
                        message = stdout.ifBlank { stderr }
                    )
                )
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun abortMerge(): Result<Unit> {
        val res = commandRunner.execute(repositoryDir, listOf("merge", "--abort"))
        return res.fold(
            onSuccess = { if (it.isSuccess) Result.success(Unit) else Result.failure(IllegalStateException(it.stderr)) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun conflicts(): Result<List<GitConflict>> {
        val statusRes = status()
        if (statusRes.isFailure) return Result.failure(statusRes.exceptionOrNull()!!)
        val conflictedFileStatuses = statusRes.getOrNull()?.conflicted ?: emptyList()

        val list = mutableListOf<GitConflict>()
        for (fileStatus in conflictedFileStatuses) {
            val file = File(repositoryDir, fileStatus.path)
            if (file.exists()) {
                val content = runCatching { file.readText() }.getOrDefault("")
                list.add(GitConflictParser.parseConflicts(fileStatus.path, content))
            }
        }
        return Result.success(list)
    }

    override suspend fun resolveConflict(file: String, resolvedContent: String): Result<Unit> {
        val valid = securityPolicy.validatePathWithinProject(repositoryDir, file)
        if (valid.isFailure) return Result.failure(valid.exceptionOrNull()!!)
        val targetFile = valid.getOrThrow()

        return runCatching {
            targetFile.writeText(resolvedContent)
            // Stage the resolved file
            stage(listOf(file)).getOrThrow()
        }
    }

    override suspend fun remotes(): Result<List<GitRemote>> {
        val res = commandRunner.execute(repositoryDir, listOf("remote", "-v"))
        return res.fold(
            onSuccess = {
                if (it.isSuccess) {
                    Result.success(GitRemoteParser.parse(it.stdout))
                } else {
                    Result.failure(IllegalStateException(it.stderr))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun addRemote(name: String, url: String): Result<Unit> {
        val validUrl = securityPolicy.validateRemoteUrl(url)
        if (validUrl.isFailure) return Result.failure(validUrl.exceptionOrNull()!!)

        val args = listOf("remote", "add", name.trim(), validUrl.getOrThrow())
        val res = commandRunner.execute(repositoryDir, args)
        return res.fold(
            onSuccess = { if (it.isSuccess) Result.success(Unit) else Result.failure(IllegalStateException(it.stderr)) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun removeRemote(name: String): Result<Unit> {
        val args = listOf("remote", "remove", name.trim())
        val res = commandRunner.execute(repositoryDir, args)
        return res.fold(
            onSuccess = { if (it.isSuccess) Result.success(Unit) else Result.failure(IllegalStateException(it.stderr)) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun changeRemoteUrl(name: String, url: String): Result<Unit> {
        val validUrl = securityPolicy.validateRemoteUrl(url)
        if (validUrl.isFailure) return Result.failure(validUrl.exceptionOrNull()!!)

        val args = listOf("remote", "set-url", name.trim(), validUrl.getOrThrow())
        val res = commandRunner.execute(repositoryDir, args)
        return res.fold(
            onSuccess = { if (it.isSuccess) Result.success(Unit) else Result.failure(IllegalStateException(it.stderr)) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun fetch(remote: String?, branch: String?): Result<Unit> {
        val args = mutableListOf("fetch")
        if (remote != null && remote.isNotBlank()) args.add(remote)
        if (branch != null && branch.isNotBlank()) args.add(branch)
        val res = commandRunner.execute(repositoryDir, args)
        return res.fold(
            onSuccess = { if (it.isSuccess) Result.success(Unit) else Result.failure(IllegalStateException(it.stderr)) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun pull(remote: String?, branch: String?): Result<Unit> {
        val args = mutableListOf("pull")
        if (remote != null && remote.isNotBlank()) args.add(remote)
        if (branch != null && branch.isNotBlank()) args.add(branch)
        val res = commandRunner.execute(repositoryDir, args)
        return res.fold(
            onSuccess = { if (it.isSuccess) Result.success(Unit) else Result.failure(IllegalStateException(it.stderr)) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun push(remote: String?, branch: String?, force: Boolean): Result<Unit> {
        val args = mutableListOf("push")
        if (force) args.add("--force")
        if (remote != null && remote.isNotBlank()) args.add(remote)
        if (branch != null && branch.isNotBlank()) args.add(branch)
        val res = commandRunner.execute(repositoryDir, args)
        return res.fold(
            onSuccess = { if (it.isSuccess) Result.success(Unit) else Result.failure(IllegalStateException(it.stderr)) },
            onFailure = { Result.failure(it) }
        )
    }
}
