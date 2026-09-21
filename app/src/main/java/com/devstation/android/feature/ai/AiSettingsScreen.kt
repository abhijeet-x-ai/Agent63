package com.devstation.android.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    viewModel: AiSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var defaultModelInput by remember { mutableStateOf(uiState.settings.defaultModelId ?: "") }

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
                    Text("AI Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Defaults & network behavior",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Defaults
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Defaults", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (uiState.configuredProviders.isEmpty()) {
                        Text(
                            "No providers are configured and enabled yet. Configure one in AI Providers first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text("Default provider", style = MaterialTheme.typography.labelSmall)
                        uiState.configuredProviders.forEach { providerId ->
                            val selected = uiState.settings.defaultProviderId == providerId
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setDefaultProvider(providerId) }
                            ) {
                                Text(
                                    providerId,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = defaultModelInput,
                        onValueChange = {
                            defaultModelInput = it
                            viewModel.setDefaultModel(it.trim().ifEmpty { null })
                        },
                        label = { Text("Default model id") },
                        supportingText = { Text("Example: gpt-4o-mini, gemini-2.0-flash, claude-sonnet-4-5. Refresh models in the provider screen for the exact list.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Chat behavior
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Chat behavior", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    ToggleRow("Streaming responses", uiState.settings.streamingEnabled) { viewModel.setStreamingEnabled(it) }
                    ToggleRow("Show token usage", uiState.settings.showUsage) { viewModel.setShowUsage(it) }
                    ToggleRow("Show estimated cost", uiState.settings.showEstimatedCost) { viewModel.setShowEstimatedCost(it) }
                    ToggleRow("Save failed requests", uiState.settings.saveFailedRequests) { viewModel.setSaveFailedRequests(it) }
                    Text(
                        "Estimated cost is shown only when verified model pricing is available; otherwise \"Cost unavailable\".",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Phase 6: agent tools
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Agent tools", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    ToggleRow(
                        label = "Enable agent tools",
                        checked = uiState.settings.agentToolsEnabled
                    ) { viewModel.setAgentToolsEnabled(it) }
                    Text(
                        "When disabled, the agent can still chat but cannot read, modify, or run anything.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ToggleRow(
                        label = "Allow Android shell fallback",
                        checked = uiState.settings.agentAllowAndroidShell
                    ) { viewModel.setAgentAllowAndroidShell(it) }
                    Text(
                        "Used only when the Linux runtime is not installed. Commands never run in your " +
                            "interactive terminal session.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Execution limits", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    NumberFieldRow(
                        label = "Max iterations (1-200)",
                        value = uiState.settings.agentMaxIterations.toLong()
                    ) { viewModel.setAgentMaxIterations(it.toInt()) }
                    NumberFieldRow(
                        label = "Max tool calls (1-500)",
                        value = uiState.settings.agentMaxToolCalls.toLong()
                    ) { viewModel.setAgentMaxToolCalls(it.toInt()) }
                    NumberFieldRow(
                        label = "Max task seconds",
                        value = uiState.settings.agentMaxTaskSeconds
                    ) { viewModel.setAgentMaxTaskSeconds(it) }
                    NumberFieldRow(
                        label = "Max tool output chars",
                        value = uiState.settings.agentMaxToolOutputChars.toLong()
                    ) { viewModel.setAgentMaxToolOutputChars(it.toInt()) }
                }
            }

            // Network
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Network", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    NumberFieldRow(
                        label = "Connect timeout (s)",
                        value = uiState.settings.connectTimeoutSeconds
                    ) { viewModel.setConnectTimeout(it) }
                    NumberFieldRow(
                        label = "Read timeout (s)",
                        value = uiState.settings.readTimeoutSeconds
                    ) { viewModel.setReadTimeout(it) }
                    NumberFieldRow(
                        label = "Retry count (max 5)",
                        value = uiState.settings.retryCount.toLong()
                    ) { viewModel.setRetryCount(it.toInt()) }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberFieldRow(label: String, value: Long, onChange: (Long) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input.filter { it.isDigit() }.take(4)
                text.toLongOrNull()?.let(onChange)
            },
            singleLine = true,
            modifier = Modifier.width(110.dp)
        )
    }
}
