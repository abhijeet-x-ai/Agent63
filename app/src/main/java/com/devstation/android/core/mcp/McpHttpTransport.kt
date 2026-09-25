package com.devstation.android.core.mcp

import com.devstation.android.core.security.policy.NetworkIntent
import com.devstation.android.core.security.policy.NetworkSecurityPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Phase 8.1 §13–§19: production MCP HTTP transport.
 *
 * Security invariants:
 * - Endpoints are validated before every request: HTTPS required except for loopback hosts;
 *   `file:`/`content:`/`data:`/`javascript:`/`intent:` and every custom scheme are rejected (§15).
 * - Redirects are never followed automatically. A 3xx Location is re-validated as an independent
 *   destination, so a redirect from an approved host to a denied host fails closed (§16).
 * - Responses are bounded (§18), must be 2xx with a JSON content type, and must parse as MCP
 *   JSON-RPC; anything else degrades to a transport exception (§19).
 * - Requests are id-correlated by [McpJsonRpcRequest.id]; concurrency is bounded (§21/§18) and every
 *   call is cancellable (§22).
 */
class McpHttpTransport(
    private val networkPolicy: NetworkSecurityPolicy = NetworkSecurityPolicy(),
    private val connectTimeoutMs: Long = DEFAULT_MCP_CONNECT_TIMEOUT_MS,
    private val requestTimeoutMs: Long = DEFAULT_MCP_REQUEST_TIMEOUT_MS,
    /** Plain HTTP is only ever permitted for loopback destinations (§15). */
    private val allowLoopbackHttp: Boolean = true,
    private val maxConcurrentRequests: Int = MAX_CONCURRENT_REQUESTS
) : McpTransport {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val semaphore = Semaphore(maxConcurrentRequests)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(requestTimeoutMs + CONNECT_SLACK_MS, TimeUnit.MILLISECONDS)
        .build()

    override val isAvailable: Boolean = true

    override suspend fun connect(config: McpServerConfig): Result<McpConnection> {
        val endpoint = config.endpoint
            ?: return Result.failure(McpTransportException("HTTP transport requires an endpoint."))
        return validateEndpoint(endpoint).map {
            // Bind the validated endpoint for this server id so send() can use it.
            endpoints[config.id] = endpoint
            McpConnection(serverId = config.id, capabilities = emptyList())
        }
    }

    override suspend fun send(
        connection: McpConnection,
        request: McpJsonRpcRequest
    ): Result<McpJsonRpcResponse> = withContext(Dispatchers.IO) {
        runCatching {
            // The endpoint is re-derived from the stored config by the manager; the connection
            // carries only identity, so the transport keeps a per-connection endpoint binding.
            val endpoint = endpoints[connection.serverId]
                ?: throw McpTransportException("MCP HTTP endpoint is not bound for this connection.")
            semaphore.withPermit { exchange(endpoint, request, maxRedirects = MAX_REDIRECTS) }
        }
    }

    override suspend fun close(connection: McpConnection) {
        endpoints.remove(connection.serverId)
    }

    // ---- endpoint validation (§14/§15) ----

    internal fun validateEndpoint(raw: String): Result<java.net.URI> {
        val uri = try {
            java.net.URI(raw.trim())
        } catch (e: Exception) {
            return Result.failure(McpTransportException("Invalid MCP endpoint URL."))
        }
        val scheme = uri.scheme?.lowercase()
            ?: return Result.failure(McpTransportException("MCP endpoint has no scheme."))
        if (FORBIDDEN_SCHEMES.contains(scheme)) {
            return Result.failure(McpTransportException("Scheme '$scheme:' is not allowed for MCP endpoints."))
        }
        val isHttpLike = scheme == "https" || scheme == "http"
        if (!isHttpLike) {
            return Result.failure(McpTransportException("Scheme '$scheme:' is not supported for MCP endpoints."))
        }
        val host = uri.host
            ?: return Result.failure(McpTransportException("MCP endpoint has no host."))
        val intent = networkPolicy.classifyHost(host)
        if (scheme == "http") {
            val isLoopback = intent == NetworkIntent.LOCAL_NETWORK &&
                (host == "localhost" || host == "127.0.0.1" || host == "::1")
            if (!isLoopback || !allowLoopbackHttp) {
                return Result.failure(
                    McpTransportException("Plaintext HTTP is only allowed for localhost MCP endpoints.")
                )
            }
        }
        return Result.success(uri)
    }

    // ---- request/response exchange ----

    private suspend fun exchange(
        rawEndpoint: String,
        request: McpJsonRpcRequest,
        maxRedirects: Int
    ): McpJsonRpcResponse {
        var currentUrl = rawEndpoint
        var redirects = 0
        while (true) {
            val uri = validateEndpoint(currentUrl).getOrElse { throw it }
            // §14: the destination is independently classified; the classification is surfaced for
            // the caller's audit (McpServerManager logs connection activity).
            val intent = networkPolicy.classifyHost(uri.host)
            check(intent != NetworkIntent.NONE) { "MCP endpoint has no network classification." }

            val body = buildRequestBody(request)
            val call = client.newCall(
                Request.Builder()
                    .url(uri.toURL())
                    .header("Accept", "application/json")
                    .post(body)
                    .build()
            )
            val response = call.awaitSuspending()
            response.use { resp ->
                when {
                    resp.isRedirect -> {
                        redirects++
                        if (redirects > maxRedirects) {
                            throw McpTransportException("MCP endpoint redirected too many times.")
                        }
                        // §16: each redirect target is validated as a brand-new destination.
                        val location = resp.header("Location")
                            ?: throw McpTransportException("Redirect without a Location header.")
                        currentUrl = java.net.URI(currentUrl).resolve(location).toString()
                        null // continue the loop with the new URL
                    }
                    !resp.isSuccessful -> {
                        throw McpTransportException("MCP endpoint returned HTTP ${resp.code}.")
                    }
                    !isJsonContentType(resp) -> {
                        throw McpTransportException("MCP endpoint returned a non-JSON content type.")
                    }
                    else -> {
                        val text = boundedBody(resp)
                        parseResponse(text, request)
                    }
                }
            }?.let { return it }
        }
    }

    private fun buildRequestBody(request: McpJsonRpcRequest): okhttp3.RequestBody {
        val body = buildString {
            append("{\"jsonrpc\":\"2.0\",\"id\":")
            append(json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(request.id)))
            append(",\"method\":")
            append(json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(request.method)))
            append(",\"params\":")
            append(json.encodeToString(JsonObject.serializer(), toJsonObject(request.params)))
            append("}")
        }
        return body.toRequestBody("application/json".toMediaType())
    }

    internal fun toJsonObject(map: Map<String, Any>): JsonObject = buildJsonObject {
        map.forEach { (key, value) ->
            when (value) {
                is String -> put(key, value)
                is Number -> put(key, value)
                is Boolean -> put(key, value)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    put(key, toJsonObject(value as Map<String, Any>))
                }
                is List<*> -> put(
                    key,
                    kotlinx.serialization.json.JsonArray(
                        value.map { v ->
                            when (v) {
                                is String -> kotlinx.serialization.json.JsonPrimitive(v)
                                is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                                is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                                else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
                            }
                        }
                    )
                )
                else -> put(key, value.toString())
            }
        }
    }

    /** Read the body with a hard cap so an oversized response cannot exhaust memory (§18). */
    private fun boundedBody(response: Response): String {
        val body = response.body
            ?: throw McpTransportException("MCP endpoint returned an empty body.")
        val source = body.source()
        source.request(MAX_MCP_RESPONSE_CHARS + 1L)
        val buffer = StringBuilder()
        val reader = body.charStream()
        val chars = CharArray(4096)
        while (true) {
            val read = reader.read(chars)
            if (read <= 0) break
            if (buffer.length.toLong() + read > MAX_MCP_RESPONSE_CHARS) {
                throw McpTransportException("MCP response exceeded the allowed size.")
            }
            buffer.append(chars, 0, read)
        }
        return buffer.toString()
    }

    private fun parseResponse(text: String, request: McpJsonRpcRequest): McpJsonRpcResponse {
        val parsed = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw McpTransportException("MCP endpoint returned malformed JSON.", e)
        }
        val errorObj = parsed["error"] as? JsonObject
        val error = errorObj?.let {
            val code = (it["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: -1
            val message = (it["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "Server error"
            McpJsonRpcError(code = code, message = message)
        }
        // §21: a response whose id does not match the request can never complete it.
        val responseId = (parsed["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (responseId != null && responseId != request.id) {
            throw McpTransportException("MCP response id does not match the request.")
        }
        return McpJsonRpcResponse(
            id = responseId ?: request.id,
            result = (parsed["result"] as? JsonObject)?.let { obj ->
                obj.mapValues { (_, v) -> v.toString() }.toMutableMap<String, Any>()
            },
            error = error
        )
    }

    private fun isJsonContentType(response: Response): Boolean {
        val type = response.header("Content-Type") ?: return false
        return type.contains("json", ignoreCase = true)
    }

    // Per-connection endpoint bindings. The manager creates one connection per server, so this
    // map stays server-id → validated endpoint. connect() binds it.
    private val endpoints = java.util.concurrent.ConcurrentHashMap<String, String>()

    internal fun bind(serverId: String, endpoint: String) {
        validateEndpoint(endpoint).getOrThrow()
        endpoints[serverId] = endpoint
    }

    private suspend fun Call.awaitSuspending(): Response = withContext(Dispatchers.IO) {
        val call = this@awaitSuspending
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(response))
                }
            })
        }
    }

    companion object {
        const val CONNECT_SLACK_MS = 5_000L
        const val MAX_CONCURRENT_REQUESTS = 8
        const val MAX_REDIRECTS = 3

        val FORBIDDEN_SCHEMES = setOf(
            "file", "content", "data", "javascript", "intent", "ws", "wss", "ftp", "gopher"
        )
    }
}
