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

class OpenAICompatibleProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAICompatibleProvider
    private val credentialManager = TestCredentialManager()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val credentialId = credentialManager.store("test-key-123")
        provider = OpenAICompatibleProvider(
            providerId = "openai",
            displayName = "OpenAI-compatible",
            defaultBaseUrl = server.url("/v1").toString().trimEnd('/'),
            httpClient = AIHttpClient(callTimeoutSeconds = 10),
            credentials = credentialManager,
            credentialId = credentialId,
            allowInsecureLocalHost = true
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `streams text deltas from chat completions chunks`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":7,\"total_tokens\":12}}\n\n" +
                        "data: [DONE]\n\n"
            ).setHeader("Content-Type", "text/event-stream")
        )
        val request = AIRequest(modelId = "test-model", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")))
        val events = provider.generate(request).toList()

        val started = events.filterIsInstance<AIResponseEvent.Started>().first()
        assertEquals("openai", started.providerId)
        val deltas = events.filterIsInstance<AIResponseEvent.TextDelta>().map { it.text }
        assertEquals(listOf("Hello", " world"), deltas)
        val usage = events.filterIsInstance<AIResponseEvent.Usage>().firstOrNull()
        assertNotNull("events=$events", usage)
        assertEquals(5L, usage!!.usage.inputTokens)
        assertEquals(7L, usage.usage.outputTokens)
        assertNotNull("events=$events", events.filterIsInstance<AIResponseEvent.Completed>().firstOrNull())
    }

    @Test
    fun `non streaming returns full text as one delta`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Full response.\"}}]," +
                        "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":7}}"
            ).setHeader("Content-Type", "application/json")
        )
        val request = AIRequest(modelId = "test-model", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")), stream = false)
        val events = provider.generate(request).toList()
        val deltas = events.filterIsInstance<AIResponseEvent.TextDelta>().map { it.text }
        assertEquals(listOf("Full response."), deltas)
        assertEquals(3L, events.filterIsInstance<AIResponseEvent.Usage>().first().usage.inputTokens)
    }

    @Test
    fun `maps 401 to authentication error`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("{\"error\":{\"message\":\"Invalid API key\",\"type\":\"invalid_request_error\"}}")
        )
        val request = AIRequest(modelId = "m", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")))
        val events = provider.generate(request).toList()
        val error = events.filterIsInstance<AIResponseEvent.Error>().firstOrNull()
        assertTrue(error?.error is AIError.AuthenticationError)
    }

    @Test
    fun `maps 429 to rate limit error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\":{\"message\":\"slow down\"}}"))
        val request = AIRequest(modelId = "m", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")))
        val events = provider.generate(request).toList()
        assertTrue(events.filterIsInstance<AIResponseEvent.Error>().first().error is AIError.RateLimitError)
    }

    @Test
    fun `lists models from data array`() = runTest {
        server.enqueue(
            MockResponse().setBody("{\"data\":[{\"id\":\"gpt-a\",\"object\":\"model\"},{\"id\":\"gpt-b\",\"object\":\"model\"}]}")
        )
        val models = provider.getModels().getOrThrow()
        assertEquals(listOf("gpt-a", "gpt-b"), models.map { it.modelId })
    }

    @Test
    fun `test connection succeeds on 200`() = runTest {
        server.enqueue(MockResponse().setBody("{\"data\":[]}"))
        val health = provider.testConnection().getOrThrow()
        assertTrue(health.authenticated)
        assertTrue(health.reachable)
    }

    @Test
    fun `sends bearer auth header and stream flag`() = runTest {
        server.enqueue(
            MockResponse().setBody("data: [DONE]\n\n").setHeader("Content-Type", "text/event-stream")
        )
        val request = AIRequest(modelId = "test-model", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")))
        provider.generate(request).toList()
        val recorded = server.takeRequest()
        assertEquals("Bearer test-key-123", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("include_usage"))
    }
}
