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

class GeminiProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: GeminiProvider
    private val credentialManager = TestCredentialManager()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val credentialId = credentialManager.store("gemini-key")
        provider = GeminiProvider(
            httpClient = AIHttpClient(callTimeoutSeconds = 10),
            credentials = credentialManager,
            credentialId = credentialId,
            baseUrlOverride = server.url("/v1beta").toString().trimEnd('/'),
            allowInsecureLocalHost = true
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `streams gemini sse chunks with usage`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hola\"}],\"role\":\"model\"}}],\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":4,\"totalTokenCount\":13}}\n\n" +
                        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"!\"}],\"role\":\"model\"}}],\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":5,\"totalTokenCount\":14}}\n\n"
            ).setHeader("Content-Type", "text/event-stream")
        )
        val request = AIRequest(modelId = "gemini-test", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")))
        val events = provider.generate(request).toList()
        val deltas = events.filterIsInstance<AIResponseEvent.TextDelta>().map { it.text }
        assertEquals(listOf("Hola", "!"), deltas)
        val usage = events.filterIsInstance<AIResponseEvent.Usage>().last()
        assertEquals(9L, usage.usage.inputTokens)
        assertEquals(14L, usage.usage.totalTokens)
        assertTrue(events.filterIsInstance<AIResponseEvent.Completed>().isNotEmpty())
    }

    @Test
    fun `maps stream error payload to normalized error`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "data: {\"error\":{\"code\":429,\"message\":\"Resource exhausted\"}}\n\n"
            ).setHeader("Content-Type", "text/event-stream")
        )
        val request = AIRequest(modelId = "m", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")))
        val events = provider.generate(request).toList()
        val error = events.filterIsInstance<AIResponseEvent.Error>().first()
        assertTrue(error.error is AIError.RateLimitError)
    }

    @Test
    fun `non streaming extracts candidates text`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Answer\"}],\"role\":\"model\"}}],\"usageMetadata\":{\"promptTokenCount\":2,\"candidatesTokenCount\":3}}"
            ).setHeader("Content-Type", "application/json")
        )
        val request = AIRequest(modelId = "m", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")), stream = false)
        val events = provider.generate(request).toList()
        assertEquals(listOf("Answer"), events.filterIsInstance<AIResponseEvent.TextDelta>().map { it.text })
        assertEquals(2L, events.filterIsInstance<AIResponseEvent.Usage>().first().usage.inputTokens)
    }

    @Test
    fun `model listing filters non-generateContent models`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"models\":[" +
                        "{\"name\":\"models/gem-a\",\"displayName\":\"Gem A\",\"supportedGenerationMethods\":[\"generateContent\"],\"inputTokenLimit\":1000000}," +
                        "{\"name\":\"models/embed-x\",\"displayName\":\"Embed\",\"supportedGenerationMethods\":[\"embedContent\"]}" +
                        "]}"
            )
        )
        val models = provider.getModels().getOrThrow()
        assertEquals(listOf("gem-a"), models.map { it.modelId })
        assertEquals(1000000L, models.first().contextWindow)
    }

    @Test
    fun `maps http 403 to authentication error like bad gemini keys`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("{\"error\":{\"code\":403,\"message\":\"API key not valid\"}}"))
        val request = AIRequest(modelId = "m", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")), stream = false)
        val events = provider.generate(request).toList()
        val error = events.filterIsInstance<AIResponseEvent.Error>().first().error
        assertTrue(error is AIError.AuthenticationError || error is AIError.AuthorizationError)
    }

    @Test
    fun `sends x-goog-api-key header`() = runTest {
        server.enqueue(
            MockResponse().setBody("data: [DONE]\n\n").setHeader("Content-Type", "text/event-stream")
        )
        val request = AIRequest(modelId = "m", messages = listOf(AIMessage(role = AIMessageRole.USER, content = "hi")))
        provider.generate(request).toList()
        assertEquals("gemini-key", server.takeRequest().getHeader("x-goog-api-key"))
    }
}
