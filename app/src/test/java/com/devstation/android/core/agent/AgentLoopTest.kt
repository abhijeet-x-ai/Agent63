package com.devstation.android.core.agent

import com.devstation.android.core.agent.tools.AgentProcessRegistry
import com.devstation.android.core.agent.tools.AgentToolFactory
import com.devstation.android.core.agent.tools.EditorBridge
import com.devstation.android.core.ai.AIError
import com.devstation.android.core.ai.AIProvider
import com.devstation.android.core.ai.AIProviderCapabilities
import com.devstation.android.core.ai.AIRequest
import com.devstation.android.core.ai.AIResponseEvent
import com.devstation.android.core.ai.AIToolCall
import com.devstation.android.core.ai.AIToolParameter
import com.devstation.android.core.ai.AIToolParameterType
import com.devstation.android.core.database.AISettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Test tool factory: fixed registry, no disk access beyond what the tool itself does. */
private class FakeToolFactory(private val tools: List<Tool>) : AgentToolFactory {
    override val editorBridge: EditorBridge = FakeEditorBridge()
    override val fileMutationToolNames: Set<String> = setOf("write_file", "apply_patch")
    val clearedTasks = mutableListOf<String>()

    override fun create(limits: AgentLoopLimits): ToolRegistry = ToolRegistry(tools)
    override fun clearTaskState(taskId: String) {
        clearedTasks.add(taskId)
    }
}

private class Harness(
    turns: List<ProviderTurn>,
    tools: List<Tool> = emptyList(),
    settings: AISettingsEntity = AISettingsEntity(),
    provider: AIProvider? = null,
    providerCapabilitiesToolCalling: Boolean = true,
    modelToolCalling: Boolean = true,
    projectRoot: File?,
    conversationSelection: Pair<String?, String?> = null to null
) {
    val root: File? = projectRoot
    val taskStore = FakeAgentTaskStore()
    val eventStore = FakeAgentEventStore()
    val historyStore = FakeAgentHistoryStore()
    val permissionStore = FakeAgentPermissionStore()
    val broker = ApprovalBroker()
    val permissionManager = PermissionManager(broker, grantSink = { taskId, tool -> permissionStore.grant(taskId, tool) })
    val processRegistry = AgentProcessRegistry()
    val toolFactory = FakeToolFactory(tools)
    val conversationPort = FakeConversationPort(selection = conversationSelection)
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

    private val capabilities = AIProviderCapabilities(
        chat = true,
        streaming = true,
        toolCalling = providerCapabilitiesToolCalling,
        modelListing = true,
        usageReporting = true
    )

    private val effectiveProvider: AIProvider = provider ?: ScriptedProvider(
        turns = turns,
        capabilities = capabilities
    )

    /** Tests configure behavioral settings only; the scripted provider is wired in here. */
    private val effectiveSettings = settings.copy(
        defaultProviderId = settings.defaultProviderId ?: effectiveProvider.providerId,
        defaultModelId = settings.defaultModelId ?: "scripted-model"
    )

    val runtime = AgentRuntime(
        providerManager = FakeProviderManager(
            provider = effectiveProvider,
            modelToolCalling = modelToolCalling
        ),
        aiSettingsRepository = fakeAiSettingsRepository(effectiveSettings),
        projectLocator = { id -> root?.let { fakeProject(it, id = id) } },
        conversationPort = conversationPort,
        toolFactory = toolFactory,
        permissionManager = permissionManager,
        broker = broker,
        processRegistry = processRegistry,
        taskStore = taskStore,
        eventStore = eventStore,
        historyStore = historyStore,
        permissionStore = permissionStore,
        dispatchers = AgentTestDispatchers,
        scope = scope
    )

    fun close() {
        scope.cancel()
    }
}

private suspend fun awaitCondition(timeoutMs: Long = 3_000, condition: () -> Boolean) {
    withTimeout(timeoutMs) {
        while (!condition()) yield()
    }
}

class AgentLoopTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = createTempProject(mapOf("src/App.kt" to "fun main() {}\n"))
    }

    @After
    fun tearDown() = deleteTempProject(root)

    private fun request(goal: String = "Inspect this project") =
        AgentTaskRequest(goal = goal, projectId = "p1", conversationId = "c1")

    @Test
    fun `plain answer completes the task and persists the assistant message`() = runBlocking {
        val harness = Harness(listOf(ProviderTurn.Text("The project has one Kotlin file.")), projectRoot = root)
        try {
            val started = harness.runtime.startTask(request())
            assertTrue(started is AgentStartResult.Started)
            awaitCondition { harness.runtime.state.value?.state == AgentState.COMPLETED }

            val snapshot = harness.runtime.state.value!!
            assertEquals(AgentState.COMPLETED, snapshot.state)
            assertEquals(1, snapshot.iterationCount)
            assertEquals(0, snapshot.toolCallCount)
            assertEquals(listOf("The project has one Kotlin file."), harness.conversationPort.persisted)
            assertEquals(AgentState.COMPLETED.name, harness.taskStore.tasks.values.single().state)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `tool calls run, are recorded in the timeline and the action history`() = runBlocking {
        val readTool = FakeTool("read_file")
        val harness = Harness(
            turns = listOf(
                ProviderTurn.Tools("Let me look.", listOf(AIToolCall("c1", "read_file", """{"path":"src/App.kt"}"""))),
                ProviderTurn.Text("Done.")
            ),
            tools = listOf(readTool),
            projectRoot = root
        )
        try {
            harness.runtime.startTask(request())
            awaitCondition { harness.runtime.state.value?.state == AgentState.COMPLETED }

            assertEquals(1, readTool.executeCount)
            val snapshot = harness.runtime.state.value!!
            assertEquals(2, snapshot.iterationCount)
            assertEquals(1, snapshot.toolCallCount)
            assertEquals(1, harness.historyStore.entries.size)
            assertEquals("read_file", harness.historyStore.entries.single().toolName)
            assertEquals("SUCCESS", harness.historyStore.entries.single().status)
            assertTrue(snapshot.timeline.any { it.label.startsWith("read_file") })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `agent tools disabled rejects the task before any provider call`() = runBlocking {
        val provider = ScriptedProvider(listOf(ProviderTurn.Text("nope")))
        val harness = Harness(
            turns = emptyList(),
            settings = AISettingsEntity(agentToolsEnabled = false),
            provider = provider,
            projectRoot = root
        )
        try {
            val result = harness.runtime.startTask(request())
            assertTrue(result is AgentStartResult.Rejected)
            assertEquals(PermissionManager.AGENT_DISABLED_MESSAGE, (result as AgentStartResult.Rejected).message)
            assertEquals(0, provider.callCount)
            assertTrue(harness.historyStore.entries.isEmpty())
            assertEquals(null, harness.taskStore.tasks.values.firstOrNull())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `model without tool calling is rejected with a clear message`() = runBlocking {
        val harness = Harness(
            turns = listOf(ProviderTurn.Text("chat only")),
            providerCapabilitiesToolCalling = false,
            projectRoot = root
        )
        try {
            val result = harness.runtime.startTask(request())
            assertTrue(result is AgentStartResult.Rejected)
            assertTrue((result as AgentStartResult.Rejected).message.contains("not agent tool execution"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `model-level capability gate also rejects`() = runBlocking {
        val harness = Harness(
            turns = listOf(ProviderTurn.Text("chat only")),
            modelToolCalling = false,
            projectRoot = root
        )
        try {
            val result = harness.runtime.startTask(request())
            assertTrue(result is AgentStartResult.Rejected)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `no project rejects with a clear message`() = runBlocking {
        val harness = Harness(listOf(ProviderTurn.Text("hi")), projectRoot = null)
        try {
            val result = harness.runtime.startTask(request())
            assertTrue(result is AgentStartResult.Rejected)
            assertTrue((result as AgentStartResult.Rejected).message.contains("Project not found"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `blank goal is rejected`() = runBlocking {
        val harness = Harness(listOf(ProviderTurn.Text("hi")), projectRoot = root)
        try {
            assertTrue(harness.runtime.startTask(request(goal = "   ")) is AgentStartResult.Rejected)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `unknown tools fail the step without executing anything`() = runBlocking {
        val harness = Harness(
            turns = listOf(
                ProviderTurn.Tools("", listOf(AIToolCall("c1", "rm_everything", "{}"))),
                ProviderTurn.Text("Recovered.")
            ),
            tools = emptyList(),
            projectRoot = root
        )
        try {
            harness.runtime.startTask(request())
            awaitCondition { harness.runtime.state.value?.state == AgentState.COMPLETED }
            val entry = harness.historyStore.entries.single()
            assertEquals("FAILED", entry.status)
            assertTrue(harness.runtime.state.value!!.summary!!.errors.any { it.contains("Unknown tool") })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `iteration limit stops the agent with an explicit message`() = runBlocking {
        val tool = FakeTool("read_file")
        val loopTurn = ProviderTurn.Tools("", listOf(AIToolCall("c", "read_file", """{"path":"a"}""")))
        val harness = Harness(
            turns = List(10) { loopTurn },
            tools = listOf(tool),
            settings = AISettingsEntity(agentMaxIterations = 2),
            projectRoot = root
        )
        try {
            harness.runtime.startTask(request())
            awaitCondition { harness.runtime.state.value?.state == AgentState.FAILED }
            val snapshot = harness.runtime.state.value!!
            assertEquals(
                "Agent stopped because the execution limit was reached.",
                snapshot.errorMessage
            )
            assertEquals(2, snapshot.iterationCount)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `tool call limit stops the agent`() = runBlocking {
        val tool = FakeTool("read_file")
        val harness = Harness(
            turns = List(10) {
                ProviderTurn.Tools(
                    "",
                    listOf(
                        AIToolCall("a", "read_file", """{"path":"a"}"""),
                        AIToolCall("b", "read_file", """{"path":"b"}""")
                    )
                )
            },
            tools = listOf(tool),
            settings = AISettingsEntity(agentMaxToolCalls = 2),
            projectRoot = root
        )
        try {
            harness.runtime.startTask(request())
            awaitCondition { harness.runtime.state.value?.state == AgentState.FAILED }
            assertEquals(2, harness.runtime.state.value!!.toolCallCount)
            assertEquals(2, tool.executeCount)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `provider error fails the task`() = runBlocking {
        val harness = Harness(
            turns = listOf(ProviderTurn.Failure(AIError.AuthenticationError("bad key"))),
            projectRoot = root
        )
        try {
            harness.runtime.startTask(request())
            awaitCondition { harness.runtime.state.value?.state == AgentState.FAILED }
            assertEquals("bad key", harness.runtime.state.value!!.errorMessage)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `stop cancels the running task and terminates agent processes`() = runBlocking {
        val harness = Harness(
            turns = emptyList(),
            provider = HangingProvider(),
            projectRoot = root
        )
        try {
            val started = harness.runtime.startTask(request())
            assertTrue(started is AgentStartResult.Started)
            val taskId = (started as AgentStartResult.Started).taskId
            awaitCondition { harness.runtime.state.value?.state == AgentState.WAITING_FOR_MODEL }
            assertTrue(harness.runtime.state.value!!.state == AgentState.WAITING_FOR_MODEL)

            harness.runtime.stop()
            awaitCondition { harness.runtime.state.value?.state == AgentState.CANCELLED }
            assertEquals(AgentState.CANCELLED.name, harness.taskStore.tasks[taskId]!!.state)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `denied approval never executes the tool and is recorded as a warning`() = runBlocking {
        val writeTool = FakeTool(
            "write_file",
            definition = ToolDefinition(
                name = "write_file",
                description = "write",
                parameters = listOf(AIToolParameter("path", AIToolParameterType.STRING, "p")),
                permission = ToolPermission.ASK
            )
        )
        val harness = Harness(
            turns = listOf(
                ProviderTurn.Tools("", listOf(AIToolCall("c1", "write_file", """{"path":"src/App.kt"}"""))),
                ProviderTurn.Text("I could not write the file.")
            ),
            tools = listOf(writeTool),
            projectRoot = root
        )
        try {
            harness.runtime.startTask(request())
            awaitCondition { harness.runtime.pendingApproval.value != null }
            assertNotNull(harness.runtime.pendingApproval.value)
            harness.runtime.submitDecision(ApprovalDecision.Deny)
            awaitCondition { harness.runtime.state.value?.state == AgentState.COMPLETED }

            assertEquals(0, writeTool.executeCount)
            assertEquals("DENIED", harness.historyStore.entries.single().status)
            assertTrue(harness.runtime.state.value!!.summary!!.warnings.any { it.contains("Denied") })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `allow once executes the tool exactly once and does not grant the task`() = runBlocking {
        val writeTool = FakeTool(
            "write_file",
            definition = ToolDefinition(
                name = "write_file",
                description = "write",
                parameters = listOf(AIToolParameter("path", AIToolParameterType.STRING, "p")),
                permission = ToolPermission.ASK
            )
        )
        val harness = Harness(
            turns = listOf(
                ProviderTurn.Tools("", listOf(AIToolCall("c1", "write_file", """{"path":"a.json"}"""))),
                ProviderTurn.Tools("", listOf(AIToolCall("c2", "write_file", """{"path":"b.json"}"""))),
                ProviderTurn.Text("done")
            ),
            tools = listOf(writeTool),
            projectRoot = root
        )
        try {
            harness.runtime.startTask(request())
            awaitCondition { harness.runtime.pendingApproval.value != null }
            harness.runtime.submitDecision(ApprovalDecision.AllowOnce)
            // The second call must ask again because "allow once" is not a task grant.
            awaitCondition { harness.runtime.pendingApproval.value != null && writeTool.executeCount == 1 }
            assertFalse(harness.permissionManager.hasTaskGrant(harness.runtime.state.value!!.taskId, "write_file"))
            harness.runtime.submitDecision(ApprovalDecision.Deny)
            awaitCondition { harness.runtime.state.value?.state == AgentState.COMPLETED }
            assertEquals(1, writeTool.executeCount)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `allow for task covers later calls in the same task and is revoked at the end`() = runBlocking {
        val writeTool = FakeTool(
            "write_file",
            definition = ToolDefinition(
                name = "write_file",
                description = "write",
                parameters = listOf(AIToolParameter("path", AIToolParameterType.STRING, "p")),
                permission = ToolPermission.ASK
            )
        )
        val harness = Harness(
            turns = listOf(
                ProviderTurn.Tools("", listOf(AIToolCall("c1", "write_file", """{"path":"a.json"}"""))),
                ProviderTurn.Tools("", listOf(AIToolCall("c2", "write_file", """{"path":"b.json"}"""))),
                ProviderTurn.Text("done")
            ),
            tools = listOf(writeTool),
            projectRoot = root,
            settings = AISettingsEntity(agentMaxIterations = 10)
        )
        try {
            val started = harness.runtime.startTask(request())
            val taskId = (started as AgentStartResult.Started).taskId
            awaitCondition { harness.runtime.pendingApproval.value != null }
            harness.runtime.submitDecision(ApprovalDecision.AllowForTask)
            awaitCondition { harness.runtime.state.value?.state == AgentState.COMPLETED }

            assertEquals(2, writeTool.executeCount)
            // The task grant was persisted while running and cleared when the task ended.
            assertTrue(harness.taskStore.tasks.containsKey(taskId))
            assertEquals(emptySet<String>(), harness.permissionStore.taskScopedTools(taskId))
            assertFalse(harness.permissionManager.hasTaskGrant(taskId, "write_file"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `summary reports files changed, commands and tests only when they actually ran`() = runBlocking {
        val fileTool = FakeTool(
            "write_file",
            definition = ToolDefinition(
                name = "write_file",
                description = "write",
                parameters = listOf(AIToolParameter("path", AIToolParameterType.STRING, "p")),
                permission = ToolPermission.ALLOW
            ),
            result = { ToolResult.Success("write_file", "wrote", mapOf("path" to "src/App.kt")) }
        )
        val terminal = RunTerminalCommandProbe()
        val harness = Harness(
            turns = listOf(
                ProviderTurn.Tools("", listOf(AIToolCall("c1", "write_file", """{"path":"src/App.kt"}"""))),
                ProviderTurn.Tools("", listOf(AIToolCall("c2", "run_terminal_command", """{"command":"npm test"}"""))),
                ProviderTurn.Text("All done.")
            ),
            tools = listOf(fileTool, terminal),
            projectRoot = root
        )
        try {
            harness.runtime.startTask(request())
            awaitCondition { harness.runtime.state.value?.state == AgentState.COMPLETED }
            val summary = harness.runtime.state.value!!.summary!!
            assertEquals(listOf("src/App.kt"), summary.filesChanged)
            assertEquals(listOf("npm test"), summary.commandsExecuted)
            assertEquals(listOf("npm test"), summary.testsRun)
            assertEquals("All done.", summary.finalMessage)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `interrupted recovery marks active tasks and clears task permissions`() = runBlocking {
        val harness = Harness(listOf(ProviderTurn.Text("hi")), projectRoot = root)
        try {
            harness.taskStore.create(
                com.devstation.android.core.database.AgentTaskEntity(
                    taskId = "old-task",
                    projectId = "p1",
                    goal = "old",
                    state = AgentState.EXECUTING_TOOL.name,
                    createdAt = 0,
                    updatedAt = 0
                )
            )
            harness.permissionStore.grant("old-task", "write_file")
            harness.runtime.recoverInterruptedTasks()
            assertEquals(AgentState.INTERRUPTED.name, harness.taskStore.tasks["old-task"]!!.state)
            assertEquals(emptySet<String>(), harness.permissionStore.taskScopedTools("old-task"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `second task cannot start while one is running`() = runBlocking {
        val harness = Harness(turns = emptyList(), provider = HangingProvider(), projectRoot = root)
        try {
            assertTrue(harness.runtime.startTask(request()) is AgentStartResult.Started)
            awaitCondition { harness.runtime.state.value?.state == AgentState.WAITING_FOR_MODEL }
            val second = harness.runtime.startTask(request("another"))
            assertTrue(second is AgentStartResult.Rejected)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `emergency stop clears approvals and permissions and reports cancelled`() = runBlocking {
        val writeTool = FakeTool(
            "write_file",
            definition = ToolDefinition(
                name = "write_file",
                description = "write",
                parameters = listOf(AIToolParameter("path", AIToolParameterType.STRING, "p")),
                permission = ToolPermission.ASK
            )
        )
        val harness = Harness(
            turns = listOf(ProviderTurn.Tools("", listOf(AIToolCall("c1", "write_file", """{"path":"a"}""")))),
            tools = listOf(writeTool),
            projectRoot = root
        )
        try {
            harness.runtime.startTask(request())
            awaitCondition { harness.runtime.pendingApproval.value != null }
            harness.runtime.stopAll()
            awaitCondition { harness.runtime.pendingApproval.value == null }
            assertEquals(0, writeTool.executeCount)
            assertEquals(AgentState.CANCELLED, harness.runtime.state.value?.state)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `terminal policy probe uses a fake runner`() {
        val runner = FakeCommandRunner("linux")
        assertEquals("linux", runner.environmentLabel)
    }
}

/** Terminal tool wired to a fake runner so loop tests never spawn a real process. */
private class RunTerminalCommandProbe : Tool {
    override val definition = ToolDefinition(
        name = "run_terminal_command",
        description = "terminal",
        parameters = listOf(AIToolParameter("command", AIToolParameterType.STRING, "command")),
        riskLevel = ToolRiskLevel.HIGH,
        permission = ToolPermission.ALLOW,
        classificationDriven = false
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult =
        ToolResult.Success("run_terminal_command", "output", mapOf("exitCode" to "0"))
}

/** Provider that never answers, used to test cancellation. */
private class HangingProvider : AIProvider {
    override val providerId = "hanging"
    override val displayName = "Hanging"
    override val capabilities = AIProviderCapabilities(chat = true, streaming = true, toolCalling = true)

    override suspend fun getModels() = Result.success(emptyList<com.devstation.android.core.ai.AIModel>())
    override suspend fun testConnection() = Result.success(
        com.devstation.android.core.ai.ProviderHealth(providerId, true, true, "ok")
    )

    override fun generate(request: AIRequest): Flow<AIResponseEvent> = flow {
        emit(AIResponseEvent.Started(providerId, request.modelId))
        awaitCancellation()
    }
}
