package com.devstation.android.core.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderToolCallingCapabilityTest {

    @Test
    fun `all built-in adapters advertise tool calling after phase 6`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val credentials = TestCredentialManager()
            val credentialId = credentials.store("k")
            val http = AIHttpClient(callTimeoutSeconds = 5)
            val base = server.url("").toString().trimEnd('/')

            val openAi = OpenAICompatibleProvider(
                providerId = "openai",
                displayName = "OpenAI-compatible",
                defaultBaseUrl = "$base/v1",
                httpClient = http,
                credentials = credentials,
                credentialId = credentialId,
                allowInsecureLocalHost = true
            )
            val anthropic = AnthropicProvider(
                httpClient = http,
                credentials = credentials,
                credentialId = credentialId,
                baseUrlOverride = base,
                allowInsecureLocalHost = true
            )
            val gemini = GeminiProvider(
                httpClient = http,
                credentials = credentials,
                credentialId = credentialId,
                baseUrlOverride = base,
                allowInsecureLocalHost = true
            )

            assertTrue(openAi.capabilities.toolCalling)
            assertTrue(anthropic.capabilities.toolCalling)
            assertTrue(gemini.capabilities.toolCalling)
        } finally {
            server.shutdown()
        }
    }
}

class OpenAIToolCallingTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAICompatibleProvider
    private val credentials = TestCredentialManager()

    private val toolSpec = AIToolSpec(
        name = "read_file",
        description = "Read a project file",
        parameters = listOf(AIToolParameter("path", AIToolParameterType.STRING, "path"))
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAICompatibleProvider(
            providerId = "openai",
            displayName = "OpenAI-compatible",
            defaultBaseUrl = server.url("/v1").toString().trimEnd('/'),
            httpClient = AIHttpClient(callTimeoutSeconds = 10),
            credentials = credentials,
            credentialId = credentials.store("test-key"),
            allowInsecureLocalHost = true
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `assembles streamed tool_call fragments and advertises the schema`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"pa\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"th\\\":\\\"src/App.kt\\\"}\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                    "data: [DONE]\n\n"
            ).setHeader("Content-Type", "text/event-stream")
        )

        val request = AIRequest(
            modelId = "test-model",
            messages = listOf(AIMessage(role = AIMessageRole.USER, content = "read it")),
            tools = listOf(toolSpec),
            toolChoice = "auto"
        )
        val events = provider.generate(request).toList()

        val calls = events.filterIsInstance<AIResponseEvent.ToolCallRequested>().single().calls
        assertEquals(1, calls.size)
        assertEquals("call_1", calls.single().id)
        assertEquals("read_file", calls.single().name)
        assertTrue(calls.single().argumentsJson.contains("src/App.kt"))
        assertNotNull(events.filterIsInstance<AIResponseEvent.Completed>().firstOrNull())

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"tools\""))
        assertTrue(body.contains("\"tool_choice\":\"auto\""))
        assertTrue(body.contains("\"read_file\""))
        assertTrue(body.contains("\"required\":[\"path\"]"))
    }

    @Test
    fun `parses non-streaming tool_calls`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null," +
                    "\"tool_calls\":[{\"id\":\"call_9\",\"type\":\"function\",\"function\":{\"name\":\"list_directory\",\"arguments\":\"{\\\"path\\\":\\\".\\\"}\"}}]}}]}"
            ).setHeader("Content-Type", "application/json")
        )
        val request = AIRequest(
            modelId = "m",
            messages = listOf(AIMessage(role = AIMessageRole.USER, content = "list")),
            tools = listOf(toolSpec),
            stream = false
        )
        val events = provider.generate(request).toList()
        val call = events.filterIsInstance<AIResponseEvent.ToolCallRequested>().single().calls.single()
        assertEquals("list_directory", call.name)
        assertEquals("call_9", call.id)
    }

    @Test
    fun `serializes assistant tool calls and tool results back to the provider`() = runTest {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n").setHeader("Content-Type", "text/event-stream"))
        val request = AIRequest(
            modelId = "m",
            messages = listOf(
                AIMessage(role = AIMessageRole.USER, content = "read it"),
                AIMessage(
                    role = AIMessageRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(AIToolCall("call_1", "read_file", "{\"path\":\"a\"}"))
                ),
                AIMessage(
                    role = AIMessageRole.TOOL,
                    content = "{\"status\":\"success\",\"output\":\"[FILE CONTENT] hi\"}",
                    toolCallId = "call_1",
                    toolName = "read_file"
                )
            ),
            tools = listOf(toolSpec)
        )
        provider.generate(request).toList()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"role\":\"tool\""))
        assertTrue(body.contains("\"tool_call_id\":\"call_1\""))
        assertTrue(body.contains("\"tool_calls\""))
        assertTrue(body.contains("[FILE CONTENT]"))
    }
}

class AnthropicToolCallingTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: AnthropicProvider
    private val credentials = TestCredentialManager()

    private val toolSpec = AIToolSpec(
        name = "read_file",
        description = "Read a project file",
        parameters = listOf(AIToolParameter("path", AIToolParameterType.STRING, "path"))
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = AnthropicProvider(
            httpClient = AIHttpClient(callTimeoutSeconds = 10),
            credentials = credentials,
            credentialId = credentials.store("test-key"),
            baseUrlOverride = server.url("").toString().trimEnd('/'),
            allowInsecureLocalHost = true
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `parses streamed tool_use blocks`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"usage\":{\"input_tokens\":10}}}\n\n" +
                    "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read_file\",\"input\":{}}}\n\n" +
                    "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\\\"src/App.kt\\\"}\"}}\n\n" +
                    "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                    "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}\n\n" +
                    "data: {\"type\":\"message_stop\"}\n\n"
            ).setHeader("Content-Type", "text/event-stream")
        )
        val request = AIRequest(
            modelId = "claude-test",
            messages = listOf(AIMessage(role = AIMessageRole.USER, content = "read it")),
            tools = listOf(toolSpec)
        )
        val events = provider.generate(request).toList()
        val call = events.filterIsInstance<AIResponseEvent.ToolCallRequested>().single().calls.single()
        assertEquals("toolu_1", call.id)
        assertEquals("read_file", call.name)
        assertTrue(call.argumentsJson.contains("src/App.kt"))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"input_schema\""))
        assertTrue(body.contains("\"tool_choice\""))
    }

    @Test
    fun `parses non-streaming tool_use content blocks`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"id\":\"msg_2\",\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"list_directory\",\"input\":{\"path\":\".\"}}]," +
                    "\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}"
            )
        )
        val request = AIRequest(
            modelId = "claude-test",
            messages = listOf(AIMessage(role = AIMessageRole.USER, content = "list")),
            tools = listOf(toolSpec),
            stream = false
        )
        val events = provider.generate(request).toList()
        val call = events.filterIsInstance<AIResponseEvent.ToolCallRequested>().single().calls.single()
        assertEquals("list_directory", call.name)
        assertEquals("toolu_2", call.id)
    }

    @Test
    fun `groups tool results into a single user turn`() = runTest {
        server.enqueue(MockResponse().setBody("{\"id\":\"m\",\"content\":[],\"usage\":{}}"))
        val request = AIRequest(
            modelId = "m",
            messages = listOf(
                AIMessage(role = AIMessageRole.USER, content = "do it"),
                AIMessage(
                    role = AIMessageRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(
                        AIToolCall("toolu_1", "read_file", "{\"path\":\"a\"}"),
                        AIToolCall("toolu_2", "list_directory", "{\"path\":\".\"}")
                    )
                ),
                AIMessage(role = AIMessageRole.TOOL, content = "{\"status\":\"success\"}", toolCallId = "toolu_1", toolName = "read_file"),
                AIMessage(role = AIMessageRole.TOOL, content = "{\"status\":\"success\"}", toolCallId = "toolu_2", toolName = "list_directory")
            ),
            tools = listOf(toolSpec),
            stream = false
        )
        provider.generate(request).toList()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"type\":\"tool_result\""))
        assertTrue(body.contains("\"tool_use_id\":\"toolu_1\""))
        assertTrue(body.contains("\"tool_use_id\":\"toolu_2\""))
        assertTrue(body.contains("\"type\":\"tool_use\""))
    }
}

class GeminiToolCallingTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: GeminiProvider
    private val credentials = TestCredentialManager()

    private val toolSpec = AIToolSpec(
        name = "read_file",
        description = "Read a project file",
        parameters = listOf(AIToolParameter("path", AIToolParameterType.STRING, "path"))
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = GeminiProvider(
            httpClient = AIHttpClient(callTimeoutSeconds = 10),
            credentials = credentials,
            credentialId = credentials.store("test-key"),
            baseUrlOverride = server.url("").toString().trimEnd('/'),
            allowInsecureLocalHost = true
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `extracts functionCall parts and sends functionDeclarations`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"read_file\",\"args\":{\"path\":\"src/App.kt\"}}}]}}]}\n\n" +
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":4}}\n\n"
            ).setHeader("Content-Type", "text/event-stream")
        )
        val request = AIRequest(
            modelId = "gemini-test",
            messages = listOf(AIMessage(role = AIMessageRole.USER, content = "read it")),
            tools = listOf(toolSpec)
        )
        val events = provider.generate(request).toList()
        val calls = events.filterIsInstance<AIResponseEvent.ToolCallRequested>().single().calls
        assertEquals(1, calls.size)
        assertEquals("read_file", calls.single().name)
        assertTrue(calls.single().argumentsJson.contains("src/App.kt"))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"functionDeclarations\""))
        assertTrue(body.contains("\"type\":\"OBJECT\""))
        assertTrue(body.contains("\"type\":\"STRING\""))
    }

    @Test
    fun `serializes tool results as functionResponse parts`() = runTest {
        server.enqueue(MockResponse().setBody("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}"))
        val request = AIRequest(
            modelId = "gemini-test",
            messages = listOf(
                AIMessage(role = AIMessageRole.USER, content = "do it"),
                AIMessage(
                    role = AIMessageRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(AIToolCall("call_0", "read_file", "{\"path\":\"a\"}"))
                ),
                AIMessage(role = AIMessageRole.TOOL, content = "{\"status\":\"success\"}", toolCallId = "call_0", toolName = "read_file")
            ),
            tools = listOf(toolSpec),
            stream = false
        )
        provider.generate(request).toList()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"functionResponse\""))
        assertTrue(body.contains("\"functionCall\""))
        assertTrue(body.contains("\"name\":\"read_file\""))
    }

    @Test
    fun `gemini adapters stay usable for plain chat without tools`() = runTest {
        server.enqueue(MockResponse().setBody("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"}]}}]}"))
        val request = AIRequest(
            modelId = "gemini-test",
            messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")),
            stream = false
        )
        val events = provider.generate(request).toList()
        assertEquals(listOf("hello"), events.filterIsInstance<AIResponseEvent.TextDelta>().map { it.text })
        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("functionDeclarations"))
    }
}
