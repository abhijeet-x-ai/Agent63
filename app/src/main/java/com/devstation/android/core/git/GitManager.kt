package com.devstation.android.core.git

import android.util.Base64
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 10 §1/§2/§8: GitManager coordinates Git repositories across projects,
 * tracks Git engine availability, and executes secure clone operations.
 */
interface GitManager {
    fun checkAvailability(): GitAvailability
    fun getRepository(projectDir: File): GitRepository
    fun getRepositoryForProject(projectId: String, projectDir: File): GitRepository
    suspend fun cloneRepository(
        url: String,
        targetDir: File,
        branch: String? = null,
        depth: Int? = null,
        token: String? = null
    ): Result<GitRepository>
}

class DefaultGitManager(
    private val commandRunner: GitCommandRunner,
    private val securityPolicy: GitSecurityPolicy = GitSecurityPolicy()
) : GitManager {

    private val repositories = ConcurrentHashMap<String, GitRepository>()

    override fun checkAvailability(): GitAvailability {
        return commandRunner.checkAvailability()
    }

    override fun getRepository(projectDir: File): GitRepository {
        val canonical = projectDir.canonicalPath
        return repositories.computeIfAbsent(canonical) {
            DefaultGitRepository(projectDir, commandRunner, securityPolicy)
        }
    }

    override fun getRepositoryForProject(projectId: String, projectDir: File): GitRepository {
        return getRepository(projectDir)
    }

    override suspend fun cloneRepository(
        url: String,
        targetDir: File,
        branch: String?,
        depth: Int?,
        token: String?
    ): Result<GitRepository> {
        // 1. Validate remote URL for security (SSRF, protocol, local file targets)
        val urlValidation = securityPolicy.validateRemoteUrl(url)
        if (urlValidation.isFailure) {
            return Result.failure(urlValidation.exceptionOrNull()!!)
        }

        // 2. Validate target directory
        if (targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true) {
            return Result.failure(IllegalStateException("Target directory already exists and is not empty: ${targetDir.absolutePath}"))
        }

        targetDir.parentFile?.mkdirs()

        // 3. Build clone arguments
        val args = mutableListOf<String>()

        // Inject authorization header if token provided, avoiding writing token to .git/config
        val env = mutableMapOf<String, String>()
        if (!token.isNullOrBlank()) {
            val auth = "x-access-token:$token"
            val basic = Base64.encodeToString(auth.toByteArray(), Base64.NO_WRAP)
            args.add("-c")
            args.add("http.extraHeader=AUTHORIZATION: basic $basic")
        }

        args.add("clone")
        if (branch != null && branch.isNotBlank()) {
            args.add("-b")
            args.add(branch)
        }
        if (depth != null && depth > 0) {
            args.add("--depth")
            args.add(depth.toString())
        }

        args.add(url)
        args.add(targetDir.absolutePath)

        val cloneResult = commandRunner.execute(
            repositoryDir = targetDir.parentFile ?: targetDir,
            args = args,
            environment = env,
            allowHooks = false,
            timeoutMs = 120_000L // 2 min timeout for clone
        )

        return cloneResult.fold(
            onSuccess = { res ->
                if (res.isSuccess && File(targetDir, ".git").exists()) {
                    Result.success(getRepository(targetDir))
                } else {
                    Result.failure(IllegalStateException(res.stderr.ifBlank { "Git clone failed: ${res.stdout}" }))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }
}
