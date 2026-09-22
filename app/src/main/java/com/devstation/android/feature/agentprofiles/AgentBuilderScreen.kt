package com.devstation.android.feature.agentprofiles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devstation.android.core.agent.profiles.AgentProfile
import com.devstation.android.core.agent.profiles.AgentProfileFileAccess
import com.devstation.android.core.agent.profiles.AgentProfilePermissions
import com.devstation.android.core.agent.profiles.AgentProfileSecurityScope
import java.util.UUID

/**
 * Phase 8.1 §30–§32: Agent Builder screen.
 *
 * Sections: Identity, Instructions, Provider/Model, Tools, Skills, MCP, Security, Limits.
 * Selecting capabilities never grants them — the effective security summary makes clear that
 * SecurityManager still evaluates every tool call at execution time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentBuilderScreen(
    profile: AgentProfile?,
    isNew: Boolean,
    availableTools: List<String>,
    availableSkills: List<Pair<String, String>>,
    availableMcpServers: List<Pair<String, String>>,
    onNavigateBack: () -> Unit,
    onSave: (AgentProfile) -> Unit
) {
    var name by remember(profile?.id) { mutableStateOf(profile?.name ?: "") }
    var description by remember(profile?.id) { mutableStateOf(profile?.description ?: "") }
    var instructions by remember(profile?.id) { mutableStateOf(profile?.systemInstructions ?: "") }
    var providerId by remember(profile?.id) { mutableStateOf(profile?.providerId ?: "") }
    var modelId by remember(profile?.id) { mutableStateOf(profile?.modelId ?: "") }
    var enabledTools by remember(profile?.id) { mutableStateOf(profile?.enabledTools?.toSet() ?: emptySet()) }
    var enabledSkills by remember(profile?.id) { mutableStateOf(profile?.enabledSkills?.toSet() ?: emptySet()) }
    var enabledMcp by remember(profile?.id) { mutableStateOf(profile?.enabledMcpServers?.toSet() ?: emptySet()) }
    var securityScope by remember(profile?.id) { mutableStateOf(profile?.securityScope ?: AgentProfileSecurityScope.BALANCED) }
    var fileAccess by remember(profile?.id) { mutableStateOf(profile?.permissionProfile?.fileAccess ?: AgentProfileFileAccess.READ_ONLY) }
    var terminalAccess by remember(profile?.id) { mutableStateOf(profile?.permissionProfile?.terminalAccess ?: false) }
    var networkAccess by remember(profile?.id) { mutableStateOf(profile?.permissionProfile?.networkAccess ?: false) }
    var packageAccess by remember(profile?.id) { mutableStateOf(profile?.permissionProfile?.packageAccess ?: false) }
    var maxIterations by remember(profile?.id) { mutableStateOf((profile?.maxIterations ?: 25).toString()) }
    var maxToolCalls by remember(profile?.id) { mutableStateOf((profile?.maxToolCalls ?: 50).toString()) }
    var maxDuration by remember(profile?.id) { mutableStateOf(((profile?.maxTaskDurationMs ?: 600_000L) / 1000).toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (isNew) "New Agent" else "Agent Builder") },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BuilderSection("Identity") {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
            }

            BuilderSection("Instructions") {
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("System instructions") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }

            BuilderSection("AI Model") {
                OutlinedTextField(value = providerId, onValueChange = { providerId = it }, label = { Text("Provider ID (blank = conversation default)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = modelId, onValueChange = { modelId = it }, label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            BuilderSection("Tools") {
                if (availableTools.isEmpty()) {
                    Text("Built-in tools are used when nothing is selected.", style = MaterialTheme.typography.bodySmall)
                }
                availableTools.forEach { tool ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = tool in enabledTools,
                            onCheckedChange = { checked ->
                                enabledTools = if (checked) enabledTools + tool else enabledTools - tool
                            }
                        )
                        Text(tool, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "Empty selection = all built-in tools.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            BuilderSection("Skills") {
                if (availableSkills.isEmpty()) Text("No skills available.", style = MaterialTheme.typography.bodySmall)
                availableSkills.forEach { (id, skillName) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = id in enabledSkills,
                            onCheckedChange = { checked ->
                                enabledSkills = if (checked) enabledSkills + id else enabledSkills - id
                            }
                        )
                        Text(skillName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            BuilderSection("MCP Servers") {
                if (availableMcpServers.isEmpty()) Text("No MCP servers configured.", style = MaterialTheme.typography.bodySmall)
                availableMcpServers.forEach { (id, serverName) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = id in enabledMcp,
                            onCheckedChange = { checked ->
                                enabledMcp = if (checked) enabledMcp + id else enabledMcp - id
                            }
                        )
                        Text(serverName, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "MCP tool calls still require security approval at execution time.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            BuilderSection("Security") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentProfileSecurityScope.entries.forEach { scope ->
                        FilterChip(
                            selected = securityScope == scope,
                            onClick = { securityScope = scope },
                            label = { Text(scope.name) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("File access", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
                    FilterChip(
                        selected = fileAccess == AgentProfileFileAccess.READ_ONLY,
                        onClick = { fileAccess = AgentProfileFileAccess.READ_ONLY },
                        label = { Text("Read only") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = fileAccess == AgentProfileFileAccess.READ_WRITE,
                        onClick = { fileAccess = AgentProfileFileAccess.READ_WRITE },
                        label = { Text("Read/Write") }
                    )
                }
                PermissionRequestRow("Terminal access", terminalAccess) { terminalAccess = it }
                PermissionRequestRow("Network access", networkAccess) { networkAccess = it }
                PermissionRequestRow("Package management", packageAccess) { packageAccess = it }
                Text(
                    "These are requests, not grants. Every tool call is still evaluated by the " +
                        "SecurityManager; destructive actions always require approval.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            EffectiveSecuritySummary(
                securityScope = securityScope,
                toolCount = enabledTools.size,
                skillCount = enabledSkills.size,
                mcpCount = enabledMcp.size,
                fileAccess = fileAccess,
                terminalAccess = terminalAccess,
                networkAccess = networkAccess,
                packageAccess = packageAccess
            )

            BuilderSection("Limits") {
                OutlinedTextField(value = maxIterations, onValueChange = { maxIterations = it }, label = { Text("Max iterations") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = maxToolCalls, onValueChange = { maxToolCalls = it }, label = { Text("Max tool calls") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = maxDuration, onValueChange = { maxDuration = it }, label = { Text("Max task duration (seconds)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = {
                    val iterations = maxIterations.toIntOrNull()
                    val toolCalls = maxToolCalls.toIntOrNull()
                    val durationSec = maxDuration.toLongOrNull()
                    if (name.isBlank()) { error = "A name is required."; return@Button }
                    if (iterations == null || iterations !in 1..500) { error = "Max iterations must be 1–500."; return@Button }
                    if (toolCalls == null || toolCalls !in 1..1000) { error = "Max tool calls must be 1–1000."; return@Button }
                    if (durationSec == null || durationSec !in 30..7200) { error = "Duration must be 30–7200 seconds."; return@Button }
                    error = null
                    onSave(
                        AgentProfile(
                            id = profile?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            description = description.trim(),
                            systemInstructions = instructions.trim(),
                            providerId = providerId.trim().ifBlank { null },
                            modelId = modelId.trim().ifBlank { null },
                            enabledTools = enabledTools.toList(),
                            enabledSkills = enabledSkills.toList(),
                            enabledMcpServers = enabledMcp.toList(),
                            permissionProfile = AgentProfilePermissions(
                                fileAccess = fileAccess,
                                terminalAccess = terminalAccess,
                                networkAccess = networkAccess,
                                packageAccess = packageAccess
                            ),
                            securityScope = securityScope,
                            projectScope = profile?.projectScope,
                            maxIterations = iterations,
                            maxToolCalls = toolCalls,
                            maxTaskDurationMs = durationSec * 1000,
                            createdAt = profile?.createdAt ?: System.currentTimeMillis()
                        )
                    )
                }) { Text("Save Agent") }
            }
        }
    }
}

@Composable
private fun PermissionRequestRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** Phase 8.1 §32: informational-only effective security summary. */
@Composable
private fun EffectiveSecuritySummary(
    securityScope: AgentProfileSecurityScope,
    toolCount: Int,
    skillCount: Int,
    mcpCount: Int,
    fileAccess: AgentProfileFileAccess,
    terminalAccess: Boolean,
    networkAccess: Boolean,
    packageAccess: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Effective Security (informational)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            SummaryLine("Security mode", securityScope.name)
            SummaryLine("Tools", if (toolCount == 0) "All built-in" else "$toolCount selected")
            SummaryLine("Skills", "$skillCount enabled")
            SummaryLine("MCP servers", "$mcpCount enabled")
            SummaryLine("Project writes", if (fileAccess == AgentProfileFileAccess.READ_WRITE) "Approval required" else "Read-only requests")
            SummaryLine("Terminal", if (terminalAccess) "Requested — approval per command" else "Not requested")
            SummaryLine("Network", if (networkAccess) "Requested — approval required" else "Not requested")
            SummaryLine("Packages", if (packageAccess) "Requested — always approval" else "Not requested")
            SummaryLine("System / credentials", "Always denied")
            Spacer(Modifier.height(4.dp))
            Text(
                "Selecting capabilities never grants them. SecurityManager decides at execution time.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BuilderSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
