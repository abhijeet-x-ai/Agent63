package com.devstation.android.feature.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devstation.android.core.agent.AgentState
import com.devstation.android.core.agent.AgentStepStatus
import com.devstation.android.core.agent.AgentTimelineEntry
import com.devstation.android.core.agent.ToolRiskLevel

/** Explicit mode switch. Agent mode is never entered implicitly. */
@Composable
fun AgentModeSelector(
    mode: AgentMode,
    onSelect: (AgentMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ModeChip("Chat", mode == AgentMode.CHAT) { onSelect(AgentMode.CHAT) }
        ModeChip("Agent", mode == AgentMode.AGENT) { onSelect(AgentMode.AGENT) }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The agent task surface: state, timeline, approvals, stop and the final summary. */
@Composable
fun AgentPanel(
    uiState: AgentUiState,
    onStop: () -> Unit,
    onEmergencyStop: () -> Unit,
    onApproveOnce: () -> Unit,
    onApproveForTask: () -> Unit,
    onDeny: () -> Unit,
    onRetry: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!uiState.agentToolsEnabled) {
            NoticeCard(
                title = "Agent tools are disabled",
                body = "Enable them in Settings → AI Settings to let the agent act on this project. " +
                    "Chat still works normally.",
                tone = NoticeTone.WARNING
            )
        }

        uiState.notice?.let { notice ->
            NoticeCard(
                title = "Agent",
                body = notice,
                tone = NoticeTone.ERROR,
                onDismiss = onDismissNotice
            )
        }

        val task = uiState.task
        if (task == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Agent mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "The agent can inspect and modify this project and run approved development " +
                            "commands. Every file write, deletion and command needs your approval. " +
                            "It cannot reach anything outside the project.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Column
        }

        AgentTaskHeader(taskState = task.state, goal = task.goal, providerId = task.providerId, modelId = task.modelId)

        if (uiState.isRunning) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop Agent")
                }
                Button(
                    onClick = onEmergencyStop,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop All")
                }
            }
        }

        uiState.pendingApproval?.let { request ->
            ApprovalCard(
                title = request.title,
                target = request.target,
                detail = request.detail,
                risk = request.riskLevel,
                onAllowOnce = onApproveOnce,
                onAllowForTask = onApproveForTask,
                onDeny = onDeny
            )
        }

        if (task.timeline.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Timeline • ${task.toolCallCount} tool call(s) • turn ${task.iterationCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    task.timeline.takeLast(12).forEach { entry -> TimelineRow(entry) }
                }
            }
        }

        val summary = uiState.summary
        if (summary != null) {
            AgentSummaryCard(summary)
        }

        if (!uiState.isRunning && task.state == AgentState.FAILED) {
            TextButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Retry task")
            }
        }
    }
}

@Composable
private fun AgentTaskHeader(
    taskState: AgentState,
    goal: String,
    providerId: String?,
    modelId: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "AI Coding Agent",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stateLabel(taskState),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (taskState.isTerminal) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                )
            }
            Text(goal, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            Text(
                "${providerId ?: "no provider"} • ${modelId ?: "no model"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TimelineRow(entry: AgentTimelineEntry) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = statusGlyph(entry.status),
            color = statusColor(entry.status),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2
            )
            entry.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

/** The approval card. Nothing runs until the user taps one of these. */
@Composable
fun ApprovalCard(
    title: String,
    target: String,
    detail: String,
    risk: ToolRiskLevel,
    onAllowOnce: () -> Unit,
    onAllowForTask: () -> Unit,
    onDeny: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Approval required",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                target,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Risk: ${risk.name.lowercase().replaceFirstChar { it.uppercase() }}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = when (risk) {
                    ToolRiskLevel.CRITICAL, ToolRiskLevel.HIGH -> MaterialTheme.colorScheme.error
                    ToolRiskLevel.MEDIUM -> MaterialTheme.colorScheme.primary
                    ToolRiskLevel.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onAllowOnce, modifier = Modifier.weight(1f)) { Text("Allow Once", maxLines = 1) }
                Button(
                    onClick = onAllowForTask,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) { Text("For Task", maxLines = 1) }
                Button(
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) { Text("Deny", maxLines = 1) }
            }
        }
    }
}

@Composable
private fun AgentSummaryCard(summary: com.devstation.android.core.agent.AgentRunSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Completed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (summary.finalMessage.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(summary.finalMessage, style = MaterialTheme.typography.bodySmall, maxLines = 8)
            }
            SummarySection("Files changed", summary.filesChanged)
            SummarySection("Commands executed", summary.commandsExecuted)
            SummarySection("Tests run", summary.testsRun)
            SummarySection("Warnings", summary.warnings)
            SummarySection("Errors", summary.errors)
            Text(
                "${summary.iterations} turn(s) • ${summary.toolCalls} tool call(s) • ${summary.durationMs / 1000}s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SummarySection(label: String, values: List<String>) {
    if (values.isEmpty()) return
    Spacer(Modifier.height(2.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    values.take(8).forEach { value ->
        Text(
            "• $value",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2
        )
    }
}

private enum class NoticeTone { WARNING, ERROR }

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    tone: NoticeTone,
    onDismiss: (() -> Unit)? = null
) {
    val container = when (tone) {
        NoticeTone.WARNING -> MaterialTheme.colorScheme.secondaryContainer
        NoticeTone.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = container,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

internal fun stateLabel(state: AgentState): String = when (state) {
    AgentState.IDLE -> "Idle"
    AgentState.PLANNING -> "Planning"
    AgentState.WAITING_FOR_APPROVAL -> "Waiting for approval"
    AgentState.EXECUTING_TOOL -> "Running tool"
    AgentState.WAITING_FOR_MODEL -> "Waiting for model"
    AgentState.COMPLETED -> "Completed"
    AgentState.FAILED -> "Failed"
    AgentState.CANCELLED -> "Stopped"
    AgentState.PAUSED -> "Paused"
    AgentState.INTERRUPTED -> "Interrupted"
}

internal fun statusGlyph(status: AgentStepStatus): String = when (status) {
    AgentStepStatus.RUNNING -> "⏳"
    AgentStepStatus.SUCCESS -> "✓"
    AgentStepStatus.FAILED -> "✕"
    AgentStepStatus.DENIED -> "⚠"
    AgentStepStatus.CANCELLED -> "⊘"
    AgentStepStatus.PENDING -> "◌"
}

@Composable
internal fun statusColor(status: AgentStepStatus): Color = when (status) {
    AgentStepStatus.SUCCESS -> MaterialTheme.colorScheme.primary
    AgentStepStatus.FAILED -> MaterialTheme.colorScheme.error
    AgentStepStatus.DENIED -> MaterialTheme.colorScheme.tertiary
    AgentStepStatus.RUNNING -> MaterialTheme.colorScheme.secondary
    AgentStepStatus.CANCELLED, AgentStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
}
