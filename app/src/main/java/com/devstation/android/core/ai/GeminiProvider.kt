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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Gemini provider adapter.
 *
 * Wire format verified against the official Gemini API docs (ai.google.dev):
 * - POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse
 * - POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 * - GET  https://generativelanguage.googleapis.com/v1beta/models?pageSize=200
 * - Auth: `x-goog-api-key: <key>` header
 * - Streaming SSE payloads: `candidates[0].content.parts[].text`, `usageMetadata`,
 *   errors as `{"error":{"code":...,"message":...}}`.
 */
class GeminiProvider(
    private val httpClient: AIHttpClient,
    private val credentials: AiCredentialManager,
    private val credentialId: String? = null,
    /** Defaults to the official Google endpoint. Exposed for tests and future self-hosted gateways. */
    private val baseUrlOverride: String? = null,
    /** Test/dev escape hatch: permits plaintext HTTP on loopback (e.g. MockWebServer). Never for saved configs. */
    private val allowInsecureLocalHost: Boolean = false
) : AIProvider {

    override val providerId = "gemini"
    override val displayName = "Google Gemini"

    override val capabilities = AIProviderCapabilities(
        chat = true,
        streaming = true,
        vision = false, // Phase 5 sends text only; vision capability stays reserved.
        toolCalling = false,
        embeddings = false,
        modelListing = true,
        usageReporting = true,
        systemPrompt = true,
        maxContext = true,
        reasoning = false,
        fileInput = false
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun requireKey(): String =
        credentialId?.let { credentials.resolveKey(it) }
            ?: throw AIError.AuthenticationError("No API key configured for $displayName.")

    private fun generateUrl(modelId: String, stream: Boolean): HttpUrl {
        val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        val encoded = java.net.URLEncoder.encode(modelId, "UTF-8")
        val base = (baseUrlOverride ?: DEFAULT_BASE_URL).trimEnd('/')
        return HttpsUrlValidator.validate("$base/models/$encoded:$action", allowInsecureLocalHost)
    }

    private fun modelsUrl(): HttpUrl =
        HttpsUrlValidator.validate("${(baseUrlOverride ?: DEFAULT_BASE_URL).trimEnd('/')}/models?pageSize=200", allowInsecureLocalHost)

    override suspend fun getModels(): Result<List<AIModel>> = runCatching {
        val key = requireKey()
        val response = try {
            httpClient.execute(url = modelsUrl(), headers = authHeaders(key))
        } catch (e: AIHttpException) {
            throw AIErrorMapper.fromHttpCode(e.code, e.providerMessage)
        }
        val body = response.body ?: throw AIError.ServerError("Empty model list response.")
        val parsed = json.parseToJsonElement(body).jsonObject
        val models = parsed["models"]?.jsonArray ?: JsonArray(emptyList())
        val now = System.currentTimeMillis()
        models.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj.stringField("name")?.removePrefix("models/") ?: return@mapNotNull null
            val methods = (obj["supportedGenerationMethods"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: emptyList()
            if ("generateContent" !in methods) return@mapNotNull null
            AIModel(
                providerId = providerId,
                modelId = name,
                displayName = obj.stringField("displayName") ?: name,
                contextWindow = obj.longField("inputTokenLimit"),
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
            // Gemini reports invalid API keys as 400/403 API_ERROR responses.
            if (e.code == 400 || e.code == 403) throw AIError.AuthenticationError() else throw AIErrorMapper.fromHttpCode(e.code, e.providerMessage)
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
            put("contents", buildContents(request))
            request.systemInstruction?.let { sys ->
                put("systemInstruction", buildJsonObject {
                    put("parts", JsonArray(listOf(buildJsonObject { put("text", sys) })))
                })
            }
            val cfg = buildJsonObject {
                request.temperature?.let { put("temperature", it) }
                request.maxOutputTokens?.let { put("maxOutputTokens", it) }
            }
            if (cfg.isNotEmpty()) put("generationConfig", cfg)
        }
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        if (!request.stream) {
            val response = try {
                httpClient.execute(
                    url = generateUrl(request.modelId, stream = false),
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
                val text = extractTextFromCandidates(obj)
                if (text.isNotEmpty()) send(AIResponseEvent.TextDelta(text))
                obj["usageMetadata"]?.let { u ->
                    extractUsage(u.jsonObject)?.let { send(AIResponseEvent.Usage(it)) }
                }
                send(AIResponseEvent.Completed(messageId = "gm_${System.currentTimeMillis()}"))
            } catch (_: Throwable) {
                send(AIResponseEvent.Error(AIError.InvalidRequestError("Provider returned an unreadable response.")))
            }
            return@channelFlow
        }

        val parser = SseLineParser()
        var started = false
        var streamError: AIError? = null
        try {
            httpClient.executeStreaming(
                url = generateUrl(request.modelId, stream = true),
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
                        val code = errObj?.longField("code")?.toInt()
                        val message = errObj?.stringField("message")
                        streamError = if (code != null) {
                            AIErrorMapper.fromHttpCode(code, message, request.modelId)
                        } else {
                            AIError.ServerError(message ?: "Provider reported a stream error.")
                        }
                        return@executeStreaming
                    }
                    if (!started) {
                        started = true
                        trySend(AIResponseEvent.Started(providerId, request.modelId))
                    }
                    val text = extractTextFromCandidates(obj)
                    if (text.isNotEmpty()) trySend(AIResponseEvent.TextDelta(text))
                    obj["usageMetadata"]?.let { u ->
                        (u as? JsonObject)?.let { um ->
                            extractUsage(um)?.let { trySend(AIResponseEvent.Usage(it)) }
                        }
                    }
                }
            )
            if (streamError != null) {
                send(AIResponseEvent.Error(streamError!!))
            } else if (started) {
                send(AIResponseEvent.Completed(messageId = "gm_${System.currentTimeMillis()}"))
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
        "x-goog-api-key" to key,
        "Content-Type" to "application/json"
    )

    private fun buildContents(request: AIRequest): JsonArray {
        val arr = mutableListOf<JsonElement>()
        request.messages.forEach { msg ->
            val role = when (msg.role) {
                AIMessageRole.USER, AIMessageRole.SYSTEM -> "user"
                AIMessageRole.ASSISTANT -> "model"
            }
            arr.add(buildJsonObject {
                put("role", role)
                put("parts", JsonArray(listOf(buildJsonObject { put("text", msg.content) })))
            })
        }
        return JsonArray(arr)
    }

    private fun extractTextFromCandidates(obj: JsonObject): String {
        val candidates = obj["candidates"] as? JsonArray ?: return ""
        val first = candidates.firstOrNull() as? JsonObject ?: return ""
        val content = first["content"] as? JsonObject ?: return ""
        val parts = content["parts"] as? JsonArray ?: return ""
        return parts.mapNotNull { p ->
            ((p as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
        }.joinToString("")
    }

    private fun extractUsage(usage: JsonObject): AIUsage? {
        val input = usage.longField("promptTokenCount")
        val output = usage.longField("candidatesTokenCount")
        if (input == null && output == null) return null
        return AIUsage(
            inputTokens = input,
            outputTokens = output,
            totalTokens = usage.longField("totalTokenCount") ?: ((input ?: 0L) + (output ?: 0L)),
            cachedTokens = usage.longField("cachedContentTokenCount"),
            reasoningTokens = usage.longField("thoughtsTokenCount")
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
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }
}
