package com.devstation.android.core.security

import com.devstation.android.core.agent.AgentTestDispatchers
import com.devstation.android.core.agent.ApprovalBroker
import com.devstation.android.core.agent.PermissionManager
import com.devstation.android.core.agent.PermissionScope
import com.devstation.android.core.agent.ToolArgumentValidator
import com.devstation.android.core.agent.ToolDefinition
import com.devstation.android.core.agent.ToolPermission
import com.devstation.android.core.agent.createTempProject
import com.devstation.android.core.agent.deleteTempProject
import com.devstation.android.core.ai.AIToolParameter
import com.devstation.android.core.ai.AIToolParameterType
import com.devstation.android.core.security.policy.AuditDecision
import com.devstation.android.core.security.policy.InMemorySecurityAuditStore
import com.devstation.android.core.security.policy.ResourceType
import com.devstation.android.core.security.policy.SecurityAction
import com.devstation.android.core.security.policy.SecurityAuditLogger
import com.devstation.android.core.security.policy.SecurityGrantLookup
import com.devstation.android.core.security.policy.SecurityOutcomeType
import com.devstation.android.core.security.policy.SecurityPolicyEngine
import com.devstation.android.core.security.policy.SecurityPolicyRepository
import com.devstation.android.core.security.policy.SecurityRequest
import com.devstation.android.core.security.policy.StaticSecurityPolicyProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Phase 7 §48/§49: the attack suite. Each test states the attack and asserts that it fails safely.
 */
class SecurityAttackSuiteTest {

    private lateinit var root: File
    private lateinit var otherProjectRoot: File

    @Before
    fun setUp() {
        root = createTempProject(mapOf("src/App.kt" to "fun main() {}\n"))
        otherProjectRoot = createTempProject(mapOf("src/Other.kt" to "// other\n"))
    }

    @After
    fun tearDown() {
        deleteTempProject(root)
        deleteTempProject(otherProjectRoot)
    }

    // ---- identity mismatch ----

    private fun engineWithGrants(permissions: PermissionManager) = SecurityPolicyEngine(
        policyProvider = StaticSecurityPolicyProvider(),
        grants = SecurityGrantLookup { scope, tool, taskId, sessionId, projectId ->
            permissions.hasGrant(scope, tool, taskId, sessionId, projectId)
        }
    )

    private fun writeRequest(
        taskId: String = "task-A",
        sessionId: String = "session-A",
        projectId: String = "project-A"
    ) = SecurityRequest(
        projectId = projectId,
        projectRoot = root,
        taskId = taskId,
        sessionId = sessionId,
        toolName = "write_file",
        action = SecurityAction.WRITE,
        resourceType = ResourceType.PROJECT_FILE,
        resource = "src/App.kt",
        declaredPermission = ToolPermission.ASK
    )

    @Test
    fun `task mismatch a grant for another task is not honoured`() = runBlocking {
        val permissions = PermissionManager(ApprovalBroker())
        permissions.grantForTask("task-B", "write_file")
        val decision = engineWithGrants(permissions).authorize(writeRequest(taskId = "task-A"), true)
        assertEquals(SecurityOutcomeType.ASK, decision.type)
    }

    @Test
    fun `session mismatch a grant from another session is not honoured`() = runBlocking {
        val permissions = PermissionManager(ApprovalBroker())
        permissions.grantForSession("session-B", "write_file")
        val decision = engineWithGrants(permissions).authorize(writeRequest(sessionId = "session-A"), true)
        assertEquals(SecurityOutcomeType.ASK, decision.type)
    }

    @Test
    fun `project mismatch a grant for another project is not honoured`() = runBlocking {
        val permissions = PermissionManager(ApprovalBroker())
        permissions.grantForProject("project-B", "write_file")
        val decision = engineWithGrants(permissions).authorize(writeRequest(projectId = "project-A"), true)
        assertEquals(SecurityOutcomeType.ASK, decision.type)
    }

    @Test
    fun `a matching grant is honoured`() = runBlocking {
        val permissions = PermissionManager(ApprovalBroker())
        permissions.grantForTask("task-A", "write_file")
        val decision = engineWithGrants(permissions).authorize(writeRequest(), true)
        assertTrue(decision.allowed)
    }

    @Test
    fun `a request for another project's file is denied even with a valid grant`() = runBlocking {
        val permissions = PermissionManager(ApprovalBroker())
        permissions.grantForTask("task-A", "write_file")
        val request = writeRequest().copy(resource = File(otherProjectRoot, "src/Other.kt").absolutePath)
        val decision = engineWithGrants(permissions).authorize(request, true)
        assertEquals(SecurityOutcomeType.DENY, decision.type)
    }

    // ---- expiry and revocation ----

    @Test
    fun `an expired grant is ignored and cleaned up`() = runBlocking {
        val grantDao = FakePermissionGrantDao()
        val repository = SecurityPolicyRepository(
            FakeSecuritySettingsDao(),
            FakeProjectSecuritySettingsDao(),
            grantDao,
            AgentTestDispatchers
        )
        val past = System.currentTimeMillis() - 1_000
        repository.grant(PermissionScope.PROJECT, "write_file", projectId = "project-A", expiresAt = past)

        assertTrue(repository.restoreProjectGrants().isEmpty())
        assertEquals(1, repository.pruneExpiredGrants())
        assertTrue(grantDao.all().isEmpty())
    }

    @Test
    fun `a grant revoked mid-task is not honoured at execution time`() = runBlocking {
        val permissions = PermissionManager(ApprovalBroker())
        val engine = engineWithGrants(permissions)
        permissions.grantForTask("task-A", "write_file")

        val request = writeRequest()
        val decision = engine.authorize(request, true)
        assertTrue(decision.allowed)

        permissions.revokeTaskPermissions("task-A")
        val revalidated = engine.revalidate(request, decision, agentToolsEnabled = true)
        assertTrue(revalidated.denied)
        assertTrue(revalidated.reason.contains("no longer valid"))
    }

    @Test
    fun `emergency stop invalidates every grant before the next execution`() = runBlocking {
        val permissions = PermissionManager(ApprovalBroker())
        val engine = engineWithGrants(permissions)
        permissions.grantForTask("task-A", "write_file")
        permissions.grantForSession("session-A", "write_file")
        permissions.grantForProject("project-A", "write_file")

        val request = writeRequest()
        val decision = engine.authorize(request, true)
        assertTrue(decision.allowed)

        permissions.clearAllPermissions()

        assertTrue(engine.revalidate(request, decision, agentToolsEnabled = true).denied)
    }

    // ---- double execution and replay ----

    @Test
    fun `replaying an identical always-ask call asks again instead of reusing the approval`() = runBlocking {
        val permissions = PermissionManager(ApprovalBroker())
        permissions.grantForTask("task-A", "delete_file")
        val request = SecurityRequest(
            projectId = "project-A",
            projectRoot = root,
            taskId = "task-A",
            sessionId = "session-A",
            toolName = "delete_file",
            action = SecurityAction.DELETE,
            resourceType = ResourceType.PROJECT_FILE,
            resource = "src/App.kt",
            declaredPermission = ToolPermission.ALWAYS_ASK,
            destructive = true
        )
        val engine = engineWithGrants(permissions)
        repeat(3) {
            val decision = engine.authorize(request, agentToolsEnabled = true)
            assertEquals(SecurityOutcomeType.ELEVATED, decision.type)
            assertFalse(decision.allowed)
        }
    }

    // ---- untrusted arguments ----

    private val definition = ToolDefinition(
        name = "write_file",
        description = "test",
        parameters = listOf(
            AIToolParameter("path", AIToolParameterType.STRING, "path"),
            AIToolParameter("content", AIToolParameterType.STRING, "content", required = false)
        )
    )

    @Test
    fun `an oversized argument is rejected before it can be used`() {
        val huge = JsonObject(
            mapOf(
                "path" to JsonPrimitive("x.txt"),
                "content" to JsonPrimitive("x".repeat(ToolArgumentValidator.MAX_STRING_LENGTH + 1))
            )
        )
        assertTrue(ToolArgumentValidator.validate(definition, huge) is com.devstation.android.core.agent.ArgumentValidation.Invalid)
    }

    @Test
    fun `unknown parameters are rejected so they cannot be smuggled into a tool`() {
        val extra = JsonObject(
            mapOf(
                "path" to JsonPrimitive("x.txt"),
                "force" to JsonPrimitive(true)
            )
        )
        assertTrue(ToolArgumentValidator.validate(definition, extra) is com.devstation.android.core.agent.ArgumentValidation.Invalid)
    }

    @Test
    fun `a wrong parameter type is rejected`() {
        val wrong = JsonObject(
            mapOf(
                "path" to JsonPrimitive(1),
                "content" to JsonPrimitive("ok")
            )
        )
        assertTrue(ToolArgumentValidator.validate(definition, wrong) is com.devstation.android.core.agent.ArgumentValidation.Invalid)
    }

    // ---- audit completeness ----

    @Test
    fun `every denial is recorded in the audit log with a reason`() = runBlocking {
        val store = InMemorySecurityAuditStore()
        val engine = SecurityPolicyEngine(
            policyProvider = StaticSecurityPolicyProvider(),
            audit = SecurityAuditLogger(store)
        )
        engine.evaluate(
            SecurityRequest(
                projectId = "project-A",
                projectRoot = root,
                taskId = "task-A",
                toolName = "read_file",
                action = SecurityAction.READ,
                resourceType = ResourceType.PROJECT_FILE,
                resource = "/data/data/com.devstation.android/files/x",
                declaredPermission = ToolPermission.ALLOW
            ),
            agentToolsEnabled = true
        )
        val events = store.recent(10)
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.decision == AuditDecision.BLOCKED })
        assertTrue(events.all { it.summary.isNotBlank() })
        assertTrue(events.any { it.summary.contains("Android") || it.summary.contains("private") })
    }
}
