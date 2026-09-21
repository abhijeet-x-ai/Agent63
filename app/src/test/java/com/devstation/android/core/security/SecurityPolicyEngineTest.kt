package com.devstation.android.core.security

import com.devstation.android.core.agent.PermissionScope
import com.devstation.android.core.agent.ToolPermission
import com.devstation.android.core.agent.ToolRiskLevel
import com.devstation.android.core.agent.createTempProject
import com.devstation.android.core.agent.deleteTempProject
import com.devstation.android.core.security.policy.AgentSecurityMode
import com.devstation.android.core.security.policy.CategoryPolicy
import com.devstation.android.core.security.policy.PermissionCategory
import com.devstation.android.core.security.policy.ProjectSecuritySettings
import com.devstation.android.core.security.policy.ResourceType
import com.devstation.android.core.security.policy.SecurityAction
import com.devstation.android.core.security.policy.SecurityGrantLookup
import com.devstation.android.core.security.policy.SecurityOutcomeType
import com.devstation.android.core.security.policy.SecurityPolicy
import com.devstation.android.core.security.policy.SecurityPolicyEngine
import com.devstation.android.core.security.policy.SecurityRequest
import com.devstation.android.core.security.policy.StaticSecurityPolicyProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Phase 7 §2/§3/§4/§5/§15–§19/§29/§71: the centralized decision point.
 */
class SecurityPolicyEngineTest {

    private lateinit var root: File
    private lateinit var siblingRoot: File

    @Before
    fun setUp() {
        root = createTempProject(mapOf("src/App.kt" to "fun main() {}\n", ".env" to "TOKEN=abc123\n"))
        siblingRoot = createTempProject(mapOf("secret.kt" to "// other project\n"))
    }

    @After
    fun tearDown() {
        deleteTempProject(root)
        deleteTempProject(siblingRoot)
    }

    // ---- helpers ----

    private fun engine(
        policy: SecurityPolicy = SecurityPolicy.DEFAULT,
        grantScopes: Set<PermissionScope> = emptySet(),
        projectSettings: ProjectSecuritySettings? = null
    ) = SecurityPolicyEngine(
        policyProvider = StaticSecurityPolicyProvider(policy),
        grants = SecurityGrantLookup { scope, _, _, _, _ -> scope in grantScopes },
        projectSettings = { projectSettings }
    )

    private fun request(
        tool: String = "read_file",
        action: SecurityAction = SecurityAction.READ,
        resourceType: ResourceType = ResourceType.PROJECT_FILE,
        resource: String? = "src/App.kt",
        declared: ToolPermission = ToolPermission.ALLOW,
        risk: ToolRiskLevel = ToolRiskLevel.LOW,
        classificationDriven: Boolean = false,
        destructive: Boolean = false,
        projectId: String? = "p1",
        projectRoot: File? = root,
        taskId: String? = "t1",
        sessionId: String? = "s1"
    ) = SecurityRequest(
        projectId = projectId,
        projectRoot = projectRoot,
        taskId = taskId,
        sessionId = sessionId,
        agentId = "agent-1",
        toolName = tool,
        action = action,
        resourceType = resourceType,
        resource = resource,
        riskLevel = risk,
        declaredPermission = declared,
        classificationDriven = classificationDriven,
        destructive = destructive
    )

    // ---- global gate and identity ----

    @Test
    fun `agent tools disabled denies everything`() = runBlocking {
        val decision = engine().evaluate(request(), agentToolsEnabled = false)
        assertEquals(SecurityOutcomeType.DENY, decision.type)
        assertTrue(decision.reason.contains("disabled"))
    }

    @Test
    fun `missing project or task identity is denied`() = runBlocking {
        val e = engine()
        assertTrue(e.evaluate(request(projectId = null), true).denied)
        assertTrue(e.evaluate(request(projectRoot = null), true).denied)
        assertTrue(e.evaluate(request(taskId = ""), true).denied)
    }

    @Test
    fun `project isolation blocks a sibling project path`() = runBlocking {
        val decision = engine().evaluate(
            request(resource = File(siblingRoot, "secret.kt").absolutePath),
            agentToolsEnabled = true
        )
        assertEquals(SecurityOutcomeType.DENY, decision.type)
    }

    // ---- filesystem ----

    @Test
    fun `reading inside the project is allowed`() = runBlocking {
        val decision = engine().evaluate(request(), true)
        assertEquals(SecurityOutcomeType.ALLOW, decision.type)
        assertNotNull(decision.sandboxToken)
    }

    @Test
    fun `traversal and android private paths are always denied`() = runBlocking {
        val e = engine()
        listOf(
            "../outside.txt",
            "../../etc/passwd",
            "/data/data/com.devstation.android/files/x",
            "/proc/self/environ",
            "/system/bin/sh",
            "/sdcard/Download/x"
        ).forEach { path ->
            val decision = e.evaluate(request(resource = path), true)
            assertEquals("expected denial for $path", SecurityOutcomeType.DENY, decision.type)
        }
    }

    @Test
    fun `reading a sensitive file asks, and is reported as sensitive`() = runBlocking {
        val decision = engine().evaluate(request(resource = ".env"), true)
        assertEquals(SecurityOutcomeType.ASK, decision.type)
        assertEquals(ResourceType.SENSITIVE_FILE, decision.resourceType)
        assertTrue(decision.sensitive)
    }

    @Test
    fun `deleting a sensitive file always asks`() = runBlocking {
        val decision = engine().evaluate(
            request(
                tool = "delete_file",
                action = SecurityAction.DELETE,
                resource = ".env",
                declared = ToolPermission.ALWAYS_ASK
            ),
            true
        )
        assertEquals(SecurityOutcomeType.ELEVATED, decision.type)
    }

    // ---- credential isolation (§12/§62) ----

    @Test
    fun `credential resource is denied even with every grant`() = runBlocking {
        val e = engine(grantScopes = PermissionScope.entries.toSet())
        val decision = e.authorize(
            request(resourceType = ResourceType.CREDENTIAL, resource = "devstation_secure_prefs"),
            agentToolsEnabled = true
        )
        assertEquals(SecurityOutcomeType.DENY, decision.type)
        assertTrue(decision.reason.contains("credential"))
    }

    @Test
    fun `credential policy cannot be loosened through the policy API`() {
        val policy = SecurityPolicy.DEFAULT.withCategory(PermissionCategory.CREDENTIALS, CategoryPolicy.ALLOW)
        assertEquals(CategoryPolicy.DENY, policy.credentials)
    }

    // ---- approval scopes (§4/§31/§53/§58–§60) ----

    @Test
    fun `writing asks, a task grant satisfies it, other scopes are validated by identity`() = runBlocking {
        val writeRequest = request(
            tool = "write_file",
            action = SecurityAction.WRITE,
            declared = ToolPermission.ASK
        )
        assertEquals(SecurityOutcomeType.ASK, engine().evaluate(writeRequest, true).type)
        assertTrue(engine(grantScopes = setOf(PermissionScope.PER_TASK)).authorize(writeRequest, true).allowed)
        assertTrue(engine(grantScopes = setOf(PermissionScope.SESSION)).authorize(writeRequest, true).allowed)
        assertTrue(engine(grantScopes = setOf(PermissionScope.PROJECT)).authorize(writeRequest, true).allowed)
    }

    @Test
    fun `a request-scoped approval never satisfies a later request`() = runBlocking {
        val writeRequest = request(
            tool = "write_file",
            action = SecurityAction.WRITE,
            declared = ToolPermission.ASK
        )
        val decision = engine(grantScopes = setOf(PermissionScope.PER_REQUEST)).authorize(writeRequest, true)
        assertEquals(SecurityOutcomeType.ASK, decision.type)
        assertFalse(decision.allowed)
    }

    @Test
    fun `always-ask is never satisfied by a grant`() = runBlocking {
        val deleteRequest = request(
            tool = "delete_file",
            action = SecurityAction.DELETE,
            declared = ToolPermission.ALWAYS_ASK
        )
        val e = engine(grantScopes = PermissionScope.entries.toSet())
        val evaluated = e.evaluate(deleteRequest, true)
        val authorized = e.authorize(deleteRequest, true)
        assertEquals(SecurityOutcomeType.ELEVATED, evaluated.type)
        assertEquals(SecurityOutcomeType.ELEVATED, authorized.type)
        assertFalse(authorized.allowed)
    }

    // ---- terminal policy (§15–§19) ----

    private fun terminal(command: String, declared: ToolPermission = ToolPermission.ASK) = request(
        tool = "run_terminal_command",
        action = SecurityAction.EXECUTE,
        resourceType = ResourceType.TERMINAL,
        resource = command,
        declared = declared,
        classificationDriven = true,
        risk = ToolRiskLevel.MEDIUM
    )

    @Test
    fun `read-only commands are allowed and destructive ones always ask`() = runBlocking {
        val e = engine()
        assertEquals(SecurityOutcomeType.ALLOW, e.evaluate(terminal("ls -la"), true).type)
        assertEquals(SecurityOutcomeType.ALLOW, e.evaluate(terminal("git status"), true).type)
        assertEquals(
            SecurityOutcomeType.ELEVATED,
            e.evaluate(terminal("rm -rf build"), true).type
        )
        assertEquals(
            SecurityOutcomeType.ELEVATED,
            e.evaluate(terminal("apk del curl"), true).type
        )
    }

    @Test
    fun `unknown commands always ask and are never auto-allowed`() = runBlocking {
        val e = engine(grantScopes = PermissionScope.entries.toSet())
        val decision = e.authorize(terminal("frobnicate --all"), true)
        assertEquals(SecurityOutcomeType.ELEVATED, decision.type)
        assertFalse(decision.allowed)
    }

    @Test
    fun `a terminal command referencing a private path is denied`() = runBlocking {
        val decision = engine().evaluate(terminal("cat /data/data/com.devstation.android/files/x"), true)
        assertEquals(SecurityOutcomeType.DENY, decision.type)
    }

    @Test
    fun `package installation asks while package removal always asks`() = runBlocking {
        val e = engine()
        assertEquals(SecurityOutcomeType.ASK, e.evaluate(terminal("npm install express"), true).type)
        assertEquals(SecurityOutcomeType.ELEVATED, e.evaluate(terminal("npm uninstall express"), true).type)
    }

    // ---- network policy (§20–§24) ----

    @Test
    fun `network commands ask and report a redacted destination`() = runBlocking {
        val decision = engine().evaluate(
            terminal("curl https://user:pw@example.com/data?token=SECRET"),
            true
        )
        assertEquals(SecurityOutcomeType.ASK, decision.type)
        assertNotNull(decision.destination)
        assertFalse("query string must not leak", decision.destination!!.contains("SECRET"))
        assertFalse("credentials must not leak", decision.destination!!.contains("pw"))
    }

    @Test
    fun `localhost is its own class and still requires approval`() = runBlocking {
        val decision = engine().evaluate(terminal("curl http://127.0.0.1:8080/health"), true)
        assertEquals(SecurityOutcomeType.ASK, decision.type)
        assertTrue(decision.destination?.contains("127.0.0.1") == true)
    }

    @Test
    fun `network policy set to deny blocks network commands`() = runBlocking {
        val policy = SecurityPolicy.DEFAULT.withCategory(PermissionCategory.NETWORK, CategoryPolicy.DENY)
        val decision = engine(policy).evaluate(terminal("curl https://example.com"), true)
        assertEquals(SecurityOutcomeType.DENY, decision.type)
    }

    // ---- modes and project settings (§39–§41) ----

    @Test
    fun `safe mode keeps reads automatic but no grant can cover outward actions`() = runBlocking {
        val policy = SecurityPolicy.forMode(AgentSecurityMode.SAFE)
        val e = engine(policy, grantScopes = PermissionScope.entries.toSet())

        // §41: reading the project (and read-only commands) stays possible.
        assertEquals(SecurityOutcomeType.ALLOW, e.evaluate(request(), true).type)
        assertEquals(SecurityOutcomeType.ALLOW, e.evaluate(terminal("ls -la"), true).type)
        assertEquals(SecurityOutcomeType.ALLOW, e.evaluate(terminal("git status"), true).type)

        // A project change asks, and every grant in existence cannot make it automatic.
        assertEquals(
            SecurityOutcomeType.ASK,
            e.evaluate(
                request(tool = "write_file", action = SecurityAction.WRITE, declared = ToolPermission.ASK),
                true
            ).type
        )
        // Anything reaching outward needs a fresh decision every time.
        assertEquals(SecurityOutcomeType.ELEVATED, e.evaluate(terminal("curl https://example.com/x"), true).type)
        assertEquals(SecurityOutcomeType.ELEVATED, e.evaluate(terminal("npm install express"), true).type)
        assertEquals(
            SecurityOutcomeType.ELEVATED,
            e.authorize(terminal("curl https://example.com/x"), agentToolsEnabled = true).type
        )
    }

    @Test
    fun `project settings can hard-deny a category`() = runBlocking {
        val settings = ProjectSecuritySettings.defaults("p1").copy(allowTerminal = false)
        val decision = engine(projectSettings = settings).evaluate(terminal("ls -la"), true)
        assertEquals(SecurityOutcomeType.DENY, decision.type)
        assertTrue(decision.reason.contains("disabled for this project"))
    }

    @Test
    fun `project settings cannot re-enable credential access`() {
        val settings = ProjectSecuritySettings.defaults("p1").copy(allowSensitiveFileAccess = true)
        assertTrue(settings.denies(PermissionCategory.CREDENTIALS))
    }

    // ---- revalidation (§49/§50) ----

    @Test
    fun `a permission revoked between approval and execution blocks the call`() = runBlocking {
        val writeRequest = request(
            tool = "write_file",
            action = SecurityAction.WRITE,
            declared = ToolPermission.ASK
        )
        var grants: Set<PermissionScope> = setOf(PermissionScope.PER_TASK)
        val e = SecurityPolicyEngine(
            policyProvider = StaticSecurityPolicyProvider(),
            grants = SecurityGrantLookup { scope, _, _, _, _ -> scope in grants }
        )
        val decision = e.authorize(writeRequest, true)
        assertTrue(decision.allowed)

        // The user revokes while the approval is being processed.
        grants = emptySet()
        val revalidated = e.revalidate(writeRequest, decision, agentToolsEnabled = true)
        assertFalse(revalidated.allowed)
    }

    @Test
    fun `tightening the policy mid-task blocks a previously allowed action`() = runBlocking {
        var policy = SecurityPolicy.DEFAULT
        val e = SecurityPolicyEngine(policyProvider = { policy })
        val readRequest = request()
        val decision = e.evaluate(readRequest, true)
        assertTrue(decision.allowed)

        // The user switches to a policy that denies network/files mid-task.
        policy = SecurityPolicy.DEFAULT.withCategory(PermissionCategory.FILES, CategoryPolicy.DENY)
        val revalidated = e.revalidate(readRequest, decision, agentToolsEnabled = true)
        assertTrue(revalidated.denied)
        assertTrue(revalidated.reason.contains("Blocked before execution"))
    }

    @Test
    fun `a file changed after the decision blocks a write`() = runBlocking {
        val target = File(root, "src/App.kt")
        val writeRequest = request(
            tool = "write_file",
            action = SecurityAction.WRITE,
            declared = ToolPermission.ASK
        )
        val e = engine(grantScopes = setOf(PermissionScope.PER_TASK))
        val decision = e.authorize(writeRequest, true)
        assertTrue(decision.allowed)

        // Something else edits the file while the agent is working.
        target.writeText("fun main() { println(1) }\n")

        val revalidated = e.revalidate(writeRequest, decision, agentToolsEnabled = true)
        assertTrue(revalidated.denied)
    }

    @Test
    fun `disabling agent tools mid-task blocks the pending call`() = runBlocking {
        val readRequest = request()
        val e = engine()
        val decision = e.evaluate(readRequest, true)
        assertTrue(decision.allowed)
        assertTrue(e.revalidate(readRequest, decision, agentToolsEnabled = false).denied)
    }

    @Test
    fun `the decision explains itself when it denies`() = runBlocking {
        val decision = engine().evaluate(request(resource = "/data/data/x"), true)
        assertTrue(decision.reason.isNotBlank())
        assertTrue(decision.reason.contains("denied", ignoreCase = true))
    }
}
