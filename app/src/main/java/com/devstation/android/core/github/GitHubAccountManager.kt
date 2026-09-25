package com.devstation.android.core.github

import com.devstation.android.core.database.GitHubAccountDao
import com.devstation.android.core.database.GitHubAccountEntity
import com.devstation.android.core.security.SecureCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface GitHubAccountManager {
    fun getActiveAccountFlow(): Flow<GitHubAccount?>
    suspend fun getActiveAccount(): GitHubAccount?
    suspend fun getActiveToken(): Result<String>
    fun getAllAccountsFlow(): Flow<List<GitHubAccount>>
    suspend fun connectAccount(token: String): Result<GitHubAccount>
    suspend fun disconnectAccount(id: String): Result<Unit>
    suspend fun setActiveAccount(id: String): Result<Unit>
}

class DefaultGitHubAccountManager(
    private val accountDao: GitHubAccountDao,
    private val credentialStore: SecureCredentialStore,
    private val apiClient: GitHubApiClient
) : GitHubAccountManager {

    override fun getActiveAccountFlow(): Flow<GitHubAccount?> {
        return accountDao.getActiveAccountFlow().map { entity ->
            entity?.toDomainModel()
        }
    }

    override suspend fun getActiveAccount(): GitHubAccount? = withContext(Dispatchers.IO) {
        accountDao.getActiveAccount()?.toDomainModel()
    }

    override suspend fun getActiveToken(): Result<String> = withContext(Dispatchers.IO) {
        val entity = accountDao.getActiveAccount()
            ?: return@withContext Result.failure(IllegalStateException("No GitHub account connected"))
        
        val secretResult = credentialStore.getSecret(entity.credentialAlias)
        val token = secretResult.getOrNull()
        if (token.isNullOrBlank()) {
            Result.failure(IllegalStateException("GitHub credential secret not found or inaccessible in Keystore"))
        } else {
            Result.success(token)
        }
    }

    override fun getAllAccountsFlow(): Flow<List<GitHubAccount>> {
        return accountDao.getAllAccountsFlow().map { list ->
            list.map { it.toDomainModel() }
        }
    }

    override suspend fun connectAccount(token: String): Result<GitHubAccount> = withContext(Dispatchers.IO) {
        val sanitized = token.trim()
        if (sanitized.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Token cannot be blank"))
        }

        // 1. Verify token by calling GitHub /user endpoint
        val userResult = apiClient.getUser(sanitized)
        if (userResult.isFailure) {
            return@withContext Result.failure(userResult.exceptionOrNull() ?: IllegalStateException("GitHub auth failed"))
        }
        val user = userResult.getOrThrow()

        // 2. Generate Keystore alias for this account
        val alias = "github_token_${user.id}"

        // 3. Store secret in Android Keystore AES256-GCM
        val storeResult = credentialStore.storeSecret(alias, sanitized)
        if (storeResult.isFailure) {
            return@withContext Result.failure(storeResult.exceptionOrNull() ?: IllegalStateException("Keystore write failed"))
        }

        // 4. Deactivate existing accounts and persist new account metadata in Room
        accountDao.deactivateAllAccounts()
        val entity = GitHubAccountEntity(
            id = user.id.toString(),
            username = user.login,
            displayName = user.name,
            avatarUrl = user.avatarUrl,
            credentialAlias = alias,
            tokenType = "PAT",
            scopesCsv = "",
            isActive = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        accountDao.insertAccount(entity)

        Result.success(entity.toDomainModel())
    }

    override suspend fun disconnectAccount(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val entity = accountDao.getAccountById(id)
        if (entity != null) {
            credentialStore.removeSecret(entity.credentialAlias)
            accountDao.deleteAccountById(id)
        }
        Result.success(Unit)
    }

    override suspend fun setActiveAccount(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        accountDao.deactivateAllAccounts()
        accountDao.setActiveAccount(id)
        Result.success(Unit)
    }

    private fun GitHubAccountEntity.toDomainModel(): GitHubAccount {
        return GitHubAccount(
            id = id,
            username = username,
            displayName = displayName,
            avatarUrl = avatarUrl,
            tokenType = tokenType,
            scopes = if (scopesCsv.isBlank()) emptyList() else scopesCsv.split(","),
            isActive = isActive,
            createdAt = createdAt
        )
    }
}
