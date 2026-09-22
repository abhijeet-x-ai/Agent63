package com.devstation.android.core.agent.tools

import com.devstation.android.core.agent.ToolPermission
import com.devstation.android.core.agent.ToolRiskLevel
import com.devstation.android.core.git.DefaultGitCommandRunner
import com.devstation.android.core.git.DefaultGitManager
import com.devstation.android.core.github.DefaultGitHubAccountManager
import com.devstation.android.core.github.DefaultGitHubApiClient
import com.devstation.android.core.database.GitHubAccountEntity
import com.devstation.android.core.security.SecureCredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentGitToolsSecurityTest {

    private val fakeGitManager = DefaultGitManager(
        commandRunner = DefaultGitCommandRunner(
            testProcessLauncher = { _, _, _ ->
                ProcessBuilder("echo", "test").start()
            }
        )
    )

    private val fakeCredentialStore = object : SecureCredentialStore {
        private val secrets = mutableMapOf<String, String>()
        override fun storeSecret(alias: String, secret: String): Result<Unit> {
            secrets[alias] = secret
            return Result.success(Unit)
        }
        override fun getSecret(alias: String): Result<String?> = Result.success(secrets[alias])
        override fun removeSecret(alias: String): Result<Unit> {
            secrets.remove(alias)
            return Result.success(Unit)
        }
        override fun hasSecret(alias: String): Boolean = secrets.containsKey(alias)
        override fun listAliases(): List<String> = secrets.keys.toList()
    }

    private val fakeDao = object : com.devstation.android.core.database.GitHubAccountDao {
        private val accounts = mutableListOf<com.devstation.android.core.database.GitHubAccountEntity>()
        override fun getActiveAccountFlow(): Flow<com.devstation.android.core.database.GitHubAccountEntity?> =
            flowOf(accounts.find { it.isActive })
        override suspend fun getActiveAccount(): com.devstation.android.core.database.GitHubAccountEntity? =
            accounts.find { it.isActive }
        override suspend fun getAccountById(id: String): com.devstation.android.core.database.GitHubAccountEntity? =
            accounts.find { it.id == id }
        override fun getAllAccountsFlow(): Flow<List<com.devstation.android.core.database.GitHubAccountEntity>> =
            flowOf(accounts)
        override suspend fun insertAccount(account: com.devstation.android.core.database.GitHubAccountEntity) {
            accounts.removeAll { it.id == account.id }
            accounts.add(account)
        }
        override suspend fun updateAccount(account: com.devstation.android.core.database.GitHubAccountEntity) {
            insertAccount(account)
        }
        override suspend fun deactivateAllAccounts() {
            // no-op for stub
        }
        override suspend fun setActiveAccount(id: String) {
            // no-op for stub
        }
        override suspend fun deleteAccountById(id: String) {
            accounts.removeAll { it.id == id }
        }
        override suspend fun deleteAllAccounts() {
            accounts.clear()
        }
    }

    private val fakeAccountManager = DefaultGitHubAccountManager(
        accountDao = fakeDao,
        credentialStore = fakeCredentialStore,
        apiClient = DefaultGitHubApiClient()
    )

    private val fakeApiClient = DefaultGitHubApiClient()

    @Test
    fun testToolRegistryContainsAllGitAndGitHubTools() {
        val tools = GitToolSet.createTools(fakeGitManager, fakeAccountManager, fakeApiClient)
        assertTrue("Expected at least 27 git tools, got ${tools.size}", tools.size >= 27)

        val toolNames = tools.map { it.definition.name }.toSet()

        // Read-only
        assertTrue(toolNames.contains("git_status"))
        assertTrue(toolNames.contains("git_diff"))
        assertTrue(toolNames.contains("git_log"))
        assertTrue(toolNames.contains("git_branch_list"))
        assertTrue(toolNames.contains("git_remote_list"))
        assertTrue(toolNames.contains("git_conflict_status"))
        assertTrue(toolNames.contains("github_account_status"))
        assertTrue(toolNames.contains("github_list_repositories"))
        assertTrue(toolNames.contains("github_list_pull_requests"))
        assertTrue(toolNames.contains("github_list_issues"))

        // Write
        assertTrue(toolNames.contains("git_init"))
        assertTrue(toolNames.contains("git_stage"))
        assertTrue(toolNames.contains("git_unstage"))
        assertTrue(toolNames.contains("git_branch_create"))
        assertTrue(toolNames.contains("git_checkout"))
        assertTrue(toolNames.contains("git_resolve_conflict"))
        assertTrue(toolNames.contains("github_create_issue"))

        // Execute / Network
        assertTrue(toolNames.contains("git_commit"))
        assertTrue(toolNames.contains("git_merge"))
        assertTrue(toolNames.contains("git_pull"))
        assertTrue(toolNames.contains("git_fetch"))
        assertTrue(toolNames.contains("git_remote_add"))
        assertTrue(toolNames.contains("git_remote_remove"))
        assertTrue(toolNames.contains("git_clone"))
        assertTrue(toolNames.contains("github_create_repository"))
        assertTrue(toolNames.contains("github_create_pull_request"))

        // Destructive
        assertTrue(toolNames.contains("git_push"))
        assertTrue(toolNames.contains("git_branch_delete"))
        assertTrue(toolNames.contains("git_merge_abort"))
    }

    @Test
    fun testDestructiveToolsEnforceAlwaysAskPolicy() {
        val tools = GitToolSet.createTools(fakeGitManager, fakeAccountManager, fakeApiClient)

        val pushTool = tools.find { it.definition.name == "git_push" }!!
        assertEquals(ToolPermission.ALWAYS_ASK, pushTool.definition.permission)
        assertEquals(ToolRiskLevel.CRITICAL, pushTool.definition.riskLevel)
        assertTrue(pushTool.definition.destructive)

        val branchDeleteTool = tools.find { it.definition.name == "git_branch_delete" }!!
        assertEquals(ToolPermission.ALWAYS_ASK, branchDeleteTool.definition.permission)
        assertTrue(branchDeleteTool.definition.destructive)

        val mergeAbortTool = tools.find { it.definition.name == "git_merge_abort" }!!
        assertEquals(ToolPermission.ALWAYS_ASK, mergeAbortTool.definition.permission)
        assertTrue(mergeAbortTool.definition.destructive)
    }

    @Test
    fun testReadOnlyToolsEnforceAllowPolicy() {
        val tools = GitToolSet.createTools(fakeGitManager, fakeAccountManager, fakeApiClient)
        val readTools = listOf(
            "git_status", "git_diff", "git_log", "git_branch_list",
            "git_remote_list", "git_conflict_status", "github_account_status"
        )

        for (name in readTools) {
            val tool = tools.find { it.definition.name == name }!!
            assertEquals("$name should be ALLOW", ToolPermission.ALLOW, tool.definition.permission)
            assertEquals("$name should be LOW risk", ToolRiskLevel.LOW, tool.definition.riskLevel)
            assertFalse("$name should not be destructive", tool.definition.destructive)
        }
    }
}
