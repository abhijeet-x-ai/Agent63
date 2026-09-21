package com.devstation.android.core.ai

import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.model.MessageRole
import com.devstation.android.core.repository.AISettingsRepository
import com.devstation.android.core.repository.AIUsageRepository
import com.devstation.android.core.repository.ConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.ceil

/**
 * Chat use-case layer between the UI and [AIProviderManager].
 *
 * Responsibilities:
 * - Build [AIRequest] from persisted conversation history + per-conversation model
 *   (falling back to the AI settings default).
 * - Stream normalized events to the caller while batch-persisting the growing
 *   assistant message (throttled; never one Room write per token).
 * - Persist usage metadata separately (spec §23/§24).
 * - Enforce context-window estimation warnings (clearly labeled estimate, spec §41).
 *
 * Security: receives NO filesystem/terminal/editor/runtime dependencies.
 */
class ChatOrchestrator(
    private val providerManager: AIProviderManager,
    private val conversationRepository: ConversationRepository,
    private val aiSettingsRepository: AISettingsRepository,
    private val usageRepository: AIUsageRepository,
    private val dispatchers: DispatcherProvider
) {

    /** Rough token estimate when exact tokenization is unavailable (~4 chars/token). */
    fun estimateTokens(text: String): Int = ceil(text.length / 4.0).toInt()

    data class ContextCheck(
        val estimatedTokens: Int,
        val contextWindow: Long?,
        val exceedsContext: Boolean,
        val nearLimit: Boolean
    )

    fun checkContext(model: AIModel, messages: List<AIMessage>, systemInstruction: String?): ContextCheck {
        val total = messages.sumOf { estimateTokens(it.content) } + (systemInstruction?.let { estimateTokens(it) } ?: 0)
        val window = model.contextWindow
        return ContextCheck(
            estimatedTokens = total,
            contextWindow = window,
            exceedsContext = window != null && total > window * 0.95,
            nearLimit = window != null && total > window * 0.8 && total <= window * 0.95
        )
    }

    /**
     * Build a normalized request for [conversationId].
     * Model resolution order: conversation override -> AI settings default -> error.
     */
    suspend fun buildRequest(
        conversationId: String,
        stream: Boolean,
        providerIdOverride: String? = null,
        modelIdOverride: String? = null
    ): Result<Pair<AIProvider, AIRequest>> = withContext(dispatchers.io) {
        runCatching {
            val settings = aiSettingsRepository.get()
            val conversation = conversationRepository.getConversationById(conversationId)
                ?: throw AIError.InvalidRequestError("Conversation not found.")
            val providerId = providerIdOverride
                ?: conversation.providerId
                ?: settings.defaultProviderId
                ?: throw AIError.InvalidRequestError("No AI provider configured. Set a default provider in AI Settings.")
            val modelId = modelIdOverride
                ?: conversation.modelId
                ?: settings.defaultModelId
            val provider = providerManager.getProvider(providerId)
                ?: throw AIError.ProviderUnavailableError("Provider $providerId is not available.")
            val resolved = providerManager.resolveModel(providerId, modelId).getOrThrow()
            val history = conversationRepository.getMessagesOnce(conversationId)
            val chatMessages = history
                .filter { it.role != MessageRole.SYSTEM }
                .map { AIMessage(role = it.role.toAiRole(), content = it.content, timestamp = it.createdAt, id = it.id) }
                .takeLast(MAX_HISTORY_MESSAGES)
            val request = AIRequest(
                conversationId = conversationId,
                modelId = resolved.modelId,
                messages = chatMessages,
                systemInstruction = null,
                stream = stream,
                metadata = mapOf("providerId" to providerId)
            )
            provider to request
        }
    }

    /**
     * Stream a completion for [conversationId], persisting the assistant message
     * with throttled batching and recording usage when reported.
     * Cancellation propagates through [Job.cancel] from the UI/ViewModel.
     */
    fun streamChat(
        conversationId: String,
        provider: AIProvider,
        request: AIRequest
    ): Flow<AIResponseEvent> {
        val assistantMessageId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()

        return provider.generate(request)
            .transform { event ->
                when (event) {
                    is AIResponseEvent.TextDelta -> {
                        buffer.append(event.text)
                        val now = System.currentTimeMillis()
                        if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
                            persistPartial(conversationId, assistantMessageId)
                            lastPersistAt = now
                        }
                        emit(event)
                    }
                    is AIResponseEvent.Usage -> {
                        capturedUsage = event.usage
                        emit(event)
                    }
                    is AIResponseEvent.Completed -> {
                        persistFinal(conversationId, assistantMessageId)
                        capturedUsage?.let { usage ->
                            usageRepository.record(
                                conversationId = conversationId,
                                messageId = assistantMessageId,
                                providerId = provider.providerId,
                                modelId = request.modelId,
                                inputTokens = usage.inputTokens,
                                outputTokens = usage.outputTokens,
                                totalTokens = usage.totalTokens,
                                cachedTokens = usage.cachedTokens,
                                reasoningTokens = usage.reasoningTokens,
                                estimatedCostUsd = null // pricing not verified in Phase 5; cost stays unavailable
                            )
                        }
                        emit(event)
                    }
                    is AIResponseEvent.Error -> {
                        persistFailed(conversationId, assistantMessageId, event.error)
                        emit(event)
                    }
                    is AIResponseEvent.Cancelled -> {
                        persistFinal(conversationId, assistantMessageId)
                        emit(event)
                    }
                    else -> emit(event)
                }
            }
            .catch { throwable ->
                if (throwable is CancellationException) {
                    persistFinal(conversationId, assistantMessageId)
                    emit(AIResponseEvent.Cancelled)
                } else {
                    val error = AIErrorMapper.fromThrowable(throwable)
                    persistFailed(conversationId, assistantMessageId, error)
                    emit(AIResponseEvent.Error(error))
                }
            }
            .flowOn(dispatchers.io)
    }

    private val buffer = StringBuilder()
    private var lastPersistAt = 0L
    private var capturedUsage: AIUsage? = null

    private suspend fun persistPartial(conversationId: String, messageId: String) {
        val text = buffer.toString()
        if (text.isBlank()) return
        conversationRepository.upsertMessage(
            conversationId = conversationId,
            messageId = messageId,
            role = MessageRole.ASSISTANT,
            content = text
        )
    }

    private suspend fun persistFinal(conversationId: String, messageId: String) {
        persistPartial(conversationId, messageId)
        lastPersistAt = 0L
    }

    private suspend fun persistFailed(conversationId: String, messageId: String, error: AIError) {
        val settings = aiSettingsRepository.get()
        if (!settings.saveFailedRequests) return
        val existing = buffer.toString()
        val text = if (existing.isBlank()) "⚠ ${error.message}" else existing
        conversationRepository.upsertMessage(
            conversationId = conversationId,
            messageId = messageId,
            role = MessageRole.ASSISTANT,
            content = text,
            errorState = error::class.simpleName
        )
    }

    suspend fun cancelIfRunning(conversationId: String): Boolean {
        // Cancellation is owned by the ViewModel Job; this is a hook for future managers.
        return true
    }

    companion object {
        const val PERSIST_INTERVAL_MS = 500L
        const val MAX_HISTORY_MESSAGES = 40
    }
}

fun MessageRole.toAiRole(): AIMessageRole = when (this) {
    MessageRole.USER -> AIMessageRole.USER
    MessageRole.ASSISTANT -> AIMessageRole.ASSISTANT
    MessageRole.SYSTEM -> AIMessageRole.SYSTEM
}

fun AIMessageRole.toMessageRole(): MessageRole = when (this) {
    AIMessageRole.USER -> MessageRole.USER
    AIMessageRole.ASSISTANT -> MessageRole.ASSISTANT
    AIMessageRole.SYSTEM -> MessageRole.SYSTEM
    // Phase 6: tool results are internal agent context and are never shown as chat messages.
    AIMessageRole.TOOL -> MessageRole.SYSTEM
}
