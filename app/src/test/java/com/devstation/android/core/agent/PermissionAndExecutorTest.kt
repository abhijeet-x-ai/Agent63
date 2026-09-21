package com.devstation.android.core.agent

import com.devstation.android.core.agent.tools.AgentProcessRegistry
import com.devstation.android.core.agent.tools.RunTerminalCommandTool
import com.devstation.android.core.ai.AIToolCall
import com.devstation.android.core.ai.AIToolParameter
import com.devstation.android.core.ai.AIToolParameterType
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PermissionManagerTest {

    private val root = createTempProject()

    private fun context(projectRoot: File = root, taskId: String = "task-1") = ToolContext(
        projectId = "p1",
        projectRoot = projectRoot,
        workingDirectory = projectRoot,
        agentId = "agent",
        taskId = taskId
    )

    private fun request(toolName: String, taskId: String = "task-1") = ApprovalRequest(
        taskId = taskId,
        toolName = toolName,
        title = "Use $toolName",
        target = toolName,
        detail = "test",
        riskLevel = ToolRiskLevel.MEDIUM
    )

    @Test
    fun `allow tools run without approval`() = runBlocking {
        val broker = ApprovalBroker()
        val manager = PermissionManager(broker)
        val tool = FakeTool("read_file")
        val outcome = manager.authorize(tool, context(), request("read_file"), agentToolsEnabled = true)
        assertTrue(outcome is PermissionOutcome.Allowed)
        assertNull(broker.pending.value)
    }

    @Test
    fun `ask tools block until the user decides`() = runBlocking {
        val broker = ApprovalBroker()
        val manager = PermissionManager(broker)
        val tool = FakeTool(
            "write_file",
            definition = ToolDefinition(
                name = "write_file",
                description = "write",
                parameters = listOf(AIToolParameter("path", AIToolParameterType.STRING, "p")),
                permission = ToolPermission.ASK
            )
        )

        val decision = async { manager.authorize(tool, context(), request("write_file"), true) }
        // Give the awaiting coroutine a chance to publish the pending request.
        withTimeout(1_000) {
            while (broker.pending.value == null) kotlinx.coroutines.yield()
        }
        assertNotNull(broker.pending.value)
        broker.submit(ApprovalDecision.Deny)
        assertTrue(decision.await() is PermissionOutcome.Denied)
    }

    @Test
    fun `allow for this task is remembered for that task only`() = runBlocking {
        val broker = ApprovalBroker()
        val persisted = mutableListOf<String>()
        val manager = PermissionManager(broker, grantSink = { _, tool -> persisted.add(tool) })
        val tool = FakeTool(
            "write_file",
            definition = ToolDefinition(
                name = "write_file",
                description = "write",
                parameters = listOf(AIToolParameter("path", AIToolParameterType.STRING, "p")),
                permission = ToolPermission.ASK
            )
        )

        val first = async { manager.authorize(tool, context(), request("write_file"), true) }
        withTimeout(1_000) { while (broker.pending.value == null) kotlinx.coroutines.yield() }
        broker.submit(ApprovalDecision.AllowForTask)
        assertTrue(first.await() is PermissionOutcome.Allowed)

        assertTrue(manager.hasTaskGrant("task-1", "write_file"))
        assertTrue(persisted.contains("write_file"))
        // A different task is not covered by that grant and must still ask.
        assertFalse(manager.hasTaskGrant("task-2", "write_file"))
        assertTrue(manager.willRequireApproval(tool, context(taskId = "task-2"), null, true))

        // Repeating within the same task does not ask again.
        assertFalse(manager.willRequireApproval(tool, context(), null, true))

        // Ending the task revokes it.
        manager.revokeTaskPermissions("task-1")
        assertTrue(manager.willRequireApproval(tool, context(), null, true))
    }

    @Test
    fun `always-ask tools are never satisfied by a task grant`() = runBlocking {
        val broker = ApprovalBroker()
        val manager = PermissionManager(broker)
        val tool = FakeTool(
            "delete_file",
            definition = ToolDefinition(
                name = "delete_file",
                description = "delete",
                parameters = listOf(AIToolParameter("path", AIToolParameterType.STRING, "p")),
                riskLevel = ToolRiskLevel.HIGH,
                permission = ToolPermission.ALWAYS_ASK
            )
        )

        val call = async { manager.authorize(tool, context(), request("delete_file"), true) }
        withTimeout(1_000) { while (broker.pending.value == null) kotlinx.coroutines.yield() }
        broker.submit(ApprovalDecision.AllowForTask)
        assertTrue(call.await() is PermissionOutcome.Allowed)

        // No grant was stored, so the next deletion asks again.
        assertFalse(manager.hasTaskGrant("task-1", "delete_file"))
        assertTrue(manager.willRequireApproval(tool, context(), null, true))
    }

    @Test
    fun `global switch denies everything`() = runBlocking {
        val manager = PermissionManager(ApprovalBroker())
        val tool = FakeTool("read_file")
        val outcome = manager.authorize(tool, context(), request("read_file"), agentToolsEnabled = false)
        assertTrue(outcome is PermissionOutcome.Denied)
        assertEquals(
            PermissionManager.AGENT_DISABLED_MESSAGE,
            (outcome as PermissionOutcome.Denied).reason
        )
    }

    @Test
    fun `deny permission can never be overridden`() = runBlocking {
        val manager = PermissionManager(ApprovalBroker())
        val tool = FakeTool(
            "blocked",
            definition = ToolDefinition(
                name = "blocked",
                description = "blocked",
                parameters = emptyList(),
                permission = ToolPermission.DENY
            )
        )
        assertTrue(manager.authorize(tool, context(), request("blocked"), true) is PermissionOutcome.Denied)
    }

    @Test
    fun `destructive commands strengthen a terminal tool to always-ask`() {
        val manager = PermissionManager(ApprovalBroker())
        val tool = terminalPolicyOnlyTool()
        val classification = CommandClassifier.classify("rm -rf /workspace")
        assertEquals(
            ToolPermission.ALWAYS_ASK,
            manager.requiredPermission(tool, classification, agentToolsEnabled = true)
        )
        assertEquals(
            ToolPermission.ALLOW,
            manager.requiredPermission(tool, CommandClassifier.classify("ls"), agentToolsEnabled = true)
        )
        // No classification available: fail safe.
        assertEquals(
            ToolPermission.ALWAYS_ASK,
            manager.requiredPermission(tool, null, agentToolsEnabled = true)
        )
    }

    /** A classification-driven tool without real runners, used only for policy assertions. */
    private fun terminalPolicyOnlyTool(): Tool = object : Tool {
        override val definition = ToolDefinition(
            name = "run_terminal_command",
            description = "terminal",
            parameters = emptyList(),
            riskLevel = ToolRiskLevel.HIGH,
            permission = ToolPermission.ASK,
            classificationDriven = true
        )

        override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult =
            ToolResult.Success("run_terminal_command", "should not run")
    }
}

class ToolExecutorTest {

    private val root = createTempProject()
    private val context = ToolContext(
        projectId = "p1",
        projectRoot = root,
        workingDirectory = root,
        agentId = "agent",
        taskId = "task-1"
    )

    private fun executorFor(vararg tools: Tool, manager: PermissionManager = PermissionManager(ApprovalBroker())): ToolExecutor =
        ToolExecutor(ToolRegistry(tools.toList()), manager, AgentLoopLimits())

    @Test
    fun `unknown tool fails without executing anything`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val executor = executorFor()
        val result = executor.execute(
            AIToolCall("1", "does_not_exist", "{}"),
            context,
            true
        ) { events.add(it) }
        assertTrue(result is ToolResult.Error)
        assertTrue(events.any { it is AgentEvent.ToolFailed })
    }

    @Test
    fun `invalid arguments never reach the tool body`() = runBlocking {
        val tool = FakeTool("read_file")
        val executor = executorFor(tool)
        val result = executor.execute(AIToolCall("1", "read_file", "{not json"), context, true) {}
        assertTrue(result is ToolResult.Error)
        assertEquals(0, tool.executeCount)
    }

    @Test
    fun `unknown parameters never reach the tool body`() = runBlocking {
        val tool = FakeTool("read_file")
        val executor = executorFor(tool)
        val args = """{"path":"a.txt","root":true}"""
        val result = executor.execute(AIToolCall("1", "read_file", args), context, true) {}
        assertTrue(result is ToolResult.Error)
        assertEquals(0, tool.executeCount)
    }

    @Test
    fun `approval is requested before execution and denial prevents execution`() = runBlocking {
        val broker = ApprovalBroker()
        val manager = PermissionManager(broker)
        val tool = FakeTool(
            "write_file",
            definition = ToolDefinition(
                name = "write_file",
                description = "write",
                parameters = listOf(AIToolParameter("path", AIToolParameterType.STRING, "p")),
                permission = ToolPermission.ASK
            )
        )
        val executor = executorFor(tool, manager = manager)
        val events = mutableListOf<AgentEvent>()

        val outcome = async {
            executor.execute(AIToolCall("1", "write_file", """{"path":"a.txt"}"""), context, true) { events.add(it) }
        }
        withTimeout(1_000) { while (broker.pending.value == null) kotlinx.coroutines.yield() }

        // Approval card is visible and the tool has NOT run yet.
        assertTrue(events.any { it is AgentEvent.ToolApprovalRequired })
        assertFalse(events.any { it is AgentEvent.ToolStarted })
        assertEquals(0, tool.executeCount)

        broker.submit(ApprovalDecision.Deny)
        val result = outcome.await()
        assertTrue(result is ToolResult.Denied)
        assertEquals(0, tool.executeCount)
        assertTrue(events.any { it is AgentEvent.ToolDenied })
    }

    @Test
    fun `allowed tool executes and emits started plus completed`() = runBlocking {
        val tool = FakeTool("read_file")
        val executor = executorFor(tool)
        val events = mutableListOf<AgentEvent>()
        val result = executor.execute(AIToolCall("1", "read_file", """{"path":"a.txt"}"""), context, true) { events.add(it) }
        assertTrue(result is ToolResult.Success)
        assertEquals(1, tool.executeCount)
        assertTrue(events.any { it is AgentEvent.ToolStarted })
        assertTrue(events.any { it is AgentEvent.ToolCompleted })
    }

    @Test
    fun `tool output is redacted and bounded before leaving the executor`() = runBlocking {
        val secret = "sk-abcdefghijklmnop1234"
        val tool = FakeTool(
            "read_file",
            result = { ToolResult.Success("read_file", "token=$secret\n" + "x".repeat(50_000)) }
        )
        val executor = executorFor(tool)
        val result = executor.execute(AIToolCall("1", "read_file", """{"path":"a.txt"}"""), context, true) {}
        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertFalse(output.contains(secret))
        assertTrue(output.length <= AgentLoopLimits().maxToolOutputChars)
    }

    @Test
    fun `global switch stops tool execution in the executor`() = runBlocking {
        val tool = FakeTool("read_file")
        val executor = executorFor(tool)
        val result = executor.execute(AIToolCall("1", "read_file", """{"path":"a.txt"}"""), context, false) {}
        assertTrue(result is ToolResult.Denied)
        assertEquals(0, tool.executeCount)
    }

    @Test
    fun `cancelled task short-circuits before execution`() = runBlocking {
        val cancelled = true
        val cancelledContext = ToolContext(
            projectId = "p1",
            projectRoot = root,
            workingDirectory = root,
            agentId = "agent",
            taskId = "task-1",
            cancellationCheck = { cancelled }
        )
        val tool = FakeTool("read_file")
        val executor = executorFor(tool)
        val result = executor.execute(AIToolCall("1", "read_file", """{"path":"a.txt"}"""), cancelledContext, true) {}
        assertTrue(result is ToolResult.Cancelled)
        assertEquals(0, tool.executeCount)
    }

    @Test
    fun `tool parameter injection cannot smuggle extra keys`() = runBlocking {
        val tool = FakeTool("read_file")
        val executor = executorFor(tool)
        val payload = buildJsonObject { put("path", "a.txt") }.toString()
        assertTrue(executor.execute(AIToolCall("1", "read_file", payload), context, true) {} is ToolResult.Success)
        val injected = JsonObject(
            mapOf(
                "path" to kotlinx.serialization.json.JsonPrimitive("a.txt"),
                "command" to kotlinx.serialization.json.JsonPrimitive("rm -rf /")
            )
        ).toString()
        val result = executor.execute(AIToolCall("2", "read_file", injected), context, true) {}
        assertTrue(result is ToolResult.Error)
    }

    @Test
    fun `terminal tool classify is used for the approval card risk`() {
        val classification = CommandClassifier.classify("rm -rf build")
        assertTrue(classification.riskLevel == ToolRiskLevel.CRITICAL)
    }
}

class RunTerminalCommandToolPolicyTest {

    /** Confirms the terminal tool is classification-driven, which is what makes §22 hold. */
    @Test
    fun `terminal tool declares classification-driven policy`() {
        val tool = RunTerminalCommandTool(
            linuxRunner = null,
            androidRunner = FakeCommandRunner(),
            linuxAvailable = { false },
            allowAndroidFallback = { true },
            registry = AgentProcessRegistry(),
            limits = AgentLoopLimits()
        )
        assertTrue(tool.definition.classificationDriven)
        val args = buildJsonObject { put("command", "rm -rf /") }
        assertEquals(CommandCategory.DESTRUCTIVE, tool.classify(args).category)
        assertEquals(CommandCategory.READ_ONLY, tool.classify(buildJsonObject { put("command", "ls") }).category)
    }
}
