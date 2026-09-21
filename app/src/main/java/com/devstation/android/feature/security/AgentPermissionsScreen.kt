package com.devstation.android.feature.security

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.agent.AgentRuntime
import com.devstation.android.core.model.Project
import com.devstation.android.core.repository.ProjectRepository
import com.devstation.android.core.security.policy.AgentSecurityMode
import com.devstation.android.core.security.policy.CategoryPolicy
import com.devstation.android.core.security.policy.PermissionCategory
import com.devstation.android.core.security.policy.ProjectSecuritySettings
import com.devstation.android.core.security.policy.SecurityManager
import com.devstation.android.core.security.policy.SecurityOverview
import com.devstation.android.core.security.policy.SecurityPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** §38/§39/§40/§43: everything the user needs to see and change about agent permissions. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentPermissionsViewModel(
    private val securityManager: SecurityManager,
    projectRepository: ProjectRepository,
    runtime: AgentRuntime
) : ViewModel() {

    private val _selectedProjectId = MutableStateFlow<String?>(null)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val projects: StateFlow<List<Project>> = projectRepository.getAllProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val policy: StateFlow<SecurityPolicy> = securityManager.observePolicy()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityPolicy.DEFAULT)

    val projectSettings: StateFlow<ProjectSecuritySettings?> = _selectedProjectId
        .flatMapLatest { id: String? ->
            if (id == null) flowOf<ProjectSecuritySettings?>(null)
            else flow<ProjectSecuritySettings?> { emit(securityManager.projectSettings(id)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val overview: StateFlow<SecurityOverview?> = combine(_selectedProjectId, runtime.state) { id, task ->
        id to task?.taskId
    }.mapLatest { (id, taskId) ->
        runCatching { securityManager.overview(id, taskId) }.getOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectProject(projectId: String?) {
        _selectedProjectId.value = projectId
    }

    /** §43: mode changes are confirmed in the UI before they are applied. */
    fun setMode(mode: AgentSecurityMode) {
        viewModelScope.launch {
            val impact = securityManager.setMode(mode)
            _message.value = "Security mode set to ${mode.name}." +
                if (impact.isEmpty()) "" else " " + impact.joinToString(" ")
        }
    }

    fun setCategory(category: PermissionCategory, value: CategoryPolicy) {
        viewModelScope.launch {
            securityManager.setCategory(category, value)
            _message.value = "${category.name.lowercase().replace('_', ' ')} policy set to ${value.name}."
        }
    }

    /** §39: per-project switches. The project id must be known before anything can be changed. */
    fun setProjectCategory(category: PermissionCategory, allowed: Boolean) {
        val projectId = _selectedProjectId.value ?: return
        viewModelScope.launch {
            val current = securityManager.projectSettings(projectId)
            securityManager.setProjectSettings(current.withCategory(category, allowed))
            _message.value = "Project setting updated."
        }
    }

    fun setRetention(days: Int) {
        viewModelScope.launch {
            val removed = securityManager.setRetention(days)
            _message.value = "Retention set to $days days ($removed old event(s) removed)."
        }
    }

    fun revokeTask() {
        val taskId = overview.value?.taskId ?: return
        viewModelScope.launch {
            securityManager.revokeTask(taskId)
            _message.value = "Task permissions revoked."
        }
    }

    fun revokeSession() {
        viewModelScope.launch {
            securityManager.revokeSession()
            _message.value = "Session permissions revoked."
        }
    }

    fun revokeProject() {
        val projectId = _selectedProjectId.value ?: return
        viewModelScope.launch {
            securityManager.revokeProject(projectId)
            _message.value = "Project permissions reset."
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            securityManager.resetAllPermissions()
            _message.value = "All agent permissions were reset."
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    companion object {
        fun provideFactory(
            securityManager: SecurityManager,
            projectRepository: ProjectRepository,
            runtime: AgentRuntime
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AgentPermissionsViewModel(securityManager, projectRepository, runtime) as T
        }
    }
}

private fun ProjectSecuritySettings.withCategory(category: PermissionCategory, allowed: Boolean):
    ProjectSecuritySettings = when (category) {
    PermissionCategory.FILES -> copy(allowFileModification = allowed)
    PermissionCategory.TERMINAL -> copy(allowTerminal = allowed)
    PermissionCategory.NETWORK -> copy(allowNetwork = allowed)
    PermissionCategory.PACKAGES -> copy(allowPackageInstallation = allowed)
    PermissionCategory.SENSITIVE_FILES -> copy(allowSensitiveFileAccess = allowed)
    PermissionCategory.CREDENTIALS -> this
}

@Composable
fun AgentPermissionsScreen(
    viewModel: AgentPermissionsViewModel,
    onNavigateBack: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    val policy by viewModel.policy.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val selectedProjectId by viewModel.selectedProjectId.collectAsStateWithLifecycle()
    val projectSettings by viewModel.projectSettings.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var pendingMode by remember { mutableStateOf<AgentSecurityMode?>(null) }
    var showProjectPicker by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }

    pendingMode?.let { mode ->
        val impact = SecurityPolicy.forMode(mode).impactSummary()
        AlertDialog(
            onDismissRequest = { pendingMode = null },
            title = { Text("Switch to ${mode.name} mode?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(modeDescription(mode), style = MaterialTheme.typography.bodyMedium)
                    if (impact.isNotEmpty()) {
                        Text("This will allow:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        impact.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setMode(mode)
                    pendingMode = null
                }) { Text("Change mode") }
            },
            dismissButton = { TextButton(onClick = { pendingMode = null }) { Text("Cancel") } }
        )
    }

    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text("Reset all agent permissions?") },
            text = {
                Text(
                    "Every task, session and project permission the agent was granted is removed. " +
                        "The agent will ask again for the next action."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetAll()
                    confirmResetAll = false
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmResetAll = false }) { Text("Cancel") } }
        )
    }

    if (showProjectPicker) {
        AlertDialog(
            onDismissRequest = { showProjectPicker = false },
            title = { Text("Choose a project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ProjectChoice("All projects (app-wide)", selectedProjectId == null) {
                        viewModel.selectProject(null)
                        showProjectPicker = false
                    }
                    projects.forEach { project ->
                        ProjectChoice(project.name, selectedProjectId == project.id) {
                            viewModel.selectProject(project.id)
                            showProjectPicker = false
                        }
                    }
                    if (projects.isEmpty()) {
                        Text(
                            "No projects yet. Create one to configure per-project security.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProjectPicker = false }) { Text("Done") } }
        )
    }

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
                    Text("Agent Permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Only you can change these settings",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        message?.let { text ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.dismissMessage() }) { Text("OK") }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                SectionCard("Security mode", "How much the agent may do without asking") {
                    AgentSecurityMode.entries.forEach { mode ->
                        ModeRow(
                            mode = mode,
                            selected = policy.mode == mode,
                            onClick = { if (policy.mode != mode) pendingMode = mode }
                        )
                    }
                }
            }

            item {
                SectionCard("Category policy", "Applies to every project unless a project overrides it") {
                    CategoryRow("Files", policy.files) { viewModel.setCategory(PermissionCategory.FILES, it) }
                    CategoryRow("Terminal commands", policy.terminal) {
                        viewModel.setCategory(PermissionCategory.TERMINAL, it)
                    }
                    CategoryRow("Network", policy.network) {
                        viewModel.setCategory(PermissionCategory.NETWORK, it)
                    }
                    CategoryRow("Package installs", policy.packages) {
                        viewModel.setCategory(PermissionCategory.PACKAGES, it)
                    }
                    CategoryRow("Sensitive files", policy.sensitiveFiles) {
                        viewModel.setCategory(PermissionCategory.SENSITIVE_FILES, it)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Credential storage — always denied",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "DENY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                SectionCard("Project scope", "Per-project restrictions always win over app-wide policy") {
                    OutlinedButton(
                        onClick = { showProjectPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            selectedProjectId?.let { id -> projects.firstOrNull { it.id == id }?.name }
                                ?: "All projects (app-wide)"
                        )
                    }
                    val settings = projectSettings
                    if (settings != null) {
                        Spacer(Modifier.height(6.dp))
                        ProjectToggle("Allow file modification", settings.allowFileModification) {
                            viewModel.setProjectCategory(PermissionCategory.FILES, it)
                        }
                        ProjectToggle("Allow terminal commands", settings.allowTerminal) {
                            viewModel.setProjectCategory(PermissionCategory.TERMINAL, it)
                        }
                        ProjectToggle("Allow network access", settings.allowNetwork) {
                            viewModel.setProjectCategory(PermissionCategory.NETWORK, it)
                        }
                        ProjectToggle("Allow package installation", settings.allowPackageInstallation) {
                            viewModel.setProjectCategory(PermissionCategory.PACKAGES, it)
                        }
                        ProjectToggle("Allow sensitive file access", settings.allowSensitiveFileAccess) {
                            viewModel.setProjectCategory(PermissionCategory.SENSITIVE_FILES, it)
                        }
                        TextButton(onClick = { viewModel.revokeProject() }) { Text("Reset project permissions") }
                    } else {
                        Text(
                            "Select a project to configure its security settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            item {
                val current = overview
                SectionCard("Current grants", "Temporary approvals, and when they expire") {
                    Text(
                        "Project: ${grantSummary(current?.projectGrantedTools ?: emptySet())}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Task: ${grantSummary(current?.taskGrantedTools ?: emptySet())}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Session: ${grantSummary(current?.sessionGrantedTools ?: emptySet())}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "${current?.persistedGrantCount ?: 0} stored grant(s) • " +
                            "${current?.auditEventCount ?: 0} recent security event(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.revokeTask() },
                            enabled = (current?.taskGrantedTools?.isNotEmpty() == true),
                            modifier = Modifier.weight(1f)
                        ) { Text("Revoke task", maxLines = 1) }
                        OutlinedButton(
                            onClick = { viewModel.revokeSession() },
                            enabled = (current?.sessionGrantedTools?.isNotEmpty() == true),
                            modifier = Modifier.weight(1f)
                        ) { Text("Revoke session", maxLines = 1) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { confirmResetAll = true },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Reset all agent permissions") }
                }
            }

            item {
                SectionCard("Audit retention", "Older security events are deleted automatically") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SecurityPolicy.RETENTION_OPTIONS.forEach { days ->
                            OutlinedButton(
                                onClick = { viewModel.setRetention(days) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    "$days d",
                                    fontWeight = if (policy.auditRetentionDays == days) FontWeight.Bold
                                    else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionCard("Security diagnostics", "Verify the sandbox and policy with real checks") {
                    OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Run diagnostics")
                    }
                }
            }

            item {
                Text(
                    "The agent can request permission but can never grant it, change this policy, or " +
                        "access credential storage.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProjectChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
private fun ModeRow(mode: AgentSecurityMode, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                mode.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
            )
            Text(
                modeDescription(mode),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CategoryRow(
    label: String,
    current: CategoryPolicy,
    onChange: (CategoryPolicy) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(
                current.name,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CategoryPolicy.entries.forEach { candidate ->
                val selected = current == candidate
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.clickable { onChange(candidate) }
                ) {
                    Text(
                        candidate.shortLabel(),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun CategoryPolicy.shortLabel(): String = when (this) {
    CategoryPolicy.DEFAULT -> "Tool default"
    CategoryPolicy.ALLOW -> "Allow"
    CategoryPolicy.ASK -> "Ask"
    CategoryPolicy.ALWAYS_ASK -> "Always ask"
    CategoryPolicy.DENY -> "Deny"
}

private fun modeDescription(mode: AgentSecurityMode): String = when (mode) {
    AgentSecurityMode.SAFE ->
        "Read-only by default. Everything that changes the project, reaches the network or installs " +
            "software asks every single time."
    AgentSecurityMode.BALANCED ->
        "Low-risk reads are automatic; project edits, commands and network access ask first; " +
            "destructive actions always ask."
    AgentSecurityMode.CUSTOM ->
        "You control each category yourself. Destructive actions and credential access stay limited."
}

private fun grantSummary(tools: Set<String>): String =
    if (tools.isEmpty()) "none" else tools.sorted().joinToString(", ")
