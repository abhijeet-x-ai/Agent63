package com.devstation.android.feature.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devstation.android.core.skills.SkillDefinition
import com.devstation.android.core.skills.SkillSource

/**
 * Phase 8 §25: Skills management screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    skills: List<SkillDefinition>,
    onSkillClick: (String) -> Unit,
    onCreateSkill: () -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    userMessage: String? = null,
    onDismissMessage: () -> Unit = {}
) {
    val builtinSkills = skills.filter { it.source == SkillSource.BUILTIN }
    val customSkills = skills.filter { it.source != SkillSource.BUILTIN }

    Column(modifier = modifier.fillMaxSize()) {
        if (userMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = userMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissMessage) { Text("Dismiss") }
                }
            }
        }
        TopAppBar(
            title = { Text("Skills") },
            actions = {
                IconButton(onClick = onCreateSkill) {
                    Icon(Icons.Default.Add, contentDescription = "Create Skill")
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Built-in skills section
            if (builtinSkills.isNotEmpty()) {
                item {
                    SectionHeader("Built-in Skills")
                }
                items(builtinSkills, key = { it.id }) { skill ->
                    SkillCard(skill = skill, onClick = { onSkillClick(skill.id) }, onToggleEnabled = { onToggleEnabled(skill.id, it) })
                }
            }

            // Custom skills section
            if (customSkills.isNotEmpty()) {
                item {
                    SectionHeader("My Skills")
                }
                items(customSkills, key = { it.id }) { skill ->
                    SkillCard(skill = skill, onClick = { onSkillClick(skill.id) }, onToggleEnabled = { onToggleEnabled(skill.id, it) })
                }
            }

            if (skills.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("No skills available", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = onCreateSkill) {
                                Text("Create Skill")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SkillCard(
    skill: SkillDefinition,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            skill.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = when (skill.source) {
                                SkillSource.BUILTIN -> MaterialTheme.colorScheme.primaryContainer
                                SkillSource.PROJECT -> MaterialTheme.colorScheme.tertiaryContainer
                                SkillSource.USER -> MaterialTheme.colorScheme.secondaryContainer
                            },
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                skill.source.name,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    Text(
                        skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = skill.enabled,
                    onCheckedChange = onToggleEnabled,
                    enabled = skill.source != SkillSource.BUILTIN
                )
            }

            if (skill.requiredTools.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    skill.requiredTools.take(3).forEach { tool ->
                        AssistChip(
                            onClick = {},
                            label = { Text(tool, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Outlined.Build, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        )
                    }
                    if (skill.requiredTools.size > 3) {
                        AssistChip(
                            onClick = {},
                            label = { Text("+${skill.requiredTools.size - 3} more", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            if (skill.runCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Used ${skill.runCount} time(s) • v${skill.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
