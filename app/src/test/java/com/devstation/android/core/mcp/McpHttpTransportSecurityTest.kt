package com.devstation.android.core.mcp

import com.devstation.android.core.security.policy.NetworkSecurityPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 8.1 §38: HTTP transport security tests. Every security violation must fail closed.
 */
class McpHttpTransportSecurityTest {

    private fun transport(
        connectTimeoutMs: Long = 5_000,
        requestTimeoutMs: Long = 5_000
    ) = McpHttpTransport(
        networkPolicy = NetworkSecurityPolicy(),
        connectTimeoutMs = connectTimeoutMs,
        requestTimeoutMs = requestTimeoutMs
    )

    private fun config(url: String, id: String = "srv-${System.nanoTime()}") = McpServerConfig(
        id = id,
        name = "test",
        transportType = McpTransportType.HTTP,
        endpoint = url
    )

    private fun rpcResponse(id: String, body: String) =
        """{"jsonrpc":"2.0","id":"$id",$body}"""

    // §38.1/§38.2 malformed URL, unsupported scheme
    @Test
    fun `malformed url is rejected`() {
        val t = transport()
        assertTrue(t.validateEndpoint("not a url at all").isFailure)
        assertTrue(t.validateEndpoint("").isFailure)
    }

    @Test
    fun `forbidden schemes are rejected`() {
        val t = transport()
        listOf(
            "file:///etc/passwd",
            "content://media/external",
            "data:text/plain,hello",
            "javascript:alert(1)",
            "intent://x",
            "ftp://example.com/file",
            "ws://example.com"
        ).forEach { url ->
            assertTrue("should reject: $url", t.validateEndpoint(url).isFailure)
        }
    }

    // §38.3 plaintext HTTP when HTTPS is required
    @Test
    fun `plaintext http off loopback is rejected`() {
        val t = transport()
        assertTrue(t.validateEndpoint("http://mcp.example.com/rpc").isFailure)
        assertTrue(t.validateEndpoint("http://10.0.0.5/rpc").isFailure)
    }

    @Test
    fun `loopback http is permitted but external https is required`() {
        val t = transport()
        assertTrue(t.validateEndpoint("http://127.0.0.1:9000/rpc").isSuccess)
        assertTrue(t.validateEndpoint("http://localhost:9000/rpc").isSuccess)
        assertTrue(t.validateEndpoint("https://mcp.example.com/rpc").isSuccess)
    }

    // §38.4–§38.6 network policy integration
    @Test
    fun `destination classification is delegated to the network policy`() {
        val t = transport()
        // Private addresses keep their LOCAL classification (never "harmless").
        assertEquals(
            com.devstation.android.core.security.policy.NetworkIntent.LOCAL_NETWORK,
            NetworkSecurityPolicy().classifyHost("192.168.1.10")
        )
        assertTrue(t.validateEndpoint("https://192.168.1.10/rpc").isSuccess)
    }

    // §38.8/§38.9 redirect to another host must be re-validated
    @Test
    fun `redirect is not followed blindly`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://evil.example.com/steal")
        )
        val t = transport()
        val connection = t.connect(config(server.url("/rpc").toString())).getOrThrow()
        val result = t.send(connection, McpJsonRpcRequest(method = "tools/list"))
        // A redirect to a plaintext or non-JSON destination fails closed.
        assertTrue(result.isFailure)
        server.shutdown()
    }

    @Test
    fun `redirect loop fails closed`() = runBlocking {
        val server = MockWebServer()
        server.start()
        repeat(6) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", server.url("/rpc").toString())
            )
        }
        val t = transport()
        val connection = t.connect(config(server.url("/rpc").toString())).getOrThrow()
        val result = t.send(connection, McpJsonRpcRequest(method = "tools/list"))
        assertTrue(result.isFailure)
        server.shutdown()
    }

    // §38.10 oversized response
    @Test
    fun `oversized response is rejected`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val huge = "x".repeat(MAX_MCP_RESPONSE_CHARS + 10_000)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(huge)
        )
        val t = transport(requestTimeoutMs = 20_000)
        val connection = t.connect(config(server.url("/rpc").toString())).getOrThrow()
        val result = t.send(connection, McpJsonRpcRequest(method = "tools/list"))
        assertTrue(result.isFailure)
        server.shutdown()
    }

    // §38.11 malformed JSON
    @Test
    fun `malformed json fails closed`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{ this is not json")
        )
        val t = transport()
        val connection = t.connect(config(server.url("/rpc").toString())).getOrThrow()
        val result = t.send(connection, McpJsonRpcRequest(method = "tools/list"))
        assertTrue(result.isFailure)
        server.shutdown()
    }

    // §38.12 invalid MCP structure (non-JSON content type)
    @Test
    fun `non-json content type is rejected`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>hi</html>")
        )
        val t = transport()
        val connection = t.connect(config(server.url("/rpc").toString())).getOrThrow()
        val result = t.send(connection, McpJsonRpcRequest(method = "tools/list"))
        assertTrue(result.isFailure)
        server.shutdown()
    }

    // §38.13 timeout
    @Test
    fun `no-response server times out`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val t = transport(requestTimeoutMs = 1_000)
        val connection = t.connect(config(server.url("/rpc").toString())).getOrThrow()
        val start = System.currentTimeMillis()
        val result = t.send(connection, McpJsonRpcRequest(method = "tools/list"))
        assertTrue(result.isFailure)
        assertTrue(System.currentTimeMillis() - start < 15_000)
        server.shutdown()
    }

    // §38.16 authorization header leakage — the transport never sends or logs credentials
    @Test
    fun `no authorization header is sent`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(rpcResponse("x", "\"result\":{\"tools\":[]}"))
        )
        val t = transport()
        val connection = t.connect(config(server.url("/rpc").toString())).getOrThrow()
        t.send(connection, McpJsonRpcRequest(method = "tools/list"))
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
        server.shutdown()
    }

    // §38.17 concurrent request correlation
    @Test
    fun `response id must match request id`() {
        val t = transport()
        val request = McpJsonRpcRequest(method = "tools/list")
        val method = t.javaClass.getDeclaredMethod(
            "parseResponse", String::class.java, McpJsonRpcRequest::class.java
        )
        method.isAccessible = true
        // Matching id parses fine
        val matching = runCatching {
            method.invoke(t, rpcResponse(request.id, "\"result\":{}"), request)
        }
        assertTrue(matching.isSuccess)
        // Mismatched id must throw (reflection wraps in InvocationTargetException)
        val mismatch = runCatching {
            method.invoke(t, rpcResponse("other-id", "\"result\":{}"), request)
        }
        assertTrue(mismatch.isFailure)
        var cause: Throwable? = mismatch.exceptionOrNull()
        while (cause?.cause != null) cause = cause.cause
        assertTrue(cause is McpTransportException)
    }

    // happy path: a valid JSON-RPC response round-trips
    @Test
    fun `valid response round trips`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val t = transport()
        val connection = t.connect(config(server.url("/rpc").toString())).getOrThrow()
        val request = McpJsonRpcRequest(method = "tools/list")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(rpcResponse(request.id, "\"result\":{\"tools\":[]}"))
        )
        val result = t.send(connection, request)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isSuccess)
        server.shutdown()
    }

    // §13 error status codes fail closed
    @Test
    fun `http error status fails closed`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val t = transport()
        val connection = t.connect(config(server.url("/rpc").toString())).getOrThrow()
        val result = t.send(connection, McpJsonRpcRequest(method = "tools/list"))
        assertTrue(result.isFailure)
        server.shutdown()
    }
}
