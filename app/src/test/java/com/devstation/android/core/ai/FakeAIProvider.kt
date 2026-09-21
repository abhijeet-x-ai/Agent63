package com.devstation.android.core.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * TEST-ONLY provider. Deterministic, scriptable responses ("DevStation test response.").
 * Must never be registered in production DI (see phase5_build_report.md).
 */
class FakeAIProvider(
    override val providerId: String = "fake",
    override val displayName: String = "Fake Provider",
    override val capabilities: AIProviderCapabilities = AIProviderCapabilities(
        chat = true,
        streaming = true,
        modelListing = true,
        usageReporting = true,
        systemPrompt = true
    ),
    private val models: List<AIModel> = listOf(
        AIModel(providerId = "fake", modelId = "fake-mini", displayName = "Fake Mini", contextWindow = 4096),
        AIModel(providerId = "fake", modelId = "fake-large", displayName = "Fake Large", contextWindow = 8192)
    ),
    private val scriptedText: List<String> = "DevStation test response.".split(" "),
    private val scriptedError: AIError? = null,
    private val emitDelayMs: Long = 0L
) : AIProvider {

    var lastRequest: AIRequest? = null
        private set

    override suspend fun getModels(): Result<List<AIModel>> = Result.success(models)

    override suspend fun testConnection(): Result<ProviderHealth> = Result.success(
        ProviderHealth(
            providerId = providerId,
            reachable = true,
            authenticated = true,
            message = "Connected",
            latencyMs = 1L
        )
    )

    override fun generate(request: AIRequest): Flow<AIResponseEvent> = flow {
        lastRequest = request
        if (scriptedError != null) {
            emit(AIResponseEvent.Error(scriptedError))
            return@flow
        }
        emit(AIResponseEvent.Started(providerId, request.modelId))
        for (word in scriptedText) {
            if (emitDelayMs > 0) delay(emitDelayMs)
            emit(AIResponseEvent.TextDelta("$word "))
        }
        emit(AIResponseEvent.Usage(AIUsage(inputTokens = 10, outputTokens = 8, totalTokens = 18)))
        emit(AIResponseEvent.Completed(messageId = "fake_${System.currentTimeMillis()}"))
    }
}
