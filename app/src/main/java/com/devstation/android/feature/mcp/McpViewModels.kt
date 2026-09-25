package com.devstation.android.feature.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.mcp.McpCapability
import com.devstation.android.core.mcp.McpCapabilityType
import com.devstation.android.core.mcp.McpServerConfig
import com.devstation.android.core.mcp.McpServerManager
import com.devstation.android.core.mcp.McpServerStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Phase 8 §14: ViewModel backing the MCP Servers screen. All mutations go through
 * McpServerManager, which validates configuration and audits every lifecycle change.
 */
class McpServersViewModel(
    private val serverManager: McpServerManager
) : ViewModel() {

    val servers: StateFlow<List<McpServerConfig>> =
        serverManager.servers.map { it.values.sortedBy { s -> s.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val statuses: StateFlow<Map<String, McpServerStatus>> =
        serverManager.statuses
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    fun dismissMessage() {
        _userMessage.value = null
    }

    fun connect(serverId: String) {
        viewModelScope.launch {
            runCatching { serverManager.connect(serverId) }
                .onFailure { _userMessage.value = "Connect failed: ${it.message}" }
        }
    }

    fun disconnect(serverId: String) {
        viewModelScope.launch {
            runCatching { serverManager.disconnect(serverId) }
                .onFailure { _userMessage.value = "Disconnect failed: ${it.message}" }
        }
    }

    fun remove(serverId: String) {
        viewModelScope.launch {
            runCatching { serverManager.removeServer(serverId) }
                .onFailure { _userMessage.value = "Remove failed: ${it.message}" }
        }
    }

    fun setEnabled(serverId: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { serverManager.setEnabled(serverId, enabled) }
                .onFailure { _userMessage.value = "Update failed: ${it.message}" }
        }
    }

    class Factory(private val serverManager: McpServerManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            McpServersViewModel(serverManager) as T
    }
}

/**
 * Phase 8.1 §25–§27: ViewModel backing the MCP Server detail screen — status, capabilities,
 * lifecycle actions, and the add/edit dialog save path. Every mutation goes through
 * McpServerManager, so configuration validation and auditing are unchanged.
 */
class McpServerDetailViewModel(
    private val serverManager: McpServerManager
) : ViewModel() {

    data class UiState(
        val config: McpServerConfig? = null,
        val status: com.devstation.android.core.mcp.McpServerStatus? = null,
        val capabilities: List<McpCapability> = emptyList(),
        val connected: Boolean = false
    )

    private val serverId = MutableStateFlow<String?>(null)
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    val uiState: StateFlow<UiState> = combine(
        serverManager.servers,
        serverManager.statuses,
        serverId
    ) { servers, statuses, id ->
        val config = id?.let { servers[it] }
        UiState(
            config = config,
            status = id?.let { statuses[it] },
            capabilities = id?.let { serverManager.capabilitiesForServer(it) } ?: emptyList(),
            connected = id?.let { serverManager.isConnected(it) } ?: false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun bind(id: String) {
        serverId.value = id
    }

    fun connect() {
        serverId.value?.let { id ->
            viewModelScope.launch {
                runCatching { serverManager.connect(id) }
                    .onFailure { _saveError.value = "Connect failed: ${it.message}" }
            }
        }
    }

    fun disconnect() {
        serverId.value?.let { id ->
            viewModelScope.launch {
                runCatching { serverManager.disconnect(id) }
                    .onFailure { _saveError.value = "Disconnect failed: ${it.message}" }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        serverId.value?.let { id ->
            viewModelScope.launch {
                runCatching { serverManager.setEnabled(id, enabled) }
                    .onFailure { _saveError.value = "Update failed: ${it.message}" }
            }
        }
    }

    fun refresh() {
        serverId.value?.let { id ->
            viewModelScope.launch {
                runCatching { serverManager.refreshCapabilities(id) }
                    .onFailure { _saveError.value = "Refresh failed: ${it.message}" }
            }
        }
    }

    fun remove(onRemoved: () -> Unit) {
        val id = serverId.value ?: return
        viewModelScope.launch {
            runCatching { serverManager.removeServer(id) }
                .onFailure { _saveError.value = "Remove failed: ${it.message}" }
                .onSuccess { onRemoved() }
        }
    }

    /** Save an edited (or new) server configuration. Reports success through [onDone]. */
    fun save(config: McpServerConfig, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = if (serverManager.servers.value.containsKey(config.id)) {
                serverManager.updateServer(config)
            } else {
                serverManager.registerServer(config)
            }
            _saveError.value = result.exceptionOrNull()?.message
            onDone(result.isSuccess)
        }
    }

    fun clearSaveError() {
        _saveError.value = null
    }

    class Factory(private val serverManager: McpServerManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            McpServerDetailViewModel(serverManager) as T
    }
}

/** Parse a user-entered argument line into argv (whitespace-separated, quote-aware). */
internal fun parseArguments(raw: String): List<String> {
    val args = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    for (ch in raw) {
        when {
            quote != null -> if (ch == quote) quote = null else current.append(ch)
            ch == '\'' || ch == '"' -> quote = ch
            ch.isWhitespace() -> {
                if (current.isNotEmpty()) { args.add(current.toString()); current.setLength(0) }
            }
            else -> current.append(ch)
        }
    }
    if (current.isNotEmpty()) args.add(current.toString())
    return args
}

/** Group capabilities for the detail screen. */
internal fun McpServerDetailViewModel.UiState.tools(): List<McpCapability> =
    capabilities.filter { it.capabilityType == McpCapabilityType.TOOL }

internal fun McpServerDetailViewModel.UiState.resources(): List<McpCapability> =
    capabilities.filter { it.capabilityType == McpCapabilityType.RESOURCE }

internal fun McpServerDetailViewModel.UiState.prompts(): List<McpCapability> =
    capabilities.filter { it.capabilityType == McpCapabilityType.PROMPT }
