package com.devstation.android.core.ai

import kotlinx.coroutines.flow.Flow

/**
 * Normalized response events. UI receives only these — never raw SSE/HTTP details.
 *
 * Event ordering contract:
 *   Started? -> (TextDelta | ThinkingDelta)* -> Usage? -> Completed
 * On failure or cancellation the flow terminates with Error or Cancelled respectively.
 */
sealed class AIResponseEvent {
    data class Started(
        val providerId: String,
        val modelId: String
    ) : AIResponseEvent()

    /** A growing piece of the assistant text. Never duplicated — append only. */
    data class TextDelta(val text: String) : AIResponseEvent()

    /** Reasoning/thinking content where the provider supports it. */
    data class ThinkingDelta(val text: String) : AIResponseEvent()

    /** Token usage if and only if the provider reported it. */
    data class Usage(val usage: AIUsage) : AIResponseEvent()

    /**
     * Phase 6: the model requested one or more tool invocations. Emitted after any text
     * deltas and before [Completed]. Arguments are untrusted and must be validated.
     */
    data class ToolCallRequested(val calls: List<AIToolCall>) : AIResponseEvent()

    data class Completed(
        val messageId: String,
        val stopReason: String? = null,
        val finishMessage: String? = null
    ) : AIResponseEvent()

    data class Error(val error: AIError) : AIResponseEvent()

    /** Emitted when the caller cancels the request (structured coroutine cancellation). */
    object Cancelled : AIResponseEvent()
}

/**
 * Normalized errors. UI never needs to understand provider-specific HTTP payloads.
 * Extends Exception so adapters can throw these through runCatching/retry layers.
 */
sealed class AIError(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    class NetworkError(message: String = "Network error. Check your connection.", cause: Throwable? = null) : AIError(message, cause)
    class AuthenticationError(message: String = "Authentication failed. Check your API key.", cause: Throwable? = null) : AIError(message, cause)
    class AuthorizationError(message: String = "Permission denied for this operation.", cause: Throwable? = null) : AIError(message, cause)
    class RateLimitError(
        val retryAfterSeconds: Long? = null,
        message: String = "Rate limit reached.",
        cause: Throwable? = null
    ) : AIError(message, cause)
    class InvalidRequestError(message: String = "The request was rejected as invalid.", cause: Throwable? = null) : AIError(message, cause)
    class ModelNotFoundError(val modelId: String, message: String = "Selected model is unavailable.") : AIError(message)
    class ServerError(message: String = "Provider server error. Try again later.", cause: Throwable? = null) : AIError(message, cause)
    class TimeoutError(message: String = "Request timed out.", cause: Throwable? = null) : AIError(message, cause)
    class CancelledError(message: String = "Request cancelled.", cause: Throwable? = null) : AIError(message, cause)
    class ProviderUnavailableError(message: String = "Provider is unavailable.", cause: Throwable? = null) : AIError(message, cause)
    class UnsupportedFeatureError(message: String = "This feature is not supported by the provider.", cause: Throwable? = null) : AIError(message, cause)
    class UnknownError(message: String = "Unexpected error.", cause: Throwable? = null) : AIError(message, cause)
}

/**
 * The single interface every provider adapter implements.
 * UI and future agent layers communicate ONLY through this abstraction
 * (see implementation_plan_phase5.md §3).
 */
interface AIProvider {
    val providerId: String
    val displayName: String
    val capabilities: AIProviderCapabilities

    suspend fun getModels(): Result<List<AIModel>>

    suspend fun testConnection(): Result<ProviderHealth>

    fun generate(request: AIRequest): Flow<AIResponseEvent>
}

/**
 * Registry/facade over all registered providers. Resolves provider + model ids,
 * applies enable/disable rules, and is the injection point for the future Phase 6
 * agent layer (implementation_plan_phase5.md §14).
 */
interface AIProviderManager {
    val providers: List<AIProvider>

    fun getProvider(providerId: String): AIProvider?

    fun registerProvider(provider: AIProvider)

    suspend fun resolveModel(providerId: String, modelId: String?): Result<AIModel>
}
