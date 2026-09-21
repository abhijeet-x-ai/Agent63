package com.devstation.android.core.ai

/**
 * Phase 5: AI Provider System — provider-independent domain models.
 *
 * Nothing in this file may reference a provider-specific wire format.
 * UI and future agent layers consume ONLY these types.
 */

/** Capability flags advertised by a provider. Phase 6 enables toolCalling on adapters that support it. */
data class AIProviderCapabilities(
    val chat: Boolean = false,
    val streaming: Boolean = false,
    val vision: Boolean = false,
    val toolCalling: Boolean = false,
    val embeddings: Boolean = false,
    val modelListing: Boolean = false,
    val usageReporting: Boolean = false,
    val systemPrompt: Boolean = false,
    val maxContext: Boolean = false,
    val reasoning: Boolean = false,
    val fileInput: Boolean = false
)

/**
 * Normalized message roles. TOOL carries a tool result back to the model (Phase 6);
 * [AIMessage.toolCallId] / [AIMessage.toolName] identify which call it answers.
 */
enum class AIMessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

/**
 * Normalized chat message. [metadata] is reserved for future multimodal payloads
 * (e.g. image references) without changing the schema now.
 *
 * Phase 6 additions (all optional, defaults preserve Phase 5 behavior):
 * - [toolCalls] is set on ASSISTANT messages that requested tools.
 * - [toolCallId] / [toolName] are set on TOOL messages returning a tool result.
 */
data class AIMessage(
    val role: AIMessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val id: String = "",
    val metadata: Map<String, String> = emptyMap(),
    val toolCalls: List<AIToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null
)

/** A model exposed by a provider. Pricing is null unless verified — never invented. */
data class AIModel(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val contextWindow: Long? = null,
    val capabilities: AIProviderCapabilities = AIProviderCapabilities(),
    /** USD per 1M input tokens, verified only. */
    val inputPricing: Double? = null,
    /** USD per 1M output tokens, verified only. */
    val outputPricing: Double? = null,
    val enabled: Boolean = true,
    val lastUpdated: Long = 0L
)

/** Provider-independent request. The only content sent to a provider is inside [messages]/[systemInstruction]. */
data class AIRequest(
    val conversationId: String? = null,
    val modelId: String,
    val messages: List<AIMessage>,
    val systemInstruction: String? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val stream: Boolean = true,
    /** Reserved for future multimodal attachments; Phase 5 sends none. */
    val attachments: List<AIAttachment> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    /** Phase 6: tool definitions advertised to models with native tool calling. */
    val tools: List<AIToolSpec> = emptyList(),
    /** Phase 6: "auto" (default) or "none". Only meaningful when [tools] is non-empty. */
    val toolChoice: String? = null
)

/** Reserved multimodal attachment type (unused in Phase 5). */
data class AIAttachment(
    val id: String,
    val mimeType: String,
    val sizeBytes: Long
)

/** Token usage as reported by the provider. Nulls mean "provider did not report". */
data class AIUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val cachedTokens: Long? = null,
    val reasoningTokens: Long? = null
) {
    val isKnown: Boolean
        get() = inputTokens != null || outputTokens != null || totalTokens != null
}

/**
 * Estimated cost in USD for one request. Only computable when BOTH usage and
 * verified per-model pricing exist; otherwise callers must show "Cost unavailable".
 */
object AICostEstimator {
    /**
     * Returns null when pricing or usage is unknown. Never fabricates prices.
     */
    fun estimate(usage: AIUsage, model: AIModel): Double? {
        val inputPrice = model.inputPricing ?: return null
        val outputPrice = model.outputPricing ?: return null
        val inputTokens = usage.inputTokens ?: usage.totalTokens ?: return null
        val outputTokens = usage.outputTokens ?: 0L
        return (inputTokens * inputPrice + outputTokens * outputPrice) / 1_000_000.0
    }
}

/** Result of a provider connection test. Never contains secret material. */
data class ProviderHealth(
    val providerId: String,
    val reachable: Boolean,
    val authenticated: Boolean,
    val message: String,
    val latencyMs: Long? = null,
    val checkedAt: Long = System.currentTimeMillis()
)
