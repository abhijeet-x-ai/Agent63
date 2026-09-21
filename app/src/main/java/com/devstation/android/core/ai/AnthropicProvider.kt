package com.devstation.android.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anthropic (Claude) provider adapter.
 *
 * Wire format verified against the official Claude API docs (docs.claude.com / platform.claude.com):
 * - POST https://api.anthropic.com/v1/messages  with `"stream": true`
 * - GET  https://api.anthropic.com/v1/models
 * - Headers: `x-api-key: <key>` (or Authorization Bearer), `anthropic-version: 2023-06-01`
 * - Streaming SSE events:
 *     message_start (message.usage.input_tokens)
 *     content_block_delta (delta.type: text_delta | thinking_delta)
 *     message_delta (usage.output_tokens cumulative)
 *     message_stop
 *     event: error (overloaded_error etc.)
 * - Non-streaming response: { content: [{type:"text", text:...}], usage: {input_tokens, output_tokens} }
 */
class AnthropicProvider(
    private val httpClient: AIHttpClient,
    private val credentials: AiCredentialManager,
    private val credentialId: String? = null,
    private val baseUrlOverride: String? = null,
    /** Test/dev escape hatch: permits plaintext HTTP on loopback (e.g. MockWebServer). Never for saved configs. */
    private val allowInsecureLocalHost: Boolean = false
) : AIProvider {

    override val providerId = "anthropic"
    override val displayName = "Anthropic Claude"

    override val capabilities = AIProviderCapabilities(
        chat = true,
        streaming = true,
        vision = false,
        toolCalling = false,
        embeddings = false,
        modelListing = true,
        usageReporting = true,
        systemPrompt = true,
        maxContext = true,
        reasoning = true, // thinking_delta streams are surfaced as ThinkingDelta
        fileInput = false
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun requireKey(): String =
        credentialId?.let { credentials.resolveKey(it) }
            ?: throw AIError.AuthenticationError("No API key configured for $displayName.")

    private fun messagesUrl(): HttpUrl =
        HttpsUrlValidator.validate(
            "${(baseUrlOverride ?: DEFAULT_BASE_URL).trimEnd('/')}/v1/messages",
            allowInsecureLocalHost
        )

    private fun modelsUrl(): HttpUrl =
        HttpsUrlValidator.validate(
            "${(baseUrlOverride ?: DEFAULT_BASE_URL).trimEnd('/')}/v1/models",
            allowInsecureLocalHost
        )

    override suspend fun getModels(): Result<List<AIModel>> = runCatching {
        val key = requireKey()
        val response = try {
            httpClient.execute(url = modelsUrl(), headers = authHeaders(key))
        } catch (e: AIHttpException) {
            throw AIErrorMapper.fromHttpCode(e.code, e.providerMessage)
        }
        val body = response.body ?: throw AIError.ServerError("Empty model list response.")
        val parsed = json.parseToJsonElement(body).jsonObject
        val data = parsed["data"]?.jsonArray ?: JsonArray(emptyList())
        val now = System.currentTimeMillis()
        data.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.stringField("id") ?: return@mapNotNull null
            AIModel(
                providerId = providerId,
                modelId = id,
                displayName = obj.stringField("display_name") ?: id,
                contextWindow = obj.longField("context_window"),
                capabilities = capabilities,
                inputPricing = null,
                outputPricing = null,
                enabled = true,
                lastUpdated = now
            )
        }
    }

    override suspend fun testConnection(): Result<ProviderHealth> = runCatching {
        val started = System.currentTimeMillis()
        val key = requireKey()
        try {
            httpClient.execute(url = modelsUrl(), headers = authHeaders(key))
        } catch (e: AIHttpException) {
            throw AIErrorMapper.fromHttpCode(e.code, e.providerMessage)
        }
        val latency = System.currentTimeMillis() - started
        ProviderHealth(
            providerId = providerId,
            reachable = true,
            authenticated = true,
            message = "Connected",
            latencyMs = latency
        )
    }

    override fun generate(request: AIRequest): Flow<AIResponseEvent> = channelFlow {
        val key = try {
            requireKey()
        } catch (e: AIError) {
            send(AIResponseEvent.Error(e))
            return@channelFlow
        }
        val payload = buildJsonObject {
            put("model", request.modelId)
            put("messages", buildAnthropicMessages(request))
            // Anthropic requires an explicit max_tokens; use requested or a safe default.
            put("max_tokens", request.maxOutputTokens ?: DEFAULT_MAX_TOKENS)
            request.temperature?.let { put("temperature", it) }
            request.systemInstruction?.let { put("system", it) }
            if (request.stream) put("stream", true)
        }
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        if (!request.stream) {
            val response = try {
                httpClient.execute(
                    url = messagesUrl(),
                    method = "POST",
                    headers = authHeaders(key),
                    body = body
                )
            } catch (e: AIHttpException) {
                send(AIResponseEvent.Error(AIErrorMapper.fromHttpCode(e.code, e.providerMessage, request.modelId)))
                return@channelFlow
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                send(AIResponseEvent.Error(AIErrorMapper.fromThrowable(t)))
                return@channelFlow
            }
            send(AIResponseEvent.Started(providerId, request.modelId))
            try {
                val obj = json.parseToJsonElement(response.body ?: "{}").jsonObject
                val text = (obj["content"]?.jsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { block ->
                        val blockObj = block as? JsonObject
                        if (blockObj?.stringField("type") == "text") blockObj.stringField("text") else null
                    }.joinToString("")
                if (text.isNotEmpty()) send(AIResponseEvent.TextDelta(text))
                obj["usage"]?.let { u ->
                    (u as? JsonObject)?.let { um ->
                        extractUsage(um, 0)?.let { send(AIResponseEvent.Usage(it)) }
                    }
                }
                send(AIResponseEvent.Completed(messageId = obj.stringField("id") ?: "an_${System.currentTimeMillis()}"))
            } catch (_: Throwable) {
                send(AIResponseEvent.Error(AIError.InvalidRequestError("Provider returned an unreadable response.")))
            }
            return@channelFlow
        }

        val parser = SseLineParser()
        var started = false
        var streamError: AIError? = null
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        try {
            httpClient.executeStreaming(
                url = messagesUrl(),
                headers = authHeaders(key),
                body = body,
                onLine = { line ->
                    val event = parser.feed(line) ?: return@executeStreaming
                    if (event.isDoneSentinel) return@executeStreaming
                    val data = event.data ?: return@executeStreaming
                    val obj = try {
                        json.parseToJsonElement(data).jsonObject
                    } catch (_: Exception) {
                        return@executeStreaming
                    }
                    val type = obj.stringField("type") ?: event.eventName
                    when (type) {
                        "error" -> {
                            val err = obj["error"] as? JsonObject
                            val message = err?.stringField("message")
                            streamError = AIError.ServerError(message ?: "Provider reported a stream error.")
                        }
                        "message_start" -> {
                            started = true
                            trySend(AIResponseEvent.Started(providerId, request.modelId))
                            val message = obj["message"] as? JsonObject
                            val usage = message?.get("usage") as? JsonObject
                            inputTokens = usage?.longField("input_tokens") ?: inputTokens
                        }
                        "content_block_delta" -> {
                            val delta = obj["delta"] as? JsonObject
                            when (delta?.stringField("type")) {
                                "text_delta" -> delta.stringField("text")?.let {
                                    if (it.isNotEmpty()) trySend(AIResponseEvent.TextDelta(it))
                                }
                                "thinking_delta" -> delta.stringField("thinking")?.let {
                                    if (it.isNotEmpty()) trySend(AIResponseEvent.ThinkingDelta(it))
                                }
                            }
                        }
                        "message_delta" -> {
                            val usage = obj["usage"] as? JsonObject
                            outputTokens = usage?.longField("output_tokens") ?: outputTokens
                        }
                        // message_stop, content_block_start/stop, ping: nothing to normalize
                    }
                }
            )
            if (streamError != null) {
                send(AIResponseEvent.Error(streamError!!))
            } else if (started) {
                inputTokens?.let { input ->
                    extractUsage(inputTokens = input, outputTokens = outputTokens)?.let {
                        send(AIResponseEvent.Usage(it))
                    }
                }
                send(AIResponseEvent.Completed(messageId = "an_${System.currentTimeMillis()}"))
            } else {
                send(AIResponseEvent.Error(AIError.ServerError("Provider closed the stream without any content.")))
            }
        } catch (e: AIHttpException) {
            send(AIResponseEvent.Error(AIErrorMapper.fromHttpCode(e.code, e.providerMessage, request.modelId)))
        } catch (ce: CancellationException) {
            trySend(AIResponseEvent.Cancelled)
            throw ce
        } catch (t: Throwable) {
            send(AIResponseEvent.Error(AIErrorMapper.fromThrowable(t)))
        }
    }.flowOn(Dispatchers.IO)

    private fun authHeaders(key: String) = mapOf(
        "x-api-key" to key,
        "anthropic-version" to ANTHROPIC_VERSION,
        "Content-Type" to "application/json"
    )

    private fun buildAnthropicMessages(request: AIRequest): JsonArray {
        val arr = mutableListOf<JsonElement>()
        request.messages.forEach { msg ->
            // Anthropic takes the system prompt top-level; skip SYSTEM role here.
            if (msg.role == AIMessageRole.SYSTEM) return@forEach
            arr.add(buildJsonObject {
                put("role", if (msg.role == AIMessageRole.USER) "user" else "assistant")
                put("content", msg.content)
            })
        }
        return JsonArray(arr)
    }

    private fun extractUsage(usage: JsonObject, unused: Int): AIUsage? =
        extractUsage(inputTokens = usage.longField("input_tokens"), outputTokens = usage.longField("output_tokens"))

    private fun extractUsage(inputTokens: Long?, outputTokens: Long?): AIUsage? {
        if (inputTokens == null && outputTokens == null) return null
        return AIUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = (inputTokens ?: 0L) + (outputTokens ?: 0L)
        )
    }

    private fun extractErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            val error = obj["error"] as? JsonObject
            error?.stringField("message") ?: obj.stringField("message")
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.longField(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 4096
    }
}
