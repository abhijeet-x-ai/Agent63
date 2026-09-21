package com.devstation.android.core.agent

import com.devstation.android.core.agent.tools.AgentProcessRegistry
import com.devstation.android.core.agent.tools.CommandRunResult
import com.devstation.android.core.agent.tools.CommandRunner
import com.devstation.android.core.agent.tools.EditorBridge
import com.devstation.android.core.agent.tools.EditorOpenRequest
import com.devstation.android.core.ai.AIError
import com.devstation.android.core.ai.AIMessage
import com.devstation.android.core.ai.AIModel
import com.devstation.android.core.ai.AIProvider
import com.devstation.android.core.ai.AIProviderCapabilities
import com.devstation.android.core.ai.AIRequest
import com.devstation.android.core.ai.AIResponseEvent
import com.devstation.android.core.ai.AIToolCall
import com.devstation.android.core.ai.AIToolParameter
import com.devstation.android.core.ai.AIToolParameterType
import com.devstation.android.core.ai.ProviderHealth
import com.devstation.android.core.ai.AIUsage
import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.database.AgentActionHistoryEntity
import com.devstation.android.core.database.AgentTaskEntity
import com.devstation.android.core.database.AISettingsDao
import com.devstation.android.core.database.AISettingsEntity
import com.devstation.android.core.model.Project
import com.devstation.android.core.repository.AISettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Unconfined dispatchers so agent tests are deterministic and framework-free. */
val AgentTestDispatchers = object : DispatcherProvider {
    override val main = Dispatchers.Unconfined
    override val io = Dispatchers.Unconfined
    override val default = Dispatchers.Unconfined
    override val unconfined = Dispatchers.Unconfined
}

fun agentTestScope(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

/** Creates a throwaway project directory with the given files. */
fun createTempProject(files: Map<String, String> = emptyMap()): File {
    val root = File.createTempFile("devstation-agent-", "").let { file ->
        file.delete()
        File(file.parentFile, file.name + "-dir")
    }
    root.mkdirs()
    files.forEach { (relative, content) ->
        val target = File(root, relative)
        target.parentFile?.mkdirs()
        target.writeText(content)
    }
    return root
}

fun deleteTempProject(root: File) {
    runCatching { root.deleteRecursively() }
}

// ---- settings ----

class FakeAISettingsDao(private val initial: AISettingsEntity = AISettingsEntity()) : AISettingsDao {
    private val state = MutableStateFlow(initial)
    override fun getFlow(): Flow<AISettingsEntity?> = state
    override suspend fun get(): AISettingsEntity? = state.value
    override suspend fun upsert(settings: AISettingsEntity) {
        state.value = settings
    }
}

fun fakeAiSettingsRepository(settings: AISettingsEntity = AISettingsEntity()): AISettingsRepository =
    AISettingsRepository(FakeAISettingsDao(settings), AgentTestDispatchers)

// ---- agent stores ----

class FakeAgentTaskStore : AgentTaskStore {
    val tasks = mutableMapOf<String, AgentTaskEntity>()
    override suspend fun create(task: AgentTaskEntity) {
        tasks[task.taskId] = task
    }

    override suspend fun updateProgress(taskId: String, state: AgentState, iterations: Int, toolCalls: Int) {
        tasks[taskId]?.let { tasks[taskId] = it.copy(state = state.name, iterationCount = iterations, toolCallCount = toolCalls) }
    }

    override suspend fun updateState(taskId: String, state: AgentState, error: String?) {
        tasks[taskId]?.let { tasks[taskId] = it.copy(state = state.name, errorMessage = error) }
    }

    override suspend fun get(taskId: String): AgentTaskEntity? = tasks[taskId]

    override suspend fun markInterrupted(): List<String> {
        val active = tasks.values.filter { AgentState.fromStorage(it.state).isTerminal.not() }
        active.forEach { tasks[it.taskId] = it.copy(state = AgentState.INTERRUPTED.name) }
        return active.map { it.taskId }
    }
}

class FakeAgentEventStore : AgentEventStore {
    val events = mutableListOf<Triple<String, String, AgentStepStatus>>()
    override suspend fun append(
        taskId: String,
        type: String,
        label: String,
        detail: String?,
        status: AgentStepStatus
    ) {
        events.add(Triple(taskId, label, status))
    }
}

class FakeAgentHistoryStore : AgentHistoryStore {
    val entries = mutableListOf<AgentActionHistoryEntity>()
    override suspend fun append(taskId: String, projectId: String, toolName: String, summary: String, status: String) {
        entries.add(
            AgentActionHistoryEntity(
                id = "h${entries.size}",
                taskId = taskId,
                projectId = projectId,
                toolName = toolName,
                actionSummary = summary,
                status = status,
                createdAt = 0L
            )
        )
    }

    override suspend fun getForTask(taskId: String) = entries.filter { it.taskId == taskId }
    override suspend fun getRecent(limit: Int) = entries.takeLast(limit)
}

class FakeAgentPermissionStore : AgentPermissionStore {
    val granted = mutableMapOf<String, MutableSet<String>>()
    override suspend fun grant(taskId: String, toolName: String) {
        granted.getOrPut(taskId) { mutableSetOf() }.add(toolName)
    }

    override suspend fun taskScopedTools(taskId: String) = granted[taskId]?.toSet() ?: emptySet<String>()
    override suspend fun clearTask(taskId: String) {
        granted.remove(taskId)
    }

    override suspend fun clearAll() {
        granted.clear()
    }
}

// ---- tools ----

/** Configurable tool used to verify executor/permission behavior without touching the disk. */
class FakeTool(
    val name: String,
    override val definition: ToolDefinition = ToolDefinition(
        name = name,
        description = "test tool",
        parameters = listOf(AIToolParameter("path", AIToolParameterType.STRING, "path")),
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW
    ),
    private val result: (JsonObject) -> ToolResult = { ToolResult.Success(name, "ok") }
) : Tool {
    var executeCount = 0
        private set
    var lastArgs: JsonObject? = null
        private set

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        executeCount++
        lastArgs = args
        return result(args)
    }

    override fun summarize(args: JsonObject) = "$name ${(args["path"])?.toString()?.trim('"') ?: ""}".trim()
}

/** Records commands instead of spawning processes. */
class FakeCommandRunner(
    var label: String = "fake runner",
    private val behavior: (String) -> CommandRunResult = { command ->
        CommandRunResult(
            exitCode = 0,
            output = "ran: $command",
            timedOut = false,
            cancelled = false,
            truncated = false,
            durationMs = 1L
        )
    }
) : CommandRunner {
    val commands = mutableListOf<String>()
    val terminatedTasks = mutableListOf<String>()
    var lastTimeoutMs = 0L
        private set
    var lastTaskId: String? = null
        private set
    override val environmentLabel: String get() = label

    override suspend fun run(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputChars: Int,
        taskId: String,
        isCancelled: () -> Boolean
    ): CommandRunResult {
        commands.add(command)
        lastTimeoutMs = timeoutMs
        lastTaskId = taskId
        return behavior(command)
    }

    override fun terminate(taskId: String): Int {
        terminatedTasks.add(taskId)
        return 1
    }
}

class FakeEditorBridge : EditorBridge {
    private val _current = MutableStateFlow<String?>(null)
    override val currentFilePath: StateFlow<String?> = _current.asStateFlow()
    private val _open = MutableStateFlow<List<String>>(emptyList())
    override val openFilePaths: StateFlow<List<String>> = _open.asStateFlow()
    private val _requests = MutableSharedFlow<EditorOpenRequest>(extraBufferCapacity = 8)
    override val openRequests: SharedFlow<EditorOpenRequest> = _requests.asSharedFlow()

    override fun reportState(currentFilePath: String?, openFiles: List<String>) {
        _current.value = currentFilePath
        _open.value = openFiles
    }

    override fun requestOpenFile(projectRoot: String, filePath: String) {
        _requests.tryEmit(EditorOpenRequest(projectRoot, filePath))
    }
}

val emptyProcessRegistry: AgentProcessRegistry
    get() = AgentProcessRegistry()

// ---- providers ----

sealed class ProviderTurn {
    data class Text(val text: String) : ProviderTurn()
    data class Tools(val text: String, val calls: List<AIToolCall>) : ProviderTurn()
    data class Failure(val error: AIError) : ProviderTurn()
}

/** Scripted provider: each `generate` call plays the next turn. */
class ScriptedProvider(
    private val turns: List<ProviderTurn>,
    override val providerId: String = "scripted",
    override val displayName: String = "Scripted Provider",
    override val capabilities: AIProviderCapabilities = AIProviderCapabilities(
        chat = true,
        streaming = true,
        toolCalling = true,
        modelListing = true,
        usageReporting = true
    )
) : AIProvider {

    var callCount = 0
        private set
    val requests = mutableListOf<AIRequest>()

    override suspend fun getModels(): Result<List<AIModel>> =
        Result.success(listOf(AIModel(providerId, "scripted-model", "Scripted", capabilities = capabilities)))

    override suspend fun testConnection(): Result<ProviderHealth> =
        Result.success(ProviderHealth(providerId, true, true, "ok"))

    override fun generate(request: AIRequest): Flow<AIResponseEvent> = kotlinx.coroutines.flow.flow {
        requests.add(request)
        val turn = turns.getOrNull(callCount) ?: ProviderTurn.Text("done")
        callCount++
        emit(AIResponseEvent.Started(providerId, request.modelId))
        when (turn) {
            is ProviderTurn.Text -> emit(AIResponseEvent.TextDelta(turn.text))
            is ProviderTurn.Tools -> {
                if (turn.text.isNotEmpty()) emit(AIResponseEvent.TextDelta(turn.text))
                emit(AIResponseEvent.ToolCallRequested(turn.calls))
            }
            is ProviderTurn.Failure -> {
                emit(AIResponseEvent.Error(turn.error))
                return@flow
            }
        }
        emit(AIResponseEvent.Usage(AIUsage(1, 1, 2)))
        emit(AIResponseEvent.Completed("msg"))
    }
}

/** Registry/manager stub for agent tests. */
class FakeProviderManager(
    private val provider: AIProvider?,
    private val modelId: String = "scripted-model",
    private val modelToolCalling: Boolean = true
) : com.devstation.android.core.ai.AIProviderManager {
    override val providers: List<AIProvider> = listOfNotNull(provider)
    override fun getProvider(providerId: String): AIProvider? = provider?.takeIf { it.providerId == providerId }
    override fun registerProvider(provider: AIProvider) = Unit
    override suspend fun resolveModel(providerId: String, modelId: String?): Result<AIModel> {
        val target = provider ?: return Result.failure(AIError.ProviderUnavailableError("not registered"))
        return Result.success(
            AIModel(
                providerId = providerId,
                modelId = modelId ?: this.modelId,
                displayName = "Scripted",
                capabilities = target.capabilities.copy(toolCalling = modelToolCalling),
                contextWindow = 32_000
            )
        )
    }
}

// ---- conversation / project ports ----

class FakeConversationPort(
    private val history: List<AIMessage> = emptyList(),
    private val selection: Pair<String?, String?> = null to null
) : AgentConversationPort {
    val persisted = mutableListOf<String>()
    override suspend fun history(conversationId: String) = history
    override suspend fun providerSelection(conversationId: String) = selection
    override suspend fun persistAssistant(conversationId: String, content: String) {
        persisted.add(content)
    }
}

fun fakeProject(root: File, id: String = "p1", name: String = "demo") = Project(
    id = id,
    name = name,
    localPath = root.absolutePath,
    createdAt = 0L,
    updatedAt = 0L
)
