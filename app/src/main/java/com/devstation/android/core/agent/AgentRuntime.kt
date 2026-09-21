package com.devstation.android.core.agent

import com.devstation.android.core.agent.tools.AgentToolFactory
import com.devstation.android.core.agent.tools.AgentProcessRegistry
import com.devstation.android.core.ai.AIError
import com.devstation.android.core.ai.AIRequest
import com.devstation.android.core.ai.AIResponseEvent
import com.devstation.android.core.ai.AIUsage
import com.devstation.android.core.ai.AIToolCall
import com.devstation.android.core.ai.AIMessage
import com.devstation.android.core.ai.AIProvider
import com.devstation.android.core.ai.AIProviderManager
import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.database.AgentTaskEntity
import com.devstation.android.core.model.Project
import com.devstation.android.core.repository.AISettingsRepository
import com.devstation.android.core.security.policy.AuditDecision
import com.devstation.android.core.security.policy.ResourceType
import com.devstation.android.core.security.policy.SecurityAction
import com.devstation.android.core.security.policy.SecurityAuditLogger
import com.devstation.android.core.security.policy.SecurityEventType
import com.devstation.android.core.security.policy.SecurityPolicyEngine
import com.devstation.android.core.security.policy.SecurityRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Resolves a project by id without coupling the agent to Room. */
fun interface ProjectLocator {
    suspend fun find(projectId: String): Project?
}

/** Conversation access the agent needs, kept narrow so the loop is testable without a database. */
interface AgentConversationPort {
    suspend fun history(conversationId: String): List<AIMessage>
    /** Per-conversation provider/model override, if the conversation has one. */
    suspend fun providerSelection(conversationId: String): Pair<String?, String?>
    suspend fun persistAssistant(conversationId: String, content: String)
}

sealed class AgentStartResult {
    data class Started(val taskId: String) : AgentStartResult()
    data class Rejected(val message: String) : AgentStartResult()
}

/**
 * The controlled agent loop (Phase 6 §5–§7).
 *
 * Guarantees:
 * - bounded: iteration, tool-call and duration limits stop the task with an explicit message;
 * - explicit: every state change is set and emitted, never inferred;
 * - gated: every tool call goes through [ToolExecutor] → [PermissionManager] → user approval;
 * - cancellable: [stop] cancels the model request, the tool, and agent-owned processes;
 * - observable: the UI only ever sees [AgentEvent]s and an immutable [AgentTaskSnapshot].
 */
class AgentRuntime(
    private val providerManager: AIProviderManager,
    private val aiSettingsRepository: AISettingsRepository,
    private val projectLocator: ProjectLocator,
    private val conversationPort: AgentConversationPort,
    private val toolFactory: AgentToolFactory,
    private val permissionManager: PermissionManager,
    private val broker: ApprovalBroker,
    private val processRegistry: AgentProcessRegistry,
    private val taskStore: AgentTaskStore,
    private val eventStore: AgentEventStore,
    private val historyStore: AgentHistoryStore,
    private val permissionStore: AgentPermissionStore,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    /** Phase 7: centralized security engine. Null keeps the phase 6-equivalent default policy. */
    private val securityEngine: SecurityPolicyEngine? = null,
    private val audit: SecurityAuditLogger = SecurityAuditLogger.NoOp,
    /** Phase 7: identity of this DevStation session, used for session-scoped grants (§60). */
    private val sessionId: String? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val _state = MutableStateFlow<AgentTaskSnapshot?>(null)
    val state: StateFlow<AgentTaskSnapshot?> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /** Approval card currently blocking the loop, if any. */
    val pendingApproval: StateFlow<ApprovalRequest?> = broker.pending

    private val startMutex = Mutex()
    private var runningJob: Job? = null
    private val cancelled = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Startup recovery: tasks that were running when the app closed become INTERRUPTED and all
     * task-scoped permissions are dropped. Phase 6 never auto-resumes autonomous execution.
     */
    suspend fun recoverInterruptedTasks() {
        runCatching {
            taskStore.markInterrupted()
            permissionStore.clearAll()
            permissionManager.clearAllPermissions()
        }
    }

    /** UI decision for the pending approval card. */
    fun submitDecision(decision: ApprovalDecision): Boolean = broker.submit(decision)

    /**
     * Start a task. Rejects (with a user-facing reason) instead of silently degrading when the
     * agent cannot run: no project, no provider, tools disabled, or a model without tool calling.
     */
    suspend fun startTask(request: AgentTaskRequest): AgentStartResult = startMutex.withLock {
        if (runningJob?.isActive == true) {
            return AgentStartResult.Rejected("An agent task is already running. Stop it before starting another.")
        }
        val goal = request.goal.trim()
        if (goal.isEmpty()) {
            return AgentStartResult.Rejected("Describe what the agent should do.")
        }

        val settings = aiSettingsRepository.get()
        if (!settings.agentToolsEnabled) {
            return AgentStartResult.Rejected(PermissionManager.AGENT_DISABLED_MESSAGE)
        }

        val project = projectLocator.find(request.projectId)
            ?: return AgentStartResult.Rejected("Project not found. Select a project first.")
        val projectRoot = File(project.localPath)
        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            return AgentStartResult.Rejected("The project folder is not available on this device.")
        }

        val (conversationProvider, conversationModel) = request.conversationId
            ?.let { conversationPort.providerSelection(it) }
            ?: (null to null)
        val providerId = conversationProvider ?: settings.defaultProviderId
            ?: return AgentStartResult.Rejected("No AI provider configured. Choose a default in AI Settings.")
        val provider = providerManager.getProvider(providerId)
            ?: return AgentStartResult.Rejected("Provider '$providerId' is not available.")
        if (!provider.capabilities.chat) {
            return AgentStartResult.Rejected("${provider.displayName} cannot run chat requests.")
        }
        if (!provider.capabilities.toolCalling) {
            return AgentStartResult.Rejected(
                "${provider.displayName} supports chat but not agent tool execution."
            )
        }
        val model = providerManager.resolveModel(providerId, conversationModel ?: settings.defaultModelId)
            .getOrElse { error ->
                return AgentStartResult.Rejected(error.message ?: "No model selected for this provider.")
            }
        if (!model.capabilities.toolCalling) {
            return AgentStartResult.Rejected(
                "${model.displayName} supports chat but not agent tool execution."
            )
        }

        val limits = AgentLoopLimits(
            maxIterations = settings.agentMaxIterations.coerceIn(1, 500),
            maxToolCalls = settings.agentMaxToolCalls.coerceIn(1, 1000),
            maxTaskDurationMs = settings.agentMaxTaskSeconds.coerceIn(30, 7_200) * 1000,
            maxToolOutputChars = settings.agentMaxToolOutputChars.coerceIn(1_000, 2_000_000)
        )

        val taskId = UUID.randomUUID().toString()
        val now = clock()
        val snapshot = AgentTaskSnapshot(
            taskId = taskId,
            goal = goal,
            projectId = request.projectId,
            conversationId = request.conversationId,
            state = AgentState.PLANNING,
            providerId = providerId,
            modelId = model.modelId,
            startedAt = now,
            updatedAt = now
        )
        cancelled.set(false)
        _state.value = snapshot

        taskStore.create(
            AgentTaskEntity(
                taskId = taskId,
                projectId = request.projectId,
                conversationId = request.conversationId,
                goal = goal,
                state = AgentState.PLANNING.name,
                providerId = providerId,
                modelId = model.modelId,
                createdAt = now,
                updatedAt = now
            )
        )
        runningJob = scope.launch(dispatchers.io) {
            runTask(snapshot, project, projectRoot, provider, model.modelId, limits, settings.agentToolsEnabled)
        }
        runCatching {
            audit.log(
                type = SecurityEventType.PERMISSION_REQUESTED,
                decision = AuditDecision.RECORDED,
                summary = "Agent task started: $goal",
                riskLevel = ToolRiskLevel.LOW,
                request = SecurityRequest(
                    projectId = request.projectId,
                    projectRoot = projectRoot,
                    taskId = taskId,
                    sessionId = sessionId,
                    agentId = taskId,
                    toolName = "agent_task",
                    action = SecurityAction.CONTROL,
                    resourceType = ResourceType.PROCESS
                )
            )
        }
        return AgentStartResult.Started(taskId)
    }

    /** Stop the running task: cancels the request, the tool, and any agent-owned process. */
    fun stop(reason: String = "Stopped by user") {
        cancelled.set(true)
        broker.denyPending()
        val taskId = _state.value?.taskId
        if (taskId != null) processRegistry.terminate(taskId)
        runningJob?.cancel(CancellationException(reason))
        auditTask(
            SecurityEventType.AGENT_CANCELLED,
            AuditDecision.BLOCKED,
            "Agent stopped: $reason"
        )
    }

    /** Emergency stop: cancels the loop, clears approvals and terminates all agent processes. */
    suspend fun stopAll() {
        cancelled.set(true)
        broker.denyPending()
        val terminated = processRegistry.terminateAll()
        runningJob?.cancel(CancellationException("Emergency stop"))
        runningJob = null
        permissionManager.clearAllPermissions()
        runCatching { permissionStore.clearAll() }
        _state.value = _state.value?.copy(state = AgentState.CANCELLED, updatedAt = clock())
        auditTask(
            SecurityEventType.AGENT_CANCELLED,
            AuditDecision.BLOCKED,
            "Emergency stop: cancelled the agent task, cleared approvals and stopped $terminated agent process(es)."
        )
    }

    /** §34: task lifecycle events go into the security audit log. */
    private fun auditTask(type: SecurityEventType, decision: AuditDecision, summary: String) {
        val snapshot = _state.value
        scope.launch(dispatchers.io) {
            runCatching {
                audit.log(
                    type = type,
                    decision = decision,
                    summary = summary,
                    request = SecurityRequest(
                        projectId = snapshot?.projectId,
                        projectRoot = null,
                        taskId = snapshot?.taskId,
                        sessionId = sessionId,
                        agentId = snapshot?.taskId,
                        toolName = "agent_task",
                        action = SecurityAction.CONTROL,
                        resourceType = ResourceType.PROCESS
                    )
                )
            }
        }
    }

    private suspend fun runTask(
        snapshot: AgentTaskSnapshot,
        project: Project,
        projectRoot: File,
        provider: AIProvider,
        modelId: String,
        limits: AgentLoopLimits,
        agentToolsEnabled: Boolean
    ) {
        val taskId = snapshot.taskId
        val startedAt = snapshot.startedAt
        val registry = toolFactory.create(limits)
        val executor = ToolExecutor(
            registry = registry,
            permissionManager = permissionManager,
            limits = limits,
            securityEngine = securityEngine,
            audit = audit,
            sessionId = sessionId
        )
        val contextBuilder = AgentContextBuilder(limits)
        val toolContext = ToolContext(
            projectId = project.id,
            projectRoot = projectRoot,
            workingDirectory = projectRoot,
            agentId = taskId,
            taskId = taskId,
            scope = PermissionScope.PER_TASK,
            cancellationCheck = { cancelled.get() }
        )

        var iterations = 0
        var toolCalls = 0
        val filesChanged = linkedSetOf<String>()
        val commandsExecuted = linkedSetOf<String>()
        val testsRun = linkedSetOf<String>()
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var lastAssistantText = ""

        try {
            withContext(NonCancellable) {
                addTimeline("Task started", snapshot.goal, AgentStepStatus.SUCCESS)
            }
            emit(AgentEvent.AgentStarted(taskId, snapshot.goal))

            val history = snapshot.conversationId?.let { runCatching { conversationPort.history(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            val messages = contextBuilder.initialMessages(snapshot.goal, history)

            while (true) {
                if (cancelled.get()) {
                    finalize(taskId, AgentState.CANCELLED, errors = errors + "Stopped by user")
                    emit(AgentEvent.AgentCancelled())
                    return
                }
                if (iterations >= limits.maxIterations) {
                    limitReached(taskId, limits, errors)
                    return
                }
                if (clock() - startedAt > limits.maxTaskDurationMs) {
                    limitReached(taskId, limits, errors)
                    return
                }

                iterations++
                updateProgress(taskId, AgentState.WAITING_FOR_MODEL, iterations, toolCalls)
                emit(AgentEvent.AgentWaiting("Waiting for model"))

                val request = AIRequest(
                    conversationId = snapshot.conversationId,
                    modelId = modelId,
                    messages = messages.toList(),
                    systemInstruction = AgentSystemPrompt.build(
                        projectName = project.name,
                        projectPath = projectRoot.absolutePath,
                        tools = registry.definitions(),
                        limits = limits,
                        linuxAvailable = true,
                        hasProject = true
                    ),
                    stream = true,
                    tools = registry.specs(),
                    toolChoice = "auto"
                )

                val turn = collectTurn(provider, request)
                if (turn.error != null) {
                    errors.add(turn.error.message ?: "Provider error.")
                    finalize(taskId, AgentState.FAILED, errors = errors, message = turn.error.message)
                    emit(AgentEvent.AgentFailed(turn.error.message ?: "The model request failed."))
                    return
                }
                if (turn.thinking.isNotBlank()) {
                    emit(AgentEvent.AgentThinking("Thinking…"))
                }
                if (turn.text.isNotBlank()) {
                    lastAssistantText = turn.text
                    emit(AgentEvent.AgentMessage(turn.text))
                }

                if (turn.toolCalls.isEmpty()) {
                    complete(taskId, snapshot, limits, lastAssistantText, iterations, toolCalls, startedAt, filesChanged, commandsExecuted, testsRun, warnings, errors)
                    return
                }
                if (toolCalls + turn.toolCalls.size > limits.maxToolCalls) {
                    limitReached(taskId, limits, errors)
                    return
                }

                contextBuilder.appendAssistantTurn(messages, turn.text, turn.toolCalls)

                for (call in turn.toolCalls) {
                    if (cancelled.get()) {
                        finalize(taskId, AgentState.CANCELLED, errors = errors + "Stopped by user")
                        emit(AgentEvent.AgentCancelled())
                        return
                    }
                    toolCalls++
                    updateProgress(taskId, AgentState.PLANNING, iterations, toolCalls)

                    val entryId = addTimeline(
                        label = toolLabel(call),
                        detail = null,
                        status = AgentStepStatus.RUNNING
                    )
                    val result = executor.execute(call, toolContext, agentToolsEnabled, ::emit)

                    when (result) {
                        is ToolResult.Success -> {
                            updateTimeline(entryId, AgentStepStatus.SUCCESS, result.output.firstLineSummary())
                            if (call.name in toolFactory.fileMutationToolNames) {
                                filesChanged.add(result.metadata["path"] ?: toolTarget(call))
                            }
                            if (call.name == "run_terminal_command") {
                                val command = commandOf(call)
                                if (command.isNotBlank()) {
                                    commandsExecuted.add(command)
                                    if (looksLikeTestCommand(command)) testsRun.add(command)
                                }
                            }
                        }
                        is ToolResult.Error -> {
                            errors.add("${call.name}: ${result.message}")
                            updateTimeline(entryId, AgentStepStatus.FAILED, result.message)
                        }
                        is ToolResult.Denied -> {
                            warnings.add("Denied: ${call.name} — ${result.reason}")
                            updateTimeline(entryId, AgentStepStatus.DENIED, result.reason)
                        }
                        is ToolResult.Cancelled -> {
                            updateTimeline(entryId, AgentStepStatus.CANCELLED, result.reason)
                        }
                        is ToolResult.Timeout -> {
                            errors.add("${call.name}: ${result.output}")
                            updateTimeline(entryId, AgentStepStatus.FAILED, result.output)
                        }
                    }

                    historyStore.append(
                        taskId = taskId,
                        projectId = project.id,
                        toolName = call.name,
                        summary = toolLabel(call),
                        status = result.statusName
                    )
                    contextBuilder.appendToolResult(messages, call, result)
                }

                if (toolCalls >= limits.maxToolCalls) {
                    limitReached(taskId, limits, errors)
                    return
                }
            }
        } catch (ce: CancellationException) {
            runCatching {
                addTimeline("Stopped", "Stopped by user", AgentStepStatus.CANCELLED)
                finalize(taskId, AgentState.CANCELLED, errors = errors + "Stopped by user", message = ce.message)
            }
            emit(AgentEvent.AgentCancelled(ce.message ?: "Stopped by user"))
            throw ce
        } catch (t: Throwable) {
            errors.add(t.message ?: "Unexpected agent error.")
            runCatching { finalize(taskId, AgentState.FAILED, errors = errors, message = t.message) }
            emit(AgentEvent.AgentFailed(t.message ?: "The agent stopped because of an unexpected error."))
        } finally {
            releaseResources(taskId)
        }
    }

    // ---- model turn ----

    private class ModelTurn(
        val text: String,
        val thinking: String,
        val toolCalls: List<AIToolCall>,
        val usage: AIUsage?,
        val error: AIError?
    )

    private suspend fun collectTurn(provider: AIProvider, request: AIRequest): ModelTurn {
        val text = StringBuilder()
        val thinking = StringBuilder()
        val toolCalls = mutableListOf<AIToolCall>()
        var usage: AIUsage? = null
        var error: AIError? = null
        try {
            provider.generate(request).collect { event ->
                when (event) {
                    is AIResponseEvent.TextDelta -> text.append(event.text)
                    is AIResponseEvent.ThinkingDelta -> {
                        // Never surfaced or stored: only a boolean "thinking happened" is used.
                        if (thinking.length < THINKING_PROBE_LIMIT) thinking.append(event.text)
                    }
                    is AIResponseEvent.ToolCallRequested -> toolCalls.addAll(event.calls)
                    is AIResponseEvent.Usage -> usage = event.usage
                    is AIResponseEvent.Error -> error = event.error
                    is AIResponseEvent.Cancelled -> error = AIError.CancelledError()
                    else -> Unit
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            error = com.devstation.android.core.ai.AIErrorMapper.fromThrowable(t)
        }
        return ModelTurn(
            text = text.toString().trim(),
            thinking = if (thinking.isBlank()) "" else "thinking",
            toolCalls = toolCalls.distinctBy { it.id },
            usage = usage,
            error = error
        )
    }

    // ---- terminal states ----

    private suspend fun complete(
        taskId: String,
        snapshot: AgentTaskSnapshot,
        limits: AgentLoopLimits,
        finalText: String,
        iterations: Int,
        toolCalls: Int,
        startedAt: Long,
        filesChanged: Set<String>,
        commandsExecuted: Set<String>,
        testsRun: Set<String>,
        warnings: List<String>,
        errors: List<String>
    ) {
        val summary = AgentRunSummary(
            filesChanged = filesChanged.toList(),
            commandsExecuted = commandsExecuted.toList(),
            testsRun = testsRun.toList(),
            warnings = warnings.toList(),
            errors = errors.toList(),
            iterations = iterations,
            toolCalls = toolCalls,
            durationMs = clock() - startedAt,
            finalMessage = finalText
        )
        if (snapshot.conversationId != null && finalText.isNotBlank()) {
            runCatching { conversationPort.persistAssistant(snapshot.conversationId, finalText) }
        }
        addTimeline("Completed", summaryLine(summary), AgentStepStatus.SUCCESS)
        finalize(taskId, AgentState.COMPLETED, errors = errors, summary = summary)
        emit(AgentEvent.AgentCompleted(summary))
    }

    private suspend fun limitReached(taskId: String, limits: AgentLoopLimits, errors: List<String>) {
        val message = "Agent stopped because the execution limit was reached."
        addTimeline("Limit reached", message, AgentStepStatus.FAILED)
        finalize(taskId, AgentState.FAILED, errors = errors + message, message = message)
        emit(AgentEvent.AgentLimitReached(message))
        emit(AgentEvent.AgentFailed(message))
    }

    private suspend fun finalize(
        taskId: String,
        state: AgentState,
        errors: List<String> = emptyList(),
        message: String? = null,
        summary: AgentRunSummary? = null
    ) = withContext(NonCancellable) {
        runCatching { taskStore.updateState(taskId, state, message) }
        _state.value = _state.value?.copy(
            state = state,
            updatedAt = clock(),
            errorMessage = message,
            summary = summary,
            timeline = timelineSnapshot()
        )
        runCatching {
            taskStore.updateProgress(
                taskId = taskId,
                state = state,
                iterations = _state.value?.iterationCount ?: 0,
                toolCalls = _state.value?.toolCallCount ?: 0
            )
        }
    }

    /** Always runs to completion, even when the task coroutine was cancelled. */
    private suspend fun releaseResources(taskId: String) = withContext(NonCancellable) {
        broker.denyPending()
        processRegistry.terminate(taskId)
        toolFactory.clearTaskState(taskId)
        runCatching { permissionManager.revokeTaskPermissions(taskId) }
        runCatching { permissionStore.clearTask(taskId) }
        runningJob = null
    }

    // ---- snapshot / timeline helpers ----

    private suspend fun updateProgress(taskId: String, state: AgentState, iterations: Int, toolCalls: Int) {
        runCatching { taskStore.updateProgress(taskId, state, iterations, toolCalls) }
        _state.value = _state.value?.copy(
            state = state,
            iterationCount = iterations,
            toolCallCount = toolCalls,
            updatedAt = clock(),
            timeline = timelineSnapshot()
        )
    }

    private suspend fun addTimeline(label: String, detail: String?, status: AgentStepStatus): String {
        val entry = AgentTimelineEntry(
            id = UUID.randomUUID().toString(),
            label = label,
            detail = detail,
            status = status,
            createdAt = clock()
        )
        synchronized(timelineLock) {
            timeline.add(entry)
            if (timeline.size > MAX_TIMELINE_ENTRIES) timeline.removeAt(0)
            entries[entry.id] = entry
        }
        _state.value = _state.value?.copy(timeline = timelineSnapshot(), updatedAt = clock())
        runCatching { eventStore.append(_state.value?.taskId ?: "", "TIMELINE", label, detail, status) }
        return entry.id
    }

    private suspend fun updateTimeline(entryId: String, status: AgentStepStatus, detail: String?) {
        val updated = synchronized(timelineLock) {
            val existing = entries[entryId] ?: return@synchronized null
            val replacement = existing.copy(status = status, detail = detail)
            val index = timeline.indexOfFirst { it.id == entryId }
            if (index >= 0) timeline[index] = replacement
            entries[entryId] = replacement
            replacement
        } ?: return
        _state.value = _state.value?.copy(timeline = timelineSnapshot(), updatedAt = clock())
        runCatching {
            eventStore.append(
                _state.value?.taskId ?: "",
                "TIMELINE",
                updated.label,
                updated.detail,
                status
            )
        }
    }

    private fun timelineSnapshot(): List<AgentTimelineEntry> = synchronized(timelineLock) { timeline.toList() }

    private suspend fun emit(event: AgentEvent) {
        _events.tryEmit(event)
    }

    private fun toolLabel(call: AIToolCall): String =
        "${call.name} ${toolTarget(call)}".trim()

    private fun toolTarget(call: AIToolCall): String {
        val args = runCatching { json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull() ?: return ""
        val keys = listOf("path", "command", "query", "newName")
        return keys.firstNotNullOfOrNull { key -> (args[key] as? JsonPrimitive)?.content }
            ?.take(120)
            .orEmpty()
    }

    private fun commandOf(call: AIToolCall): String {
        val args = runCatching { json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull() ?: return ""
        return (args["command"] as? JsonPrimitive)?.content.orEmpty().take(200)
    }

    private fun looksLikeTestCommand(command: String): Boolean =
        TEST_COMMAND_HINTS.any { command.contains(it) }

    private fun summaryLine(summary: AgentRunSummary): String = buildString {
        append("${summary.toolCalls} tool call(s) in ${summary.iterations} turn(s)")
        if (summary.filesChanged.isNotEmpty()) append(" • ${summary.filesChanged.size} file(s) changed")
        if (summary.errors.isNotEmpty()) append(" • ${summary.errors.size} error(s)")
    }

    private fun String.firstLineSummary(): String =
        lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: "Completed"

    private val timeline = mutableListOf<AgentTimelineEntry>()
    private val entries = mutableMapOf<String, AgentTimelineEntry>()
    private val timelineLock = Any()

    private companion object {
        const val MAX_TIMELINE_ENTRIES = 200
        const val THINKING_PROBE_LIMIT = 32
        val TEST_COMMAND_HINTS = listOf("test", "spec", "check", "lint")
    }
}
