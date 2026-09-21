package com.devstation.android.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devstation.android.core.common.FormatUtils
import com.devstation.android.core.model.Message
import com.devstation.android.core.model.MessageRole
import com.devstation.android.feature.agent.AgentMode
import com.devstation.android.feature.agent.AgentModeSelector
import com.devstation.android.feature.agent.AgentPanel
import com.devstation.android.core.agent.tools.EditorOpenRequest
import com.devstation.android.feature.agent.AgentViewModel
import kotlinx.coroutines.launch

/**
 * AI conversation screen.
 *
 * Two explicit modes (Phase 6 §40): **Chat** (plain model conversation, no tools) and **Agent**
 * (the agent may inspect and modify the project through approved tools). The user must select
 * Agent mode deliberately — it is never entered implicitly.
 */
@Composable
fun AiChatScreen(
    viewModel: AiChatViewModel,
    agentViewModel: AgentViewModel? = null,
    onNavigateBack: () -> Unit = {},
    onOpenFile: (EditorOpenRequest) -> Unit = {},
    onOpenAgentTasks: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val agentState = agentViewModel?.uiState?.collectAsState()?.value
    val agentMode = agentState?.mode ?: AgentMode.CHAT
    var inputMessage by remember { mutableStateOf("") }
    var showProviderMenu by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val displayMessages = remember(uiState.messages, uiState.streamingText) {
        uiState.messages
    }

    LaunchedEffect(uiState.messages.size, uiState.streamingText) {
        val target = displayMessages.size - 1
        if (target >= 0) listState.animateScrollToItem(target)
    }

    // The agent may ask DevStation to open a file; the UI performs the navigation.
    LaunchedEffect(agentViewModel) {
        agentViewModel?.openFileRequests?.collect { request -> onOpenFile(request) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar with provider/model chips
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column {
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
                        Text(
                            uiState.conversation?.title ?: "AI Chat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = when {
                                uiState.isStreaming -> "Streaming…"
                                uiState.providerId != null && uiState.modelId != null ->
                                    "${uiState.providerId} • ${uiState.modelId}"
                                uiState.providerId != null -> "${uiState.providerId} • no model selected"
                                else -> "No AI provider configured"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (uiState.isStreaming) {
                        IconButton(onClick = { viewModel.cancelStreaming() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (agentViewModel != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AgentModeSelector(
                            mode = agentMode,
                            onSelect = { agentViewModel.setMode(it) }
                        )
                        TextButton(onClick = onOpenAgentTasks) { Text("Task history") }
                    }
                }
                // Provider + model selector chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { showProviderMenu = true }
                    ) {
                        Text(
                            "Provider: ${uiState.providerId ?: "none"}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    DropdownMenu(expanded = showProviderMenu, onDismissRequest = { showProviderMenu = false }) {
                        uiState.availableProviders.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    showProviderMenu = false
                                    viewModel.selectProviderAndModel(id, null)
                                }
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { showModelMenu = true }
                    ) {
                        Text(
                            "Model: ${uiState.modelId ?: "none"}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                        if (uiState.availableModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No cached models — refresh in AI Providers") },
                                onClick = { showModelMenu = false }
                            )
                        }
                        uiState.availableModels.forEach { modelId ->
                            DropdownMenuItem(
                                text = { Text(modelId) },
                                onClick = {
                                    showModelMenu = false
                                    uiState.providerId?.let { p ->
                                        viewModel.selectProviderAndModel(p, modelId)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        if (agentMode == AgentMode.AGENT && agentViewModel != null && agentState != null) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                AgentPanel(
                    uiState = agentState,
                    onStop = { agentViewModel.stop() },
                    onEmergencyStop = { agentViewModel.stopAll() },
                    onApproveOnce = { agentViewModel.approveOnce() },
                    onApproveForTask = { agentViewModel.approveForTask() },
                    onApproveForSession = { agentViewModel.approveForSession() },
                    onDeny = { agentViewModel.deny() },
                    onRetry = { agentViewModel.retryLastGoal() },
                    onDismissNotice = { agentViewModel.dismissNotice() }
                )
            }
        } else {
        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (displayMessages.isEmpty() && uiState.streamingText == null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (uiState.providerId == null) {
                                "Configure an AI provider in AI Providers, then set it as default in AI Settings."
                            } else {
                                "Send a message to test the connection."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(displayMessages, key = { it.id }) { message ->
                ChatBubble(
                    message = message,
                    onCopy = {
                        clipboard.setText(AnnotatedString(message.content))
                    }
                )
            }

            // Live streaming bubble (progressively growing; not duplicated)
            uiState.streamingText?.let { partial ->
                if (partial.isNotEmpty() || uiState.isStreaming) {
                    item(key = "streaming") {
                        StreamingBubble(text = partial)
                    }
                }
            }

            if (uiState.errorMessage != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                uiState.errorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.retryLast() }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            if (uiState.usageLine != null) {
                item {
                    Text(
                        "${uiState.usageLine} • cost unavailable (no verified pricing)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
        }

        // Input row
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputMessage,
                    onValueChange = { inputMessage = it },
                    placeholder = {
                        Text(
                            when {
                                agentMode == AgentMode.AGENT -> "Describe a task for the agent…"
                                uiState.isStreaming -> "Waiting for response…"
                                else -> "Message the AI…"
                            }
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    maxLines = 4,
                    enabled = !uiState.isStreaming && !(agentState?.isRunning ?: false),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
                Spacer(Modifier.width(8.dp))
                if (agentMode == AgentMode.AGENT && (agentState?.isRunning ?: false)) {
                    Button(
                        onClick = { agentViewModel?.stop() },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.size(44.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop agent", modifier = Modifier.size(18.dp))
                    }
                } else if (uiState.isStreaming) {
                    Button(
                        onClick = { viewModel.cancelStreaming() },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.size(44.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Cancel request", modifier = Modifier.size(18.dp))
                    }
                } else {
                    Button(
                        onClick = {
                            val text = inputMessage
                            if (text.isNotBlank()) {
                                if (agentMode == AgentMode.AGENT && agentViewModel != null) {
                                    agentViewModel.runGoal(text)
                                } else {
                                    viewModel.sendMessage(text)
                                }
                                inputMessage = ""
                            }
                        },
                        shape = CircleShape,
                        modifier = Modifier.size(44.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: Message, onCopy: () -> Unit) {
    val isUser = message.role == MessageRole.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isUser) 12.dp else 2.dp,
                bottomEnd = if (isUser) 2.dp else 12.dp
            ),
            color = when {
                isUser -> MaterialTheme.colorScheme.primaryContainer
                message.errorState != null -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isUser) "You" else "AI",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    if (!isUser) {
                        IconButton(onClick = onCopy, modifier = Modifier.size(18.dp)) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy response",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = FormatUtils.formatRelativeTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 2.dp, bottomEnd = 12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "AI",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = text.ifEmpty { "…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
