package com.devstation.android.core.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: AnthropicProvider
    private val credentialManager = TestCredentialManager()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val credentialId = credentialManager.store("anthropic-key")
        provider = AnthropicProvider(
            httpClient = AIHttpClient(callTimeoutSeconds = 10),
            credentials = credentialManager,
            credentialId = credentialId,
            baseUrlOverride = server.url("/").toString().trimEnd('/'),
            allowInsecureLocalHost = true
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val streamBody =
        "event: message_start\n" +
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}\n\n" +
                "event: content_block_start\n" +
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
                "event: ping\n" +
                "data: {\"type\":\"ping\"}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"!\"}}\n\n" +
                "event: content_block_stop\n" +
                "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                "event: message_delta\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":15}}\n\n" +
                "event: message_stop\n" +
                "data: {\"type\":\"message_stop\"}\n\n"

    @Test
    fun `streams text deltas with usage from message events`() = runTest {
        server.enqueue(MockResponse().setBody(streamBody).setHeader("Content-Type", "text/event-stream"))
        val request = AIRequest(modelId = "claude-test", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")))
        val events = provider.generate(request).toList()

        assertEquals(listOf("Hello", "!"), events.filterIsInstance<AIResponseEvent.TextDelta>().map { it.text })
        val usage = events.filterIsInstance<AIResponseEvent.Usage>().first().usage
        assertEquals(25L, usage.inputTokens)
        assertEquals(15L, usage.outputTokens)
        assertEquals(40L, usage.totalTokens)
        assertTrue(events.filterIsInstance<AIResponseEvent.Completed>().isNotEmpty())
    }

    @Test
    fun `emits thinking deltas for thinking blocks`() = runTest {
        val thinkingBody =
            "event: content_block_delta\n" +
                    "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"reasoning...\"}}\n\n" +
                    "event: content_block_delta\n" +
                    "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Answer\"}}\n\n"
        server.enqueue(MockResponse().setBody(thinkingBody).setHeader("Content-Type", "text/event-stream"))
        val request = AIRequest(modelId = "claude-test", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")))
        val events = provider.generate(request).toList()
        assertEquals(listOf("reasoning..."), events.filterIsInstance<AIResponseEvent.ThinkingDelta>().map { it.text })
        assertEquals(listOf("Answer"), events.filterIsInstance<AIResponseEvent.TextDelta>().map { it.text })
    }

    @Test
    fun `maps stream error event to server error`() = runTest {
        val errorBody =
            "event: error\n" +
                    "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n"
        server.enqueue(MockResponse().setBody(errorBody).setHeader("Content-Type", "text/event-stream"))
        val request = AIRequest(modelId = "m", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")))
        val events = provider.generate(request).toList()
        assertTrue(events.filterIsInstance<AIResponseEvent.Error>().first().error is AIError.ServerError)
    }

    @Test
    fun `non streaming parses content blocks and usage`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"id\":\"msg_x\",\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":\"Full answer\"}]," +
                        "\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}"
            ).setHeader("Content-Type", "application/json")
        )
        val request = AIRequest(modelId = "m", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")), stream = false)
        val events = provider.generate(request).toList()
        assertEquals(listOf("Full answer"), events.filterIsInstance<AIResponseEvent.TextDelta>().map { it.text })
        val usage = events.filterIsInstance<AIResponseEvent.Usage>().first().usage
        assertEquals(10L, usage.inputTokens)
        assertEquals(20L, usage.outputTokens)
    }

    @Test
    fun `maps 401 to authentication error`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}")
        )
        val request = AIRequest(modelId = "m", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")))
        val events = provider.generate(request).toList()
        assertTrue(events.filterIsInstance<AIResponseEvent.Error>().first().error is AIError.AuthenticationError)
    }

    @Test
    fun `lists models with display names and context`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"data\":[" +
                        "{\"id\":\"claude-x\",\"display_name\":\"Claude X\",\"context_window\":200000}" +
                        "]}"
            )
        )
        val models = provider.getModels().getOrThrow()
        assertEquals(listOf("claude-x"), models.map { it.modelId })
        assertEquals("Claude X", models.first().displayName)
        assertEquals(200000L, models.first().contextWindow)
    }

    @Test
    fun `sends anthropic version and api key headers with stream true`() = runTest {
        server.enqueue(MockResponse().setBody("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n").setHeader("Content-Type", "text/event-stream"))
        val request = AIRequest(modelId = "claude-test", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")))
        provider.generate(request).toList()
        val recorded = server.takeRequest()
        assertEquals("anthropic-key", recorded.getHeader("x-api-key"))
        assertEquals(AnthropicProvider.ANTHROPIC_VERSION, recorded.getHeader("anthropic-version"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"max_tokens\":"))
    }

    @Test
    fun `system role is not sent as message but as system field`() = runTest {
        server.enqueue(MockResponse().setBody("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n").setHeader("Content-Type", "text/event-stream"))
        val request = AIRequest(
            modelId = "m",
            messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")),
            systemInstruction = "be brief"
        )
        provider.generate(request).toList()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"system\":\"be brief\""))
        assertTrue(!body.contains("\"role\":\"system\""))
    }
}
