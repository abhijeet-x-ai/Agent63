package com.devstation.android.feature.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.mcp.McpServerConfig
import com.devstation.android.core.mcp.McpServerManager
import com.devstation.android.core.mcp.McpServerStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    fun connect(serverId: String) {
        viewModelScope.launch { runCatching { serverManager.connect(serverId) } }
    }

    fun disconnect(serverId: String) {
        viewModelScope.launch { serverManager.disconnect(serverId) }
    }

    fun remove(serverId: String) {
        viewModelScope.launch { runCatching { serverManager.removeServer(serverId) } }
    }

    fun setEnabled(serverId: String, enabled: Boolean) {
        viewModelScope.launch { runCatching { serverManager.setEnabled(serverId, enabled) } }
    }

    class Factory(private val serverManager: McpServerManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            McpServersViewModel(serverManager) as T
    }
}
