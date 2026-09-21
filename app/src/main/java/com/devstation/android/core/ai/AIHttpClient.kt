package com.devstation.android.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Thrown by [AIHttpClient] on non-2xx responses. Carries the sanitized provider
 * message so adapters can normalize the error without touching raw payloads.
 */
class AIHttpException(
    val code: Int,
    val providerMessage: String?
) : IOException("HTTP $code")

/**
 * HTTPS-only URL validation for all provider endpoints.
 * Plaintext HTTP is rejected for real AI credentials. Localhost is permitted ONLY
 * behind an explicit flag (default OFF) to support future local-model providers;
 * it is never accepted for saved production configurations.
 */
object HttpsUrlValidator {

    @Throws(IllegalArgumentException::class, SecurityException::class)
    fun validate(rawUrl: String, allowInsecureLocalHost: Boolean = false): HttpUrl {
        val url = rawUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid URL: $rawUrl")
        if (url.isHttps) return url
        val loopbackHost = url.host == "localhost" || url.host == "127.0.0.1" || url.host == "::1"
        if (loopbackHost && allowInsecureLocalHost) return url
        throw SecurityException("Plaintext HTTP is not allowed for AI provider endpoints: $rawUrl")
    }
}

/**
 * Minimal, testable SSE parser. Feed one line at a time; returns a complete event
 * or null until an event boundary (blank line) closes the current event.
 * Handles `data:`, `event:`, multi-line data, and the `[DONE]` sentinel.
 */
class SseLineParser {

    data class SseEvent(
        val eventName: String?,
        val data: String?,
        val isDoneSentinel: Boolean = false
    )

    private val dataParts = StringBuilder()
    private var eventName: String? = null
    private var sawField = false

    /** Feed a single (already newline-stripped) line. Returns an event when the blank-line boundary closes it. */
    fun feed(line: String): SseEvent? {
        val trimmed = line.trimEnd('\r')
        return when {
            trimmed.isEmpty() -> closeCurrentEvent()
            trimmed.startsWith("data:") -> {
                sawField = true
                val payload = trimmed.removePrefix("data:").let { if (it.startsWith(" ")) it.drop(1) else it }
                if (dataParts.isNotEmpty()) dataParts.append('\n')
                dataParts.append(payload)
                null
            }
            trimmed.startsWith("event:") -> {
                sawField = true
                eventName = trimmed.removePrefix("event:").let { if (it.startsWith(" ")) it.drop(1) else it }
                null
            }
            trimmed.startsWith(":") -> null // comment / keepalive
            else -> null // ignore unknown fields (id:, retry:, etc.)
        }
    }

    private fun closeCurrentEvent(): SseEvent? {
        if (!sawField) return null
        val data = dataParts.toString()
        val name = eventName
        val done = data.trim() == "[DONE]"
        val event = SseEvent(eventName = name, data = data.takeIf { it.isNotEmpty() }, isDoneSentinel = done)
        dataParts.setLength(0)
        eventName = null
        sawField = false
        return event
    }

    fun reset() {
        dataParts.setLength(0)
        eventName = null
        sawField = false
    }
}

/**
 * Security: never log secrets. All provider HTTP logging funnels through this
 * redactor; Authorization / api-key headers become [REDACTED].
 */
object AIHttpLogRedactor {
    private val SENSITIVE_HEADERS = listOf(
        "authorization", "x-api-key", "x-goog-api-key", "api-key", "cookie", "set-cookie", "proxy-authorization"
    )

    fun isSensitive(name: String): Boolean = SENSITIVE_HEADERS.any { it.equals(name, ignoreCase = true) }

    fun redact(): String = "[REDACTED]"
}

/**
 * Thin OkHttp wrapper for provider traffic.
 * - bounded timeouts (never an infinite request)
 * - platform default TLS (no custom trust bypass)
 * - streaming body reading for SSE
 * - redacted debug events only (no secrets, no bodies)
 */
class AIHttpClient(
    connectTimeoutSeconds: Long = DEFAULT_CONNECT_TIMEOUT_S,
    readTimeoutSeconds: Long = DEFAULT_READ_TIMEOUT_S,
    writeTimeoutSeconds: Long = DEFAULT_WRITE_TIMEOUT_S,
    callTimeoutSeconds: Long = DEFAULT_CALL_TIMEOUT_S
) {

    data class AIHttpResponse(
        val code: Int,
        val message: String?,
        val headers: Map<String, String>,
        val body: String?
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    val connectTimeoutSeconds: Long = connectTimeoutSeconds
    val readTimeoutSeconds: Long = readTimeoutSeconds
    val callTimeoutSeconds: Long = callTimeoutSeconds

    suspend fun execute(
        url: HttpUrl,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        onRedactedEvent: ((String) -> Unit)? = null
    ): AIHttpResponse = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) ->
            builder.header(name, value)
            if (AIHttpLogRedactor.isSensitive(name)) {
                onRedactedEvent?.invoke("$method ${url.host} $name: ${AIHttpLogRedactor.redact()}")
            }
        }
        when (method.uppercase()) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method.uppercase(), body ?: throw IllegalArgumentException("Body required"))
        }
        coroutineContext.ensureActive()
        val response = client.newCall(builder.build()).execute()
        response.use { resp ->
            val text = try {
                resp.body?.string()
            } catch (_: IOException) {
                null
            }
            if (!resp.isSuccessful) {
                // Normalize immediately so callers always deal with AIHttpException or success.
                throw AIHttpException(code = resp.code, providerMessage = sanitizeProviderMessage(text))
            }
            AIHttpResponse(
                code = resp.code,
                message = resp.message,
                headers = resp.headers.toMultimap().mapValues { it.value.firstOrNull() ?: "" },
                body = text
            )
        }
    }

    /**
     * Streaming: invokes [onLine] for each line of the response body while the
     * request is active. Cancellation of the calling coroutine closes the socket.
     * Non-2xx responses raise [AIHttpException] with a sanitized provider message.
     */
    suspend fun executeStreaming(
        url: HttpUrl,
        method: String = "POST",
        headers: Map<String, String> = emptyMap(),
        body: RequestBody,
        onRedactedEvent: ((String) -> Unit)? = null,
        onLine: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) ->
            builder.header(name, value)
            if (AIHttpLogRedactor.isSensitive(name)) {
                onRedactedEvent?.invoke("$method ${url.host} $name: ${AIHttpLogRedactor.redact()}")
            }
        }
        builder.post(body)
        coroutineContext.ensureActive()
        val call = client.newCall(builder.build())
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = try {
                        resp.body?.string()
                    } catch (_: IOException) {
                        null
                    }
                    throw AIHttpException(code = resp.code, providerMessage = sanitizeProviderMessage(errBody))
                }
                val source = resp.body?.source() ?: throw IOException("Empty streaming body")
                while (true) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    onLine(line)
                }
            }
        } finally {
            call.cancel()
        }
    }

    private fun sanitizeProviderMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            // Best-effort extraction of a provider "message" field; never echoes raw body.
            val snippet = body.take(2000)
            val key = "\"message\""
            val idx = snippet.indexOf(key)
            if (idx >= 0) {
                val start = snippet.indexOf('"', idx + key.length) + 1
                val end = snippet.indexOf('"', start)
                if (start > 0 && end > start) snippet.substring(start, end) else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_S = 15L
        const val DEFAULT_READ_TIMEOUT_S = 120L
        const val DEFAULT_WRITE_TIMEOUT_S = 30L
        const val DEFAULT_CALL_TIMEOUT_S = 180L
    }
}
