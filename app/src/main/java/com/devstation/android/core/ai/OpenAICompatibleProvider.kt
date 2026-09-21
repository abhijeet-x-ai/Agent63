package com.devstation.android.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI-compatible chat provider adapter.
 *
 * Wire format verified against the official OpenAI API documentation:
 * - POST {base}/chat/completions  (stream: SSE `chat.completion.chunk` objects)
 * - GET  {base}/models            (model listing)
 * - Auth: `Authorization: Bearer <key>`
 *
 * Works with any OpenAI-compatible endpoint (custom base URL), which is why the
 * display name says "OpenAI-compatible" rather than implying the official service.
 */
class OpenAICompatibleProvider(
    override val providerId: String,
    override val displayName: String,
    private val defaultBaseUrl: String,
    private val httpClient: AIHttpClient,
    private val credentials: AiCredentialManager,
    private val baseUrlOverride: String? = null,
    private val credentialId: String? = null,
    /** Explicit user-selected model list for endpoints without model listing. */
    private val configuredModels: List<String> = emptyList(),
    /** Test/dev escape hatch: permits plaintext HTTP on loopback (e.g. MockWebServer, local models). */
    private val allowInsecureLocalHost: Boolean = false
) : AIProvider {

    override val capabilities = AIProviderCapabilities(
        chat = true,
        streaming = true,
        vision = false,
        toolCalling = true,
        embeddings = false,
        modelListing = true,
        usageReporting = true,
        systemPrompt = true,
        maxContext = true,
        reasoning = false,
        fileInput = false
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun baseUrl(): String = (baseUrlOverride ?: defaultBaseUrl).trimEnd('/')

    private fun requireKey(): String =
        credentialId?.let { credentials.resolveKey(it) }
            ?: throw AIError.AuthenticationError("No API key configured for $displayName.")

    private fun chatCompletionsUrl(): HttpUrl =
        HttpsUrlValidator.validate("${baseUrl()}/chat/completions", allowInsecureLocalHost)

    private fun modelsUrl(): HttpUrl =
        HttpsUrlValidator.validate("${baseUrl()}/models", allowInsecureLocalHost)

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
                displayName = obj.stringField("id") ?: id,
                contextWindow = null, // OpenAI list does not expose context; do not invent.
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
        val response = try {
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
            put("messages", buildOpenAIMessages(request))
            request.temperature?.let { put("temperature", it) }
            request.maxOutputTokens?.let { put("max_tokens", it) }
            if (request.tools.isNotEmpty()) {
                put("tools", buildOpenAITools(request.tools))
                put("tool_choice", request.toolChoice ?: "auto")
            }
            if (request.stream) {
                put("stream", true)
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }
        }
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        if (!request.stream) {
            val response = try {
                httpClient.execute(url = chatCompletionsUrl(), method = "POST", headers = authHeaders(key), body = body)
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
                val content = extractNonStreamingContent(obj)
                if (content.isNotEmpty()) send(AIResponseEvent.TextDelta(content))
                extractNonStreamingToolCalls(obj).takeIf { it.isNotEmpty() }?.let {
                    send(AIResponseEvent.ToolCallRequested(it))
                }
                extractUsage(obj)?.let { send(AIResponseEvent.Usage(it)) }
                send(AIResponseEvent.Completed(messageId = "oa_${System.currentTimeMillis()}"))
            } catch (t: Throwable) {
                send(AIResponseEvent.Error(AIError.InvalidRequestError("Provider returned an unreadable response.")))
            }
            return@channelFlow
        }

        val parser = SseLineParser()
        var started = false
        var sawUsage = false
        var sawError: AIError? = null
        val usage = StringBuilder()
        // Phase 6: accumulate streamed tool_calls fragments keyed by their index.
        val toolCalls = LinkedHashMap<Int, ToolCallBuilder>()
        try {
            httpClient.executeStreaming(
                url = chatCompletionsUrl(),
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
                    obj["error"]?.let { errEl ->
                        val errObj = errEl as? JsonObject
                        sawError = AIError.ServerError(
                            errObj?.stringField("message") ?: "Provider reported a stream error."
                        )
                        return@executeStreaming
                    }
                    if (!started) {
                        started = true
                        trySend(AIResponseEvent.Started(providerId, request.modelId))
                    }
                    obj["usage"]?.takeIf { it is JsonObject }?.let {
                        usage.setLength(0)
                        usage.append(it.toString())
                    }
                    val choices = obj["choices"] as? JsonArray ?: return@executeStreaming
                    val first = choices.firstOrNull() as? JsonObject ?: return@executeStreaming
                    val delta = first["delta"] as? JsonObject ?: return@executeStreaming
                    delta["content"]?.let { c ->
                        val text = (c as? JsonPrimitive)?.contentOrNull
                        if (!text.isNullOrEmpty()) trySend(AIResponseEvent.TextDelta(text))
                    }
                    // Reasoning-capable compatible endpoints may stream reasoning_content.
                    delta["reasoning_content"]?.let { r ->
                        val text = (r as? JsonPrimitive)?.contentOrNull
                        if (!text.isNullOrEmpty()) trySend(AIResponseEvent.ThinkingDelta(text))
                    }
                    (delta["tool_calls"] as? JsonArray)?.forEach { el ->
                        val callObj = el as? JsonObject ?: return@forEach
                        val index = (callObj["index"] as? JsonPrimitive)?.intOrNull ?: toolCalls.size
                        val builder = toolCalls.getOrPut(index) { ToolCallBuilder() }
                        callObj.stringField("id")?.let { builder.id = it }
                        (callObj["function"] as? JsonObject)?.let { fn ->
                            fn.stringField("name")?.let { builder.name = it }
                            fn.stringField("arguments")?.let { builder.arguments.append(it) }
                        }
                    }
                    val finishRaw = (first["finish_reason"] as? JsonPrimitive)?.contentOrNull
                    if (finishRaw != null && finishRaw != "null") {
                        finishReason = finishRaw
                    }
                }
            )
            if (sawError != null) {
                send(AIResponseEvent.Error(sawError!!))
                return@channelFlow
            }
            if (usage.isNotEmpty()) {
                // `usage` already holds the usage JSON object itself (not a full response).
                parseUsageObject(json.parseToJsonElement(usage.toString()).jsonObject)?.let {
                    sawUsage = true
                    send(AIResponseEvent.Usage(it))
                }
            }
            if (toolCalls.isNotEmpty()) {
                send(
                    AIResponseEvent.ToolCallRequested(
                        toolCalls.entries.sortedBy { it.key }.mapIndexed { i, (_, b) -> b.toCall(i) }
                    )
                )
            }
            if (started) {
                send(AIResponseEvent.Completed(messageId = "oa_${System.currentTimeMillis()}", stopReason = finishReason))
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

    private var finishReason: String? = null

    /** Mutable accumulator for a streamed tool call (arguments arrive in fragments). */
    private class ToolCallBuilder {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()

        fun toCall(index: Int): AIToolCall = AIToolCall(
            id = id ?: "call_$index",
            name = name ?: "unknown",
            argumentsJson = arguments.toString().ifBlank { "{}" }
        )
    }

    private fun authHeaders(key: String) = mapOf(
        "Authorization" to "Bearer $key",
        "Content-Type" to "application/json"
    )

    private fun buildOpenAIMessages(request: AIRequest): JsonArray {
        val arr = mutableListOf<JsonElement>()
        request.systemInstruction?.let {
            arr.add(buildJsonObject {
                put("role", "system")
                put("content", it)
            })
        }
        request.messages.forEach { msg ->
            when (msg.role) {
                AIMessageRole.SYSTEM -> arr.add(buildJsonObject {
                    put("role", "system")
                    put("content", msg.content)
                })
                AIMessageRole.USER -> arr.add(buildJsonObject {
                    put("role", "user")
                    put("content", msg.content)
                })
                AIMessageRole.ASSISTANT -> arr.add(buildJsonObject {
                    put("role", "assistant")
                    put("content", msg.content)
                    if (msg.toolCalls.isNotEmpty()) {
                        put("tool_calls", JsonArray(msg.toolCalls.map { call ->
                            buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", call.name)
                                    put("arguments", call.argumentsJson)
                                })
                            }
                        }))
                    }
                })
                AIMessageRole.TOOL -> arr.add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", msg.toolCallId ?: "")
                    put("content", msg.content)
                })
            }
        }
        return JsonArray(arr)
    }

    /** OpenAI function-tool schema: {type:"function", function:{name, description, parameters}}. */
    private fun buildOpenAITools(tools: List<AIToolSpec>): JsonArray = JsonArray(tools.map { spec ->
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", spec.name)
                put("description", spec.description)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        spec.parameters.forEach { p ->
                            put(p.name, buildJsonObject {
                                put("type", p.type.wireType)
                                put("description", p.description)
                                if (p.type == AIToolParameterType.ARRAY) {
                                    put("items", buildJsonObject {
                                        put("type", (p.itemType ?: AIToolParameterType.STRING).wireType)
                                    })
                                }
                            })
                        }
                    })
                    put("required", JsonArray(spec.parameters.filter { it.required }.map { JsonPrimitive(it.name) }))
                })
            })
        }
    })

    private fun extractNonStreamingContent(obj: JsonObject): String {
        val choices = obj["choices"] as? JsonArray ?: return ""
        val first = choices.firstOrNull() as? JsonObject ?: return ""
        val message = first["message"] as? JsonObject ?: return ""
        return (message["content"] as? JsonPrimitive)?.contentOrNull ?: ""
    }

    /** Non-streaming `choices[0].message.tool_calls`. */
    private fun extractNonStreamingToolCalls(obj: JsonObject): List<AIToolCall> {
        val choices = obj["choices"] as? JsonArray ?: return emptyList()
        val first = choices.firstOrNull() as? JsonObject ?: return emptyList()
        val message = first["message"] as? JsonObject ?: return emptyList()
        val calls = message["tool_calls"] as? JsonArray ?: return emptyList()
        return calls.mapIndexedNotNull { index, el ->
            val callObj = el as? JsonObject ?: return@mapIndexedNotNull null
            val fn = callObj["function"] as? JsonObject ?: return@mapIndexedNotNull null
            val name = fn.stringField("name") ?: return@mapIndexedNotNull null
            AIToolCall(
                id = callObj.stringField("id") ?: "call_$index",
                name = name,
                argumentsJson = fn.stringField("arguments") ?: "{}"
            )
        }
    }

    private fun extractUsage(obj: JsonObject): AIUsage? {
        val usage = obj["usage"] as? JsonObject ?: return null
        return parseUsageObject(usage)
    }

    /** Parses a bare usage object ({prompt_tokens, completion_tokens, ...}). */
    private fun parseUsageObject(usage: JsonObject): AIUsage? {
        fun longOf(name: String): Long? =
            (usage[name] as? JsonPrimitive)?.longOrNull
        val input = longOf("prompt_tokens")
        val output = longOf("completion_tokens")
        if (input == null && output == null) return null
        return AIUsage(
            inputTokens = input,
            outputTokens = output,
            totalTokens = longOf("total_tokens") ?: (input ?: 0L) + (output ?: 0L),
            cachedTokens = usage["prompt_tokens_details"]?.let { d ->
                (d as? JsonObject)?.get("cached_tokens")?.let { p -> (p as? JsonPrimitive)?.longOrNull }
            },
            reasoningTokens = usage["completion_tokens_details"]?.let { d ->
                (d as? JsonObject)?.get("reasoning_tokens")?.let { p -> (p as? JsonPrimitive)?.longOrNull }
            }
        )
    }

    private fun extractErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            val error = obj["error"]
            (error as? JsonObject)?.stringField("message")
                ?: (error as? JsonPrimitive)?.contentOrNull
                ?: obj.stringField("message")
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}
