package com.devstation.android.feature.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.agent.AgentState
import com.devstation.android.core.agent.AgentTaskHistoryRepository
import com.devstation.android.core.common.FormatUtils
import com.devstation.android.core.database.AgentActionHistoryEntity
import com.devstation.android.core.database.AgentTaskEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Recent agent tasks, so the user can audit what the agent has done (§49). */
class AgentTasksViewModel(
    private val repository: AgentTaskHistoryRepository
) : ViewModel() {

    val tasks: StateFlow<List<AgentTaskEntity>> = repository.observeRecentTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        fun provideFactory(repository: AgentTaskHistoryRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AgentTasksViewModel(repository) as T
            }
    }
}

data class AgentTaskDetailUiState(
    val task: AgentTaskEntity? = null,
    val actions: List<AgentActionHistoryEntity> = emptyList(),
    val isLoading: Boolean = true
)

class AgentTaskDetailViewModel(
    private val repository: AgentTaskHistoryRepository,
    private val taskId: String
) : ViewModel() {

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(AgentTaskDetailUiState())
    val state: StateFlow<AgentTaskDetailUiState> = _state

    init {
        viewModelScope.launch {
            val task = repository.getTask(taskId)
            val actions = repository.getActions(taskId)
            _state.value = AgentTaskDetailUiState(task = task, actions = actions, isLoading = false)
        }
    }

    companion object {
        fun provideFactory(repository: AgentTaskHistoryRepository, taskId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AgentTaskDetailViewModel(repository, taskId) as T
            }
    }
}

@Composable
fun AgentTasksScreen(
    viewModel: AgentTasksViewModel,
    onNavigateBack: () -> Unit,
    onOpenTask: (String) -> Unit
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text("Agent Tasks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "What the agent did, and when",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (tasks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    "No agent tasks yet. Start one from a conversation in Agent mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tasks, key = { it.taskId }) { task ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenTask(task.taskId) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(task.goal, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                AgentState.fromStorage(task.state).let { stateLabel(it) },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "• ${task.toolCallCount} tool call(s) • ${task.iterationCount} turn(s)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            FormatUtils.formatRelativeTime(task.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AgentTaskDetailScreen(
    viewModel: AgentTaskDetailViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val task = state.task

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.width(4.dp))
                Text("Task Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }

        if (task == null && !state.isLoading) {
            Text(
                "This task is no longer available.",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        if (task == null) return@Column

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                DetailRow("Task", task.goal)
                DetailRow("Project", task.projectId)
                DetailRow("Provider", task.providerId ?: "—")
                DetailRow("Model", task.modelId ?: "—")
                DetailRow("Status", stateLabel(AgentState.fromStorage(task.state)))
                DetailRow("Iterations", task.iterationCount.toString())
                DetailRow("Tool calls", task.toolCallCount.toString())
                DetailRow("Created", FormatUtils.formatRelativeTime(task.createdAt))
                DetailRow("Updated", FormatUtils.formatRelativeTime(task.updatedAt))
                task.errorMessage?.let { DetailRow("Error", it) }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("Action history", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }

            if (state.actions.isEmpty()) {
                item {
                    Text(
                        "No tool actions were recorded for this task.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(state.actions, key = { it.id }) { action ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                action.toolName,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                action.actionSummary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2
                            )
                            Text(
                                "${action.status} • ${FormatUtils.formatRelativeTime(action.createdAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Only approved actions appear here. Hidden model reasoning is never stored.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(90.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
