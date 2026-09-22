package com.devstation.android.core.security

import com.devstation.android.core.agent.AgentRuntime
import com.devstation.android.core.agent.AgentStartResult
import com.devstation.android.core.agent.AgentState
import com.devstation.android.core.agent.AgentTaskRequest
import com.devstation.android.core.agent.AgentTestDispatchers
import com.devstation.android.core.agent.ApprovalBroker
import com.devstation.android.core.agent.ApprovalDecision
import com.devstation.android.core.agent.ApprovalRequest
import com.devstation.android.core.agent.FakeAgentEventStore
import com.devstation.android.core.agent.FakeAgentHistoryStore
import com.devstation.android.core.agent.FakeAgentPermissionStore
import com.devstation.android.core.agent.FakeAgentTaskStore
import com.devstation.android.core.agent.FakeCommandRunner
import com.devstation.android.core.agent.FakeConversationPort
import com.devstation.android.core.agent.FakeEditorBridge
import com.devstation.android.core.agent.FakeProviderManager
import com.devstation.android.core.agent.PermissionManager
import com.devstation.android.core.agent.ProviderTurn
import com.devstation.android.core.agent.ScriptedProvider
import com.devstation.android.core.agent.SecretRedactor
import com.devstation.android.core.agent.createTempProject
import com.devstation.android.core.agent.deleteTempProject
import com.devstation.android.core.agent.fakeAiSettingsRepository
import com.devstation.android.core.agent.fakeProject
import com.devstation.android.core.agent.tools.AgentProcessRegistry
import com.devstation.android.core.agent.tools.CommandRunResult
import com.devstation.android.core.agent.tools.DefaultAgentToolFactory
import com.devstation.android.core.ai.AIToolCall
import com.devstation.android.core.database.AISettingsEntity
import com.devstation.android.core.security.policy.AgentSecurityMode
import com.devstation.android.core.security.policy.InMemorySecurityAuditStore
import com.devstation.android.core.security.policy.NetworkIntent
import com.devstation.android.core.security.policy.ProjectSecuritySettings
import com.devstation.android.core.security.policy.SecurityAuditLogger
import com.devstation.android.core.security.policy.SecurityGrantLookup
import com.devstation.android.core.security.policy.SecurityPolicy
import com.devstation.android.core.security.policy.SecurityPolicyEngine
import com.devstation.android.core.security.policy.SecurityPolicyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Phase 7 §48/§49/§73: attack and integration tests that drive the REAL agent loop, the REAL tool
 * registry and the REAL security engine. Nothing here stubs out the layer under test.
 */
class AgentSecurityIntegrationTest {

    private lateinit var root: File
    private lateinit var siblingRoot: File

    @Before
    fun setUp() {
        root = createTempProject(
            mapOf(
                "src/App.kt" to "fun main() {}\n",
                ".env" to "API_KEY=super-secret-value\n",
                "README.md" to "# demo\n"
            )
        )
        siblingRoot = createTempProject(mapOf("secret.kt" to "// other project\n"))
    }

    @After
    fun tearDown() {
        deleteTempProject(root)
        deleteTempProject(siblingRoot)
    }

    private class Harness(
        turns: List<ProviderTurn>,
        private val projectRoot: File,
        policy: SecurityPolicyProvider = SecurityPolicyProvider { SecurityPolicy.DEFAULT },
        grantLookup: SecurityGrantLookup? = null,
        settings: AISettingsEntity = AISettingsEntity(),
        projectSettings: ProjectSecuritySettings? = null,
        commandOutput: (String) -> String = { "ok" }
    ) {
        val registry = AgentProcessRegistry()
        val commands = FakeCommandRunner { command ->
            CommandRunResult(
                exitCode = 0,
                output = commandOutput(command),
                timedOut = false,
                cancelled = false,
                truncated = false,
                durationMs = 1L
            )
        }
        val history = FakeAgentHistoryStore()
        val permissionStore = FakeAgentPermissionStore()
        val broker = ApprovalBroker()
        val permissions = PermissionManager(broker)
        val auditStore = InMemorySecurityAuditStore()
        val audit = SecurityAuditLogger(auditStore)
        val engine = SecurityPolicyEngine(
            policyProvider = policy,
            // Mirrors AppContainer: the engine consults the live grant store.
            grants = grantLookup ?: SecurityGrantLookup { scope, tool, taskId, sessionId, projectId ->
                permissions.hasGrant(scope, tool, taskId, sessionId, projectId)
            },
            audit = audit,
            projectSettings = { projectSettings }
        )
        val provider = ScriptedProvider(turns)
        val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

        val runtime = AgentRuntime(
            providerManager = FakeProviderManager(provider = provider),
            aiSettingsRepository = fakeAiSettingsRepository(
                settings.copy(
                    defaultProviderId = settings.defaultProviderId ?: provider.providerId,
                    defaultModelId = settings.defaultModelId ?: "scripted-model"
                )
            ),
            projectLocator = { id -> fakeProject(projectRoot, id = id) },
            conversationPort = FakeConversationPort(selection = provider.providerId to "scripted-model"),
            toolFactory = DefaultAgentToolFactory(
                editorBridge = FakeEditorBridge(),
                processRegistry = registry,
                linuxRunner = commands,
                androidRunner = commands,
                linuxAvailable = { true },
                allowAndroidFallback = { false }
            ),
            permissionManager = permissions,
            broker = broker,
            processRegistry = registry,
            taskStore = FakeAgentTaskStore(),
            eventStore = FakeAgentEventStore(),
            historyStore = history,
            permissionStore = permissionStore,
            dispatchers = AgentTestDispatchers,
            scope = scope,
            securityEngine = engine,
            audit = audit,
            sessionId = "session-1"
        )

        suspend fun start(goal: String = "do the thing") =
            runtime.startTask(AgentTaskRequest(goal = goal, projectId = "p1", conversationId = "c1"))

        fun close() {
            scope.cancel()
        }
    }

    private suspend fun awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) yield()
        }
    }

    private fun toolCall(name: String, json: String) = AIToolCall("c1", name, json)

    /** Runs one tool turn to completion, answering any approval with [decision]. */
    private suspend fun Harness.runWith(decision: ApprovalDecision? = null) {
        val started = start()
        assertTrue("task was rejected: $started", started is AgentStartResult.Started)
        if (decision != null) {
            awaitCondition { runtime.pendingApproval.value != null }
            assertTrue(runtime.submitDecision(decision))
        }
        awaitCondition { runtime.state.value?.state?.isTerminal == true }
    }

    private suspend fun Harness.awaitPending(): ApprovalRequest {
        awaitCondition { runtime.pendingApproval.value != null }
        val pending = runtime.pendingApproval.value
        assertNotNull(pending)
        return pending!!
    }

    private fun harness(turns: List<ProviderTurn>, policy: SecurityPolicy = SecurityPolicy.DEFAULT) =
        Harness(turns, root, policy = SecurityPolicyProvider { policy })

    // ---- allowed access ----

    @Test
    fun `the agent can read a project file through the sandbox`() = runBlocking {
        val h = Harness(
            listOf(
                ProviderTurn.Tools("reading", listOf(toolCall("read_file", """{"path":"src/App.kt"}"""))),
                ProviderTurn.Text("done")
            ),
            root
        )
        try {
            h.runWith()
            assertEquals("read_file", h.history.entries.single().toolName)
            assertEquals("SUCCESS", h.history.entries.single().status)
            assertEquals(AgentState.COMPLETED, h.runtime.state.value?.state)
        } finally {
            h.close()
        }
    }

    @Test
    fun `the agent can list and search the project`() = runBlocking {
        val h = Harness(
            listOf(
                ProviderTurn.Tools("listing", listOf(toolCall("list_directory", """{"path":"."}"""))),
                ProviderTurn.Tools("searching", listOf(toolCall("search_project", """{"query":"fun main"}"""))),
                ProviderTurn.Text("done")
            ),
            root
        )
        try {
            h.runWith()
            assertEquals(listOf("list_directory", "search_project"), h.history.entries.map { it.toolName })
            assertTrue(h.history.entries.all { it.status == "SUCCESS" })
        } finally {
            h.close()
        }
    }

    // ---- escapes ----

    @Test
    fun `reading outside the project is denied`() = runBlocking {
        val h = harness(
            listOf(
                ProviderTurn.Tools("escape", listOf(toolCall("read_file", """{"path":"../outside.txt"}"""))),
                ProviderTurn.Text("done")
            )
        )
        try {
            h.runWith()
            assertEquals("DENIED", h.history.entries.single().status)
        } finally {
            h.close()
        }
    }

    @Test
    fun `reading another project is denied`() = runBlocking {
        val outside = File(siblingRoot, "secret.kt").absolutePath.replace('\\', '/')
        val h = harness(
            listOf(
                ProviderTurn.Tools("cross", listOf(toolCall("read_file", """{"path":"$outside"}"""))),
                ProviderTurn.Text("done")
            )
        )
        try {
            h.runWith()
            assertEquals("DENIED", h.history.entries.single().status)
        } finally {
            h.close()
        }
    }

    @Test
    fun `an android private path is denied and audited`() = runBlocking {
        val h = harness(
            listOf(
                ProviderTurn.Tools(
                    "private",
                    listOf(toolCall("read_file", """{"path":"/data/data/com.devstation.android/shared_prefs/x.xml"}"""))
                ),
                ProviderTurn.Text("done")
            )
        )
        try {
            h.runWith()
            assertEquals("DENIED", h.history.entries.single().status)
            assertTrue(h.auditStore.recent(50).any { it.summary.contains("private", ignoreCase = true) })
        } finally {
            h.close()
        }
    }

    @Test
    fun `deleting the project root is denied and the project survives`() = runBlocking {
        val h = harness(
            listOf(
                ProviderTurn.Tools("delete root", listOf(toolCall("delete_file", """{"path":"."}"""))),
                ProviderTurn.Text("done")
            )
        )
        try {
            h.runWith()
            assertEquals("DENIED", h.history.entries.single().status)
            assertTrue(File(root, "src/App.kt").exists())
        } finally {
            h.close()
        }
    }

    @Test
    fun `a path escape through a symlink is denied`() = runBlocking {
        val link = File(root, "escape-link")
        val created = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), File(siblingRoot, "secret.kt").toPath())
        }.isSuccess
        if (!created) return@runBlocking

        val h = harness(
            listOf(
                ProviderTurn.Tools("link", listOf(toolCall("read_file", """{"path":"escape-link"}"""))),
                ProviderTurn.Text("done")
            )
        )
        try {
            h.runWith()
            assertEquals("DENIED", h.history.entries.single().status)
        } finally {
            h.close()
        }
    }

    // ---- approvals happen BEFORE execution (§28) ----

    @Test
    fun `a file write does not happen until the user approves`() = runBlocking {
        val target = File(root, "notes.txt")
        val h = harness(
            listOf(
                ProviderTurn.Tools("write", listOf(toolCall("write_file", """{"path":"notes.txt","content":"hello"}"""))),
                ProviderTurn.Text("done")
            )
        )
        try {
            h.start()
            h.awaitPending()
            assertFalse("the file was written before approval", target.exists())

            assertTrue(h.runtime.submitDecision(ApprovalDecision.AllowOnce))
            awaitCondition { h.runtime.state.value?.state?.isTerminal == true }
            assertTrue("the file was not written after approval", target.exists())
            assertEquals("SUCCESS", h.history.entries.single().status)
        } finally {
            h.close()
        }
    }

    @Test
    fun `denying a write leaves the project untouched`() = runBlocking {
        val target = File(root, "notes.txt")
        val h = harness(
            listOf(
                ProviderTurn.Tools("write", listOf(toolCall("write_file", """{"path":"notes.txt","content":"hello"}"""))),
                ProviderTurn.Text("done")
            )
        )
        try {
            h.runWith(ApprovalDecision.Deny)
            assertFalse(target.exists())
            assertEquals("DENIED", h.history.entries.single().status)
        } finally {
            h.close()
        }
    }

    @Test
    fun `a sensitive file requires approval and its content never reaches the model`() = runBlocking {
        val h = harness(
            listOf(
                ProviderTurn.Tools("env", listOf(toolCall("read_file", """{"path":".env"}"""))),
                ProviderTurn.Text("done")
            )
        )
        try {
            h.start()
            val pending = h.awaitPending()
            assertTrue(pending.target.contains(".env"))
            assertTrue(h.runtime.submitDecision(ApprovalDecision.Deny))
            awaitCondition { h.runtime.state.value?.state?.isTerminal == true }
            assertEquals("DENIED", h.history.entries.single().status)
            assertTrue(
                h.provider.requests.all { request ->
                    request.messages.none { it.content.contains("super-secret-value") }
                }
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun `a destructive command always asks and does not run when denied`() = runBlocking {
        val h = harness(
            listOf(
                ProviderTurn.Tools("rm", listOf(toolCall("run_terminal_command", """{"command":"rm -rf src"}"""))),
                ProviderTurn.Text("done")
            )
        )
        try {
            h.start()
            val pending = h.awaitPending()
            assertTrue("destructive commands must be shown as always-ask", pending.elevated)
            assertTrue(h.runtime.submitDecision(ApprovalDecision.Deny))
            awaitCondition { h.runtime.state.value?.state?.isTerminal == true }
            assertTrue("the destructive command must not have run", h.commands.commands.isEmpty())
            assertTrue(File(root, "src/App.kt").exists())
        } finally {
            h.close()
        }
    }

    @Test
    fun `a destructive command cannot be granted for the whole task`() = runBlocking {
        val h = harness(
            listOf(
                ProviderTurn.Tools("rm", listOf(toolCall("run_terminal_command", """{"command":"rm -rf build"}"""))),
                ProviderTurn.Tools("rm again", listOf(toolCall("run_terminal_command", """{"command":"rm -rf build"}"""))),
                ProviderTurn.Text("done")
            )
        )
        try {
            h.start()
            h.awaitPending()
            assertTrue(h.runtime.submitDecision(ApprovalDecision.AllowForTask))
            awaitCondition { h.commands.commands.size == 1 }

            val second = h.awaitPending()
            assertTrue("the second destructive call must ask again", second.elevated)
            assertTrue(h.runtime.submitDecision(ApprovalDecision.Deny))
            awaitCondition { h.runtime.state.value?.state?.isTerminal == true }
            assertEquals(1, h.commands.commands.size)
        } finally {
            h.close()
        }
    }

    @Test
    fun `package installation asks and does not run when denied`() = runBlocking {
        val h = harness(
            listOf(
                ProviderTurn.Tools(
                    "install",
                    listOf(toolCall("run_terminal_command", """{"command":"npm install express"}"""))
                ),
                ProviderTurn.Text("done")
            )
        )
        try {
            h.start()
            val pending = h.awaitPending()
            assertEquals("Run terminal command", pending.title)
            assertTrue(h.runtime.submitDecision(ApprovalDecision.Deny))
            awaitCondition { h.runtime.state.value?.state?.isTerminal == true }
            assertTrue(h.commands.commands.isEmpty())
        } finally {
            h.close()
        }
    }

    @Test
    fun `a read-only command runs without approval`() = runBlocking {
        val h = harness(
            listOf(
                ProviderTurn.Tools("ls", listOf(toolCall("run_terminal_command", """{"command":"ls -la"}"""))),
                ProviderTurn.Text("done")
            )
        )
        try {
            h.runWith()
            assertNull(h.runtime.pendingApproval.value)
            assertEquals(listOf("ls -la"), h.commands.commands)
            assertEquals("SUCCESS", h.history.entries.single().status)
        } finally {
            h.close()
        }
    }

    @Test
    fun `a command referencing a private path is denied before it runs`() = runBlocking {
        val h = harness(
            listOf(
                ProviderTurn.Tools(
                    "peek",
                    listOf(
                        toolCall(
                            "run_terminal_command",
                            """{"command":"cat /data/data/com.devstation.android/files/x"}"""
                        )
                    )
                ),
                ProviderTurn.Text("done")
            )
        )
        try {
            h.runWith()
            assertNull(h.runtime.pendingApproval.value)
            assertTrue(h.commands.commands.isEmpty())
            assertEquals("DENIED", h.history.entries.single().status)
        } finally {
            h.close()
        }
    }

    // ---- context safety (§13/§31/§35) ----

    @Test
    fun `secret material in command output never reaches the model`() = runBlocking {
        val h = Harness(
            listOf(
                ProviderTurn.Tools("cat", listOf(toolCall("run_terminal_command", """{"command":"cat config.txt"}"""))),
                ProviderTurn.Text("done")
            ),
            root,
            commandOutput = { "AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE\npassword=hunter2\n" }
        )
        try {
            h.runWith()
            val context = h.provider.requests.flatMap { it.messages }.joinToString("\n") { it.content }
            assertFalse(context.contains("AKIAIOSFODNN7EXAMPLE"))
            assertFalse(context.contains("hunter2"))
            assertFalse(SecretRedactor.containsSuspectedSecret(context))
        } finally {
            h.close()
        }
    }

    @Test
    fun `oversized command output is bounded before it reaches the model`() = runBlocking {
        val h = Harness(
            listOf(
                ProviderTurn.Tools("cat", listOf(toolCall("run_terminal_command", """{"command":"cat big.txt"}"""))),
                ProviderTurn.Text("done")
            ),
            root,
            settings = AISettingsEntity(agentMaxToolOutputChars = 2_000),
            commandOutput = { "x".repeat(200_000) }
        )
        try {
            h.runWith()
            val context = h.provider.requests.flatMap { it.messages }.joinToString("\n") { it.content }
            assertTrue("output was not bounded (${context.length} chars)", context.length < 100_000)
        } finally {
            h.close()
        }
    }

    // ---- policy modes and project settings ----

    @Test
    fun `safe mode lets read-only commands run without approval`() = runBlocking {
        val h = harness(
            listOf(
                ProviderTurn.Tools("ls", listOf(toolCall("run_terminal_command", """{"command":"ls -la"}"""))),
                ProviderTurn.Tools("git", listOf(toolCall("run_terminal_command", """{"command":"git status"}"""))),
                ProviderTurn.Text("done")
            ),
            policy = SecurityPolicy.forMode(AgentSecurityMode.SAFE)
        )
        try {
            h.runWith()
            assertEquals(listOf("ls -la", "git status"), h.commands.commands)
            assertNull(h.runtime.pendingApproval.value)
            assertTrue(h.history.entries.all { it.status == "SUCCESS" })
        } finally {
            h.close()
        }
    }

    @Test
    fun `safe mode requires a fresh decision for every network command`() = runBlocking {
        val h = harness(
            listOf(
                ProviderTurn.Tools("fetch", listOf(toolCall("run_terminal_command", """{"command":"curl https://example.com/x"}"""))),
                ProviderTurn.Tools("fetch again", listOf(toolCall("run_terminal_command", """{"command":"curl https://example.com/x"}"""))),
                ProviderTurn.Text("done")
            ),
            policy = SecurityPolicy.forMode(AgentSecurityMode.SAFE)
        )
        try {
            h.start()
            val first = h.awaitPending()
            assertTrue("network access must be always-ask in safe mode", first.elevated)
            assertEquals(NetworkIntent.INTERNET, first.networkIntent)
            assertTrue(h.runtime.submitDecision(ApprovalDecision.AllowForTask))
            awaitCondition { h.commands.commands.size == 1 }

            val second = h.awaitPending()
            assertTrue("always-ask cannot be granted for the task", second.elevated)
            assertTrue(h.runtime.submitDecision(ApprovalDecision.Deny))
            awaitCondition { h.runtime.state.value?.state?.isTerminal == true }
            assertEquals(1, h.commands.commands.size)
        } finally {
            h.close()
        }
    }

    @Test
    fun `project settings can hard-deny a category for that project`() = runBlocking {
        val h = Harness(
            listOf(
                ProviderTurn.Tools("ls", listOf(toolCall("run_terminal_command", """{"command":"ls -la"}"""))),
                ProviderTurn.Text("done")
            ),
            root,
            projectSettings = ProjectSecuritySettings.defaults("p1").copy(allowTerminal = false)
        )
        try {
            h.runWith()
            assertTrue(h.commands.commands.isEmpty())
            assertEquals("DENIED", h.history.entries.single().status)
        } finally {
            h.close()
        }
    }

    @Test
    fun `the global safety switch blocks every tool call`() = runBlocking {
        val h = Harness(
            listOf(
                ProviderTurn.Tools("read", listOf(toolCall("read_file", """{"path":"src/App.kt"}"""))),
                ProviderTurn.Text("done")
            ),
            root,
            settings = AISettingsEntity(agentToolsEnabled = false)
        )
        try {
            val result = h.start()
            assertTrue(result is AgentStartResult.Rejected)
            assertTrue(h.provider.requests.isEmpty())
        } finally {
            h.close()
        }
    }

    // ---- process ownership (§26/§27/§55/§56) ----

    @Test
    fun `stopping one task does not terminate another task's process`() = runBlocking {
        val registry = AgentProcessRegistry()
        registry.register("user-terminal-session", FakeProcess())
        registry.register("agent-task-1", FakeProcess())
        assertEquals(2, registry.totalOwned())

        registry.terminate("agent-task-1")

        assertEquals(0, registry.ownedForTask("agent-task-1"))
        assertEquals(1, registry.ownedForTask("user-terminal-session"))
    }

    @Test
    fun `emergency stop cancels the task and clears grants`() = runBlocking {
        val h = harness(listOf(ProviderTurn.Text("nothing to do")))
        try {
            h.start()
            awaitCondition { h.runtime.state.value?.state?.isTerminal == true }
            h.runtime.stopAll()
            assertEquals(AgentState.CANCELLED, h.runtime.state.value?.state)
            assertTrue(h.permissions.sessionGrantedTools("session-1").isEmpty())
            assertEquals(0, h.registry.totalOwned())
        } finally {
            h.close()
        }
    }

    // ---- task isolation (§58) ----

    @Test
    fun `a task grant expires when the task ends`() = runBlocking {
        val h = harness(
            listOf(
                ProviderTurn.Tools("write", listOf(toolCall("write_file", """{"path":"a.txt","content":"1"}"""))),
                ProviderTurn.Text("done")
            )
        )
        try {
            h.runWith(ApprovalDecision.AllowForTask)
            val taskId = h.runtime.state.value!!.taskId
            awaitCondition { h.permissions.taskGrantedTools(taskId).isEmpty() }
            assertTrue(h.permissions.taskGrantedTools(taskId).isEmpty())
            assertEquals("SUCCESS", h.history.entries.single().status)
        } finally {
            h.close()
        }
    }

    // ---- loop limits (§6) ----

    @Test
    fun `the loop stops instead of running forever`() = runBlocking {
        val endless = List(60) {
            ProviderTurn.Tools("again", listOf(toolCall("read_file", """{"path":"README.md"}""")))
        }
        val h = Harness(endless, root, settings = AISettingsEntity(agentMaxIterations = 4, agentMaxToolCalls = 4))
        try {
            h.start()
            awaitCondition(timeoutMs = 10_000) { h.runtime.state.value?.state?.isTerminal == true }
            val state = h.runtime.state.value!!
            assertTrue(state.iterationCount <= 6)
            assertTrue(state.toolCallCount <= 4)
        } finally {
            h.close()
        }
    }

    /** Minimal Process stub so registry ownership is testable without spawning anything. */
    private class FakeProcess : Process() {
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
        override fun exitValue(): Int = 0
        override fun getErrorStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
        override fun getInputStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream(): java.io.OutputStream = java.io.ByteArrayOutputStream()
        override fun isAlive(): Boolean = false
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
    }
}
