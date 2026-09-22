package com.devstation.android.feature.skills

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devstation.android.core.skills.SkillCapability
import com.devstation.android.core.skills.SkillDefinition
import com.devstation.android.core.skills.SkillSource
import java.util.UUID

/**
 * Phase 8.1 §28–§29: Skill detail screen.
 *
 * Shows identity, instructions, required tools, and — prominently — the difference between
 * REQUESTED permissions (what the skill asks for) and EFFECTIVE permissions (what SecurityManager
 * actually permits, which is never broadened by the skill's declaration).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillDetailScreen(
    skill: SkillDefinition?,
    onNavigateBack: () -> Unit,
    onRun: (SkillDefinition) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (SkillDefinition, String) -> Unit,
    onSave: (SkillDefinition) -> Unit
) {
    var showRunDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(skill?.name ?: "Skill") },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        if (skill == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Skill not found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        message?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard("Overview") {
                DetailRow("Version", "v${skill.version}")
                DetailRow("Author", skill.author)
                DetailRow("Source", skill.source.name)
                DetailRow("Status", if (skill.enabled) "Enabled" else "Disabled")
                DetailRow("Usage", "${skill.runCount} run(s)" + (skill.lastRunAt?.let {
                    " • last " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))
                } ?: ""))
                if (skill.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(skill.description, style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionCard("Instructions") {
                Text(
                    skill.instructions,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard("Required Tools") {
                if (skill.requiredTools.isEmpty()) {
                    Text("None", style = MaterialTheme.typography.bodySmall)
                } else {
                    skill.requiredTools.forEach { tool ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Icon(Icons.Outlined.Build, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tool, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            SectionCard("Permissions") {
                Text(
                    "REQUESTED (by the skill)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary
                )
                if (skill.requestedCapabilities.isEmpty()) {
                    Text("No capabilities requested.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        skill.requestedCapabilities.joinToString { it.name },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "EFFECTIVE (enforced by SecurityManager)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                BulletLine("Every tool call the skill triggers is individually evaluated at execution time.")
                BulletLine("Requested capabilities are not automatically granted.")
                BulletLine("Writes, terminal commands, network access and sensitive files still require approval per policy.")
                Text(
                    "Requested permissions are not automatically granted.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard("Security") {
                DetailRow("Security mode", "Follows the app-wide mode (SAFE / BALANCED / CUSTOM)")
                BulletLine("Skills cannot modify security policy, grant permissions, or disable the sandbox.")
                BulletLine("Skill execution is audited in Security Activity.")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                Button(onClick = { showRunDialog = true }) { Text("Run") }
                OutlinedButton(onClick = { onToggleEnabled(skill.id, !skill.enabled) }) {
                    Text(if (skill.enabled) "Disable" else "Enable")
                }
                if (skill.source == SkillSource.USER || skill.source == SkillSource.PROJECT) {
                    OutlinedButton(onClick = { showEditDialog = true }) { Text("Edit") }
                    OutlinedButton(onClick = { showDuplicateDialog = true }) { Text("Duplicate") }
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete") }
                }
            }
        }
    }

    val currentSkill = skill
    if (showRunDialog) {
        var goal by remember { mutableStateOf("") }
        var projectId by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRunDialog = false },
            title = { Text("Run '${currentSkill?.name ?: ""}'") },
            text = {
                Column {
                    OutlinedTextField(
                        value = projectId,
                        onValueChange = { projectId = it },
                        label = { Text("Project ID") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it },
                        label = { Text("Task goal for this run") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val s = currentSkill ?: return@TextButton
                    if (projectId.isBlank() || goal.isBlank()) return@TextButton
                    onRun(s.copy(enabled = s.enabled))
                    showRunDialog = false
                }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { showRunDialog = false }) { Text("Cancel") } }
        )
    }

    if (showDuplicateDialog && skill != null) {
        var newName by remember { mutableStateOf("${skill.name} (copy)") }
        AlertDialog(
            onDismissRequest = { showDuplicateDialog = false },
            title = { Text("Duplicate skill") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("New name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isBlank()) return@TextButton
                    onDuplicate(skill, newName.trim())
                    showDuplicateDialog = false
                }) { Text("Duplicate") }
            },
            dismissButton = { TextButton(onClick = { showDuplicateDialog = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete skill?") },
            text = { Text("This removes the skill definition. Built-in skills cannot be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    skill?.let { onDelete(it.id) }
                    showDeleteConfirm = false
                    onNavigateBack()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showEditDialog && skill != null) {
        SkillEditDialog(
            existing = skill,
            onDismiss = { showEditDialog = false },
            onSave = { edited ->
                onSave(edited)
                showEditDialog = false
            }
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(130.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BulletLine(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("• ", style = MaterialTheme.typography.bodySmall)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Phase 8.1 §29: skill editor. The SkillManager validator re-checks everything on save —
 * security-bypass declarations, unknown capabilities and invalid tool names are rejected there.
 */
@Composable
fun SkillEditDialog(
    existing: SkillDefinition,
    onDismiss: () -> Unit,
    onSave: (SkillDefinition) -> Unit
) {
    var name by remember { mutableStateOf(existing.name) }
    var description by remember { mutableStateOf(existing.description) }
    var version by remember { mutableStateOf(existing.version) }
    var instructions by remember { mutableStateOf(existing.instructions) }
    var tools by remember { mutableStateOf(existing.requiredTools.joinToString(", ")) }
    var capabilities by remember {
        mutableStateOf(existing.requestedCapabilities.toSet())
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Skill") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") })
                OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("Version (e.g. 1.0.0)") }, singleLine = true)
                OutlinedTextField(value = instructions, onValueChange = { instructions = it }, label = { Text("Instructions") })
                OutlinedTextField(
                    value = tools,
                    onValueChange = { tools = it },
                    label = { Text("Required tools (comma-separated)") }
                )
                Spacer(Modifier.height(8.dp))
                Text("Requested capabilities (requests only — not grants)", style = MaterialTheme.typography.labelMedium)
                SkillCapability.entries.forEach { capability ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = capability in capabilities,
                            onCheckedChange = { checked ->
                                capabilities = if (checked) capabilities + capability else capabilities - capability
                            }
                        )
                        Text(capability.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) { error = "A name is required."; return@TextButton }
                if (!version.matches(Regex("^\\d+\\.\\d+\\.\\d+$"))) { error = "Version must be semver (1.2.3)."; return@TextButton }
                if (instructions.isBlank()) { error = "Instructions are required."; return@TextButton }
                onSave(
                    existing.copy(
                        name = name.trim(),
                        description = description.trim(),
                        version = version.trim(),
                        instructions = instructions.trim(),
                        requiredTools = tools.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        requestedCapabilities = capabilities.toList(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
