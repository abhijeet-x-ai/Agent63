package com.devstation.android.core.agent

/**
 * Phase 6: agent state machine. Every transition is explicit — the runtime sets the state
 * and emits a matching event, and non-transient states are persisted (see [AgentTaskEntity]).
 */
enum class AgentState {
    IDLE,
    PLANNING,
    WAITING_FOR_APPROVAL,
    EXECUTING_TOOL,
    WAITING_FOR_MODEL,
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED,

    /** Set by startup recovery for a task that did not finish before the app was closed. */
    INTERRUPTED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == INTERRUPTED

    companion object {
        fun fromStorage(raw: String): AgentState = entries.firstOrNull { it.name == raw } ?: IDLE
    }
}

/** Status of one line in the agent timeline. */
enum class AgentStepStatus { RUNNING, SUCCESS, FAILED, DENIED, CANCELLED, PENDING }

/**
 * Normalized agent events. The UI consumes ONLY these — it never parses provider JSON and
 * never sees hidden reasoning. Text passed to the UI is either user-visible model output or a
 * concise tool/status label.
 */
sealed class AgentEvent {
    data class AgentStarted(val taskId: String, val goal: String) : AgentEvent()

    /** Short status label only (e.g. "Waiting for model"). Never chain-of-thought. */
    data class AgentThinking(val label: String) : AgentEvent()

    /** Assistant-visible text for this turn. */
    data class AgentMessage(val text: String) : AgentEvent()

    data class ToolCallRequested(val toolName: String, val summary: String) : AgentEvent()

    data class ToolApprovalRequired(
        val requestId: String,
        val toolName: String,
        val title: String,
        val target: String,
        val riskLevel: ToolRiskLevel,
        val detail: String
    ) : AgentEvent()

    data class ToolStarted(val toolName: String, val summary: String) : AgentEvent()

    /** Streamed/bounded tool output line (already redacted and truncated). */
    data class ToolOutput(val toolName: String, val line: String) : AgentEvent()

    data class ToolCompleted(val toolName: String, val summary: String) : AgentEvent()

    data class ToolFailed(val toolName: String, val message: String) : AgentEvent()

    data class ToolDenied(val toolName: String, val reason: String) : AgentEvent()

    data class AgentWaiting(val label: String) : AgentEvent()

    data class AgentLimitReached(val message: String) : AgentEvent()

    data class AgentCompleted(val summary: AgentRunSummary) : AgentEvent()

    data class AgentFailed(val message: String, val toolName: String? = null) : AgentEvent()

    data class AgentCancelled(val reason: String = "Stopped by user") : AgentEvent()
}

/** Hard bounds on a single agent task. Reaching any of these stops the agent. */
data class AgentLoopLimits(
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
    val maxTaskDurationMs: Long = DEFAULT_MAX_TASK_DURATION_MS,
    val maxToolOutputChars: Int = DEFAULT_MAX_TOOL_OUTPUT_CHARS,
    val maxFileReadLines: Int = DEFAULT_MAX_FILE_READ_LINES,
    val maxSearchResults: Int = DEFAULT_MAX_SEARCH_RESULTS,
    val maxContextChars: Int = DEFAULT_MAX_CONTEXT_CHARS
) {
    init {
        require(maxIterations in 1..500) { "maxIterations out of range" }
        require(maxToolCalls in 1..1000) { "maxToolCalls out of range" }
        require(maxTaskDurationMs > 0) { "maxTaskDurationMs must be positive" }
        require(maxToolOutputChars in 1_000..2_000_000) { "maxToolOutputChars out of range" }
        require(maxFileReadLines in 10..20_000) { "maxFileReadLines out of range" }
        require(maxSearchResults in 1..1_000) { "maxSearchResults out of range" }
        require(maxContextChars in 4_000..4_000_000) { "maxContextChars out of range" }
    }

    companion object {
        const val DEFAULT_MAX_ITERATIONS = 25
        const val DEFAULT_MAX_TOOL_CALLS = 50
        const val DEFAULT_MAX_TASK_DURATION_MS = 600_000L
        const val DEFAULT_MAX_TOOL_OUTPUT_CHARS = 24_000
        const val DEFAULT_MAX_FILE_READ_LINES = 400
        const val DEFAULT_MAX_SEARCH_RESULTS = 100
        const val DEFAULT_MAX_CONTEXT_CHARS = 120_000
    }
}

/** Why a task is running in this process; used for UI + persistence. */
data class AgentTaskRequest(
    val goal: String,
    val projectId: String,
    val conversationId: String? = null
)

/** Live, immutable snapshot of the active task. */
data class AgentTaskSnapshot(
    val taskId: String,
    val goal: String,
    val projectId: String,
    val conversationId: String?,
    val state: AgentState,
    val providerId: String?,
    val modelId: String?,
    val iterationCount: Int = 0,
    val toolCallCount: Int = 0,
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val errorMessage: String? = null,
    val timeline: List<AgentTimelineEntry> = emptyList(),
    val summary: AgentRunSummary? = null
)

data class AgentTimelineEntry(
    val id: String,
    val label: String,
    val detail: String? = null,
    val status: AgentStepStatus,
    val createdAt: Long = System.currentTimeMillis()
)

/** Final report shown to the user. Only claims what actually happened. */
data class AgentRunSummary(
    val filesChanged: List<String> = emptyList(),
    val commandsExecuted: List<String> = emptyList(),
    val testsRun: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val iterations: Int = 0,
    val toolCalls: Int = 0,
    val durationMs: Long = 0L,
    val finalMessage: String = ""
)
