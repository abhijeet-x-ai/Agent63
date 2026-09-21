package com.devstation.android.core.agent

import com.devstation.android.core.agent.tools.AgentProcessRegistry
import com.devstation.android.core.agent.tools.AgentToolFactory
import com.devstation.android.core.agent.tools.EditorBridge
import com.devstation.android.core.ai.AIMessage
import com.devstation.android.core.ai.AIMessageRole
import com.devstation.android.core.ai.AIToolCall
import com.devstation.android.core.database.AISettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentContextBuilderTest {

    private val limits = AgentLoopLimits(maxContextChars = 4_000)

    @Test
    fun `keeps the goal last and bounds history`() {
        val builder = AgentContextBuilder(limits)
        val history = (1..40).map {
            if (it % 2 == 0) AIMessage(AIMessageRole.USER, "u$it") else AIMessage(AIMessageRole.ASSISTANT, "a$it")
        }
        val messages = builder.initialMessages("the goal", history)
        assertEquals("the goal", messages.last().content)
        assertTrue(messages.size <= AgentContextBuilder.MAX_HISTORY_MESSAGES + 1)
    }

    @Test
    fun `tool results are labelled json carrying status and output`() {
        val builder = AgentContextBuilder(limits)
        val messages = builder.initialMessages("goal")
        builder.appendToolResult(
            messages,
            AIToolCall("call_1", "read_file", "{}"),
            ToolResult.Success("read_file", "[FILE CONTENT] hello")
        )
        val toolMessage = messages.last()
        assertEquals(AIMessageRole.TOOL, toolMessage.role)
        assertEquals("call_1", toolMessage.toolCallId)
        assertEquals("read_file", toolMessage.toolName)
        assertTrue(toolMessage.content.contains("\"status\":\"success\""))
        assertTrue(toolMessage.content.contains("[FILE CONTENT] hello"))
    }

    @Test
    fun `denied and failed results keep their status`() {
        val builder = AgentContextBuilder(limits)
        val messages = builder.initialMessages("goal")
        builder.appendToolResult(messages, AIToolCall("1", "delete_file", "{}"), ToolResult.Denied("delete_file", "no"))
        builder.appendToolResult(messages, AIToolCall("2", "read_file", "{}"), ToolResult.Error("read_file", "boom"))
        assertTrue(messages[messages.size - 2].content.contains("\"status\":\"denied\""))
        assertTrue(messages.last().content.contains("\"status\":\"failed\""))
    }

    @Test
    fun `budget shrinks the oldest tool output first with an explicit marker`() {
        val builder = AgentContextBuilder(limits)
        val messages = builder.initialMessages("goal")
        repeat(6) { index ->
            builder.appendToolResult(
                messages,
                AIToolCall("$index", "read_file", "{}"),
                ToolResult.Success("read_file", "x".repeat(1_500))
            )
        }
        assertTrue(builder.estimateChars(messages) <= limits.maxContextChars)
        // The first tool result was shrunk, the most recent ones were preserved.
        assertTrue(messages[1].content.contains("omitted"))
        assertTrue(messages.last().content.contains("x".repeat(100)))
    }

    @Test
    fun `budget never drops the goal or the most recent turn`() {
        val builder = AgentContextBuilder(limits)
        val messages = builder.initialMessages("important goal")
        repeat(10) { index ->
            builder.appendAssistantTurn(messages, "assistant $index " + "y".repeat(600), emptyList())
        }
        builder.enforceBudget(messages)
        assertEquals("important goal", messages.first().content)
        assertTrue(messages.last().content.contains("assistant 9"))
        assertTrue(builder.estimateChars(messages) <= limits.maxContextChars)
    }

    @Test
    fun `estimate counts tool call arguments`() {
        val builder = AgentContextBuilder(limits)
        val messages = builder.initialMessages("g")
        builder.appendAssistantTurn(
            messages,
            "",
            listOf(AIToolCall("1", "read_file", "{\"path\":\"" + "a".repeat(500) + "\"}"))
        )
        assertTrue(builder.estimateChars(messages) > 500)
    }

    @Test
    fun `assistant turn truncates oversized text`() {
        val builder = AgentContextBuilder(limits)
        val messages = builder.initialMessages("g")
        builder.appendAssistantTurn(messages, "z".repeat(100_000), emptyList())
        assertTrue(messages.last().content.length <= limits.maxToolOutputChars + 64)
        assertFalse(messages.last().content.contains("z".repeat(50_000)))
    }
}

private class PersistenceToolFactory(private val tools: List<Tool>) : AgentToolFactory {
    override val editorBridge: EditorBridge = FakeEditorBridge()
    override val fileMutationToolNames: Set<String> = setOf("write_file")
    override fun create(limits: AgentLoopLimits): ToolRegistry = ToolRegistry(tools)
    override fun clearTaskState(taskId: String) = Unit
}

class AgentTaskPersistenceTest {

    private lateinit var root: File

    @Test
    fun `task row is created, progressed and finished`() = runBlocking {
        root = createTempProject()
        val tool = FakeTool("read_file")
        val taskStore = FakeAgentTaskStore()
        val eventStore = FakeAgentEventStore()
        val historyStore = FakeAgentHistoryStore()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val runtime = AgentRuntime(
            providerManager = FakeProviderManager(
                provider = ScriptedProvider(
                    turns = listOf(
                        ProviderTurn.Tools("", listOf(AIToolCall("c1", "read_file", """{"path":"a"}"""))),
                        ProviderTurn.Text("done")
                    )
                )
            ),
            aiSettingsRepository = fakeAiSettingsRepository(
                AISettingsEntity(
                    defaultProviderId = "scripted",
                    defaultModelId = "scripted-model",
                    agentMaxIterations = 5
                )
            ),
            projectLocator = { id -> fakeProject(root, id = id) },
            conversationPort = FakeConversationPort(),
            toolFactory = PersistenceToolFactory(listOf(tool)),
            permissionManager = PermissionManager(ApprovalBroker()),
            broker = ApprovalBroker(),
            processRegistry = AgentProcessRegistry(),
            taskStore = taskStore,
            eventStore = eventStore,
            historyStore = historyStore,
            permissionStore = FakeAgentPermissionStore(),
            dispatchers = AgentTestDispatchers,
            scope = scope
        )
        try {
            val started = runtime.startTask(AgentTaskRequest("do work", "p1", "c1"))
            assertTrue(started is AgentStartResult.Started)
            withTimeout(3_000) { while (runtime.state.value?.state != AgentState.COMPLETED) yield() }

            val task = taskStore.tasks.values.single()
            assertEquals(AgentState.COMPLETED.name, task.state)
            assertEquals(2, task.iterationCount)
            assertEquals(1, task.toolCallCount)
            assertEquals("do work", task.goal)
            assertEquals("p1", task.projectId)
            assertTrue(historyStore.entries.single().actionSummary.contains("read_file"))
            assertTrue(eventStore.events.any { it.second.startsWith("read_file") })
        } finally {
            scope.cancel()
            deleteTempProject(root)
        }
    }
}
