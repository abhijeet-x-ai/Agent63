package com.devstation.android.core.agent

import com.devstation.android.core.ai.AIMessage
import com.devstation.android.core.ai.AIMessageRole
import com.devstation.android.core.ai.AIToolCall

/**
 * Builds the message list sent to the model and keeps it inside the context budget
 * (Phase 6 §29–§32).
 *
 * Rules:
 * - The user's goal is always kept.
 * - The most recent turns are always kept.
 * - The oldest tool outputs are shrunk first (they are the largest and least likely to matter),
 *   with an explicit marker. Only if that is not enough are older turns dropped.
 * - Nothing is dropped silently.
 */
class AgentContextBuilder(private val limits: AgentLoopLimits) {

    private val shrunkMarker = "[Earlier tool output omitted to stay within the context budget.]"

    /** Opens a task: recent conversation history, then the goal. */
    fun initialMessages(goal: String, history: List<AIMessage> = emptyList()): MutableList<AIMessage> {
        val messages = mutableListOf<AIMessage>()
        val trimmedHistory = history
            .filter { it.role == AIMessageRole.USER || it.role == AIMessageRole.ASSISTANT }
            .takeLast(MAX_HISTORY_MESSAGES)
        messages.addAll(trimmedHistory)
        messages.add(AIMessage(role = AIMessageRole.USER, content = goal))
        enforceBudget(messages)
        return messages
    }

    /** Appends the assistant turn that requested tools. */
    fun appendAssistantTurn(messages: MutableList<AIMessage>, text: String, toolCalls: List<AIToolCall>) {
        messages.add(
            AIMessage(
                role = AIMessageRole.ASSISTANT,
                content = OutputLimiter.truncate(text, limits.maxToolOutputChars),
                toolCalls = toolCalls
            )
        )
        enforceBudget(messages)
    }

    /**
     * Appends a tool result. The payload is structured JSON and the tool's own output already
     * carries its provenance label, so the model can tell data from instructions.
     */
    fun appendToolResult(messages: MutableList<AIMessage>, call: AIToolCall, result: ToolResult) {
        messages.add(
            AIMessage(
                role = AIMessageRole.TOOL,
                content = result.toModelPayload(),
                toolCallId = call.id,
                toolName = call.name
            )
        )
        enforceBudget(messages)
    }

    fun estimateChars(messages: List<AIMessage>): Int =
        messages.sumOf { message ->
            message.content.length +
                message.toolCalls.sumOf { it.name.length + it.argumentsJson.length }
        }

    /**
     * Enforce [AgentLoopLimits.maxContextChars]. Returns true when something was trimmed.
     */
    fun enforceBudget(messages: MutableList<AIMessage>): Boolean {
        if (messages.isEmpty()) return false
        var trimmed = false

        // 1. Shrink the oldest, largest tool results.
        var total = estimateChars(messages)
        while (total > limits.maxContextChars) {
            val index = messages.indices.firstOrNull { i ->
                messages[i].role == AIMessageRole.TOOL && messages[i].content.length > shrunkMarker.length
            } ?: break
            val target = messages[index]
            total -= target.content.length
            messages[index] = target.copy(content = shrunkMarker)
            total += shrunkMarker.length
            trimmed = true
        }

        // 2. Shrink oldest assistant text.
        total = estimateChars(messages)
        while (total > limits.maxContextChars) {
            val index = messages.indices.firstOrNull { i ->
                messages[i].role == AIMessageRole.ASSISTANT &&
                    messages[i].content.length > shrunkMarker.length &&
                    i < messages.size - MIN_RECENT_MESSAGES
            } ?: break
            val target = messages[index]
            total -= target.content.length
            messages[index] = target.copy(content = shrunkMarker)
            total += shrunkMarker.length
            trimmed = true
        }

        // 3. Drop the oldest history turns, keeping the goal (index 0) and recent messages.
        while (estimateChars(messages) > limits.maxContextChars && messages.size > MIN_KEPT_MESSAGES) {
            val dropIndex = messages.indices.firstOrNull { i ->
                i > 0 &&
                    i < messages.size - MIN_RECENT_MESSAGES &&
                    messages[i].role != AIMessageRole.TOOL
            } ?: break
            messages.removeAt(dropIndex)
            trimmed = true
        }

        return trimmed
    }

    companion object {
        const val MAX_HISTORY_MESSAGES = 12
        const val MIN_KEPT_MESSAGES = 4
        const val MIN_RECENT_MESSAGES = 3
    }
}
