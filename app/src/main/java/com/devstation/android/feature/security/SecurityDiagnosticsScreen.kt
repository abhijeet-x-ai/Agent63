package com.devstation.android.feature.security

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.agent.AgentRuntime
import com.devstation.android.core.repository.ProjectRepository
import com.devstation.android.core.security.policy.DiagnosticCheck
import com.devstation.android.core.security.policy.DiagnosticStatus
import com.devstation.android.core.security.policy.SecurityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

data class SecurityDiagnosticsUiState(
    val running: Boolean = false,
    val checks: List<DiagnosticCheck> = emptyList(),
    val scope: String? = null,
    val error: String? = null
) {
    val passed: Int get() = checks.count { it.status == DiagnosticStatus.PASS }
    val failed: Int get() = checks.count { it.status == DiagnosticStatus.FAIL }
    val warnings: Int get() = checks.count { it.status == DiagnosticStatus.WARNING }
}

/**
 * §47: runs the diagnostics against a real project root when one is available. When no project
 * exists the filesystem and engine checks report WARNING instead of pretending to pass.
 */
class SecurityDiagnosticsViewModel(
    private val securityManager: SecurityManager,
    private val projectRepository: ProjectRepository,
    private val runtime: AgentRuntime
) : ViewModel() {

    private val _state = MutableStateFlow(SecurityDiagnosticsUiState())
    val state: StateFlow<SecurityDiagnosticsUiState> = _state

    init {
        run()
    }

    fun run() {
        if (_state.value.running) return
        _state.value = _state.value.copy(running = true, error = null)
        viewModelScope.launch {
            val (root, label) = resolveScope()
            val taskId = runtime.state.value?.taskId ?: "diagnostics-no-task"
            val checks = runCatching { securityManager.runDiagnostics(root, taskId) }
                .getOrElse { error ->
                    _state.value = _state.value.copy(
                        running = false,
                        error = error.message ?: "Diagnostics could not run."
                    )
                    return@launch
                }
            _state.value = SecurityDiagnosticsUiState(
                running = false,
                checks = checks,
                scope = label
            )
        }
    }

    /** Prefer the project the agent is currently working on, otherwise the first known project. */
    private suspend fun resolveScope(): Pair<File?, String> {
        val activeProjectId = runtime.state.value?.projectId
        val projects = runCatching {
            projectRepository.getAllProjects().first()
        }.getOrDefault(emptyList())
        val project = projects.firstOrNull { it.id == activeProjectId } ?: projects.firstOrNull()
        if (project == null) return null to "no project available"
        val root = File(project.localPath)
        return if (root.isDirectory) root to project.name else null to "${project.name} (folder missing)"
    }

    companion object {
        fun provideFactory(
            securityManager: SecurityManager,
            projectRepository: ProjectRepository,
            runtime: AgentRuntime
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SecurityDiagnosticsViewModel(securityManager, projectRepository, runtime) as T
        }
    }
}

@Composable
fun SecurityDiagnosticsScreen(
    viewModel: SecurityDiagnosticsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
                Column(modifier = Modifier.weight(1f)) {
                    Text("Security Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Real checks against the sandbox and policy",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.running) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Running checks…", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Text(
                                "${state.passed} passed • ${state.failed} failed • ${state.warnings} not verified",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            state.scope?.let { scope ->
                                Text(
                                    "Scope: $scope",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        state.error?.let { error ->
                            Text(
                                error,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Button(onClick = { viewModel.run() }, enabled = !state.running) {
                            Text(if (state.checks.isEmpty()) "Run diagnostics" else "Run again")
                        }
                    }
                }
            }

            items(state.checks, key = { it.id }) { check -> DiagnosticRow(check) }
        }
    }
}

@Composable
private fun DiagnosticRow(check: DiagnosticCheck) {
    val (icon, accent) = when (check.status) {
        DiagnosticStatus.PASS -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        DiagnosticStatus.FAIL -> Icons.Default.Error to MaterialTheme.colorScheme.error
        DiagnosticStatus.WARNING -> Icons.Default.Warning to Color(0xFFB58900)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = check.status.name, modifier = Modifier.size(16.dp), tint = accent)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(check.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    check.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                check.status.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }
    }
}
