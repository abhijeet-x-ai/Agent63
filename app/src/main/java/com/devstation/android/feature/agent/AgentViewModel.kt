package com.devstation.android.feature.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.agent.AgentRunSummary
import com.devstation.android.core.agent.AgentRuntime
import com.devstation.android.core.agent.AgentStartResult
import com.devstation.android.core.agent.AgentState
import com.devstation.android.core.agent.AgentTaskRequest
import com.devstation.android.core.agent.AgentTaskSnapshot
import com.devstation.android.core.agent.ApprovalDecision
import com.devstation.android.core.agent.ApprovalRequest
import com.devstation.android.core.agent.tools.EditorBridge
import com.devstation.android.core.agent.tools.EditorOpenRequest
import com.devstation.android.core.repository.AISettingsRepository
import com.devstation.android.core.repository.ConversationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The conversation screen runs in one of two explicit modes. */
enum class AgentMode { CHAT, AGENT }

data class AgentUiState(
    val mode: AgentMode = AgentMode.CHAT,
    val task: AgentTaskSnapshot? = null,
    val pendingApproval: ApprovalRequest? = null,
    val agentToolsEnabled: Boolean = true,
    val isRunning: Boolean = false,
    val notice: String? = null,
    val summary: AgentRunSummary? = null,
    val hasProject: Boolean = false
)

/**
 * Bridges the UI to [AgentRuntime]. Holds no business rules of its own — the runtime owns the
 * loop, the limits and the permission checks.
 */
class AgentViewModel(
    private val runtime: AgentRuntime,
    private val conversationRepository: ConversationRepository,
    private val aiSettingsRepository: AISettingsRepository,
    private val conversationId: String?,
    editorBridge: EditorBridge
) : ViewModel() {

    /** `open_file` requests emitted by the agent; the UI navigates the real editor. */
    val openFileRequests: SharedFlow<EditorOpenRequest> = editorBridge.openRequests

    private val _mode = MutableStateFlow(AgentMode.CHAT)
    private val _notice = MutableStateFlow<String?>(null)
    private val _hasProject = MutableStateFlow(false)

    val uiState: StateFlow<AgentUiState> = combine(
        _mode,
        runtime.state,
        runtime.pendingApproval,
        aiSettingsRepository.observe(),
        combine(_notice, _hasProject) { notice, hasProject -> notice to hasProject }
    ) { mode, task, approval, settings, (notice, hasProject) ->
        AgentUiState(
            mode = mode,
            task = task,
            pendingApproval = approval,
            agentToolsEnabled = settings.agentToolsEnabled,
            isRunning = task != null && !task.state.isTerminal,
            notice = notice,
            summary = task?.summary,
            hasProject = hasProject
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AgentUiState()
    )

    init {
        viewModelScope.launch {
            _hasProject.value = resolveProjectId() != null
        }
    }

    /** Agent mode is never entered silently: the user must select it. */
    fun setMode(mode: AgentMode) {
        _mode.value = mode
        if (mode == AgentMode.AGENT && !_hasProject.value) {
            _notice.value = "Agent mode needs a project. Open this conversation from a project first."
        }
    }

    fun runGoal(goal: String) {
        if (goal.isBlank()) return
        viewModelScope.launch {
            val projectId = resolveProjectId()
            if (projectId == null) {
                _notice.value = "Agent mode needs a project. Open a project conversation and try again."
                return@launch
            }
            _notice.value = null
            when (val result = runtime.startTask(AgentTaskRequest(goal, projectId, conversationId))) {
                is AgentStartResult.Rejected -> _notice.value = result.message
                is AgentStartResult.Started -> Unit
            }
        }
    }

    fun stop() = runtime.stop()

    /** Emergency stop: cancels the loop, clears approvals and terminates agent processes. */
    fun stopAll() {
        viewModelScope.launch { runtime.stopAll() }
    }

    fun approveOnce() = runtime.submitDecision(ApprovalDecision.AllowOnce)

    fun approveForTask() = runtime.submitDecision(ApprovalDecision.AllowForTask)

    fun deny() = runtime.submitDecision(ApprovalDecision.Deny)

    fun dismissNotice() {
        _notice.value = null
    }

    /** A failed task can be retried as a fresh task — never an automatic retry of a dangerous step. */
    fun retryLastGoal(): Boolean {
        val goal = uiState.value.task?.goal ?: return false
        val state = uiState.value.task?.state ?: return false
        if (!state.isTerminal || state == AgentState.COMPLETED) return false
        runGoal(goal)
        return true
    }

    private suspend fun resolveProjectId(): String? {
        val id = conversationId ?: return null
        return conversationRepository.getConversationById(id)?.projectId
    }

    companion object {
        fun provideFactory(
            runtime: AgentRuntime,
            conversationRepository: ConversationRepository,
            aiSettingsRepository: AISettingsRepository,
            conversationId: String?,
            editorBridge: EditorBridge
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AgentViewModel(
                    runtime,
                    conversationRepository,
                    aiSettingsRepository,
                    conversationId,
                    editorBridge
                ) as T
            }
        }
    }
}
