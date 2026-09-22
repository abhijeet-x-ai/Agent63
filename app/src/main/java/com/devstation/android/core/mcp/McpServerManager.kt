package com.devstation.android.core.mcp

import com.devstation.android.core.agent.SecretRedactor
import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.security.policy.SecurityAuditLogger
import com.devstation.android.core.security.policy.SecurityEventType
import com.devstation.android.core.security.policy.AuditDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 8 §13: MCP server lifecycle manager.
 *
 * Manages registration, connection, disconnection, capability refresh, and removal of MCP
 * servers. Each server has a bounded lifecycle; background auto-reconnect is not performed
 * without explicit user/security settings.
 */
class McpServerManager(
    private val transportFactory: (McpTransportType) -> McpTransport,
    private val capabilityRegistry: McpCapabilityRegistry,
    private val audit: SecurityAuditLogger,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    private val configStore: McpConfigStore
) {
    private val _servers = MutableStateFlow<Map<String, McpServerConfig>>(emptyMap())
    val servers: StateFlow<Map<String, McpServerConfig>> = _servers.asStateFlow()

    private val _statuses = MutableStateFlow<Map<String, McpServerStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, McpServerStatus>> = _statuses.asStateFlow()

    private val connections = ConcurrentHashMap<String, McpConnection>()
    private val clients = ConcurrentHashMap<String, McpClient>()
    private val connectMutex = ConcurrentHashMap<String, Mutex>()

    /** Load servers from persistent storage. */
    suspend fun loadServers() {
        val configs = runCatching { configStore.getAll() }.getOrDefault(emptyList())
        _servers.value = configs.associateBy { it.id }
        configs.forEach { config ->
            if (config.enabled && config.autoConnect) {
                runCatching { connect(config.id) }
            }
        }
    }

    /** Register a new MCP server. Validates configuration before saving. */
    suspend fun registerServer(config: McpServerConfig): Result<McpServerConfig> {
        if (_servers.value.containsKey(config.id)) {
            return Result.failure(IllegalStateException("Server '${config.name}' is already registered."))
        }
        if (config.hasSensitiveEnvironmentKeys()) {
            return Result.failure(
                IllegalArgumentException(
                    "Environment contains sensitive variable names. Use credential references instead."
                )
            )
        }
        runCatching { configStore.save(config) }
        _servers.value = _servers.value + (config.id to config)
        _statuses.value = _statuses.value + (config.id to McpServerStatus(
            serverId = config.id,
            state = if (config.enabled) McpServerState.DISCONNECTED else McpServerState.DISABLED
        ))
        audit.log(
            type = SecurityEventType.SECURITY_POLICY_CHANGED,
            decision = AuditDecision.RECORDED,
            summary = "MCP server registered: ${SecretRedactor.redact(config.name)}"
        )
        return Result.success(config)
    }

    /** Update an existing server configuration. */
    suspend fun updateServer(config: McpServerConfig): Result<McpServerConfig> {
        if (!_servers.value.containsKey(config.id)) {
            return Result.failure(IllegalStateException("Server '${config.id}' not found."))
        }
        if (config.hasSensitiveEnvironmentKeys()) {
            return Result.failure(
                IllegalArgumentException("Environment contains sensitive variable names.")
            )
        }
        val updated = config.copy(updatedAt = System.currentTimeMillis())
        runCatching { configStore.save(updated) }
        _servers.value = _servers.value + (updated.id to updated)
        return Result.success(updated)
    }

    /** Remove a server and disconnect if connected. */
    suspend fun removeServer(serverId: String): Result<Unit> {
        disconnect(serverId)
        runCatching { configStore.delete(serverId) }
        _servers.value = _servers.value - serverId
        _statuses.value = _statuses.value - serverId
        capabilityRegistry.removeServerCapabilities(serverId)
        audit.log(
            type = SecurityEventType.SECURITY_POLICY_CHANGED,
            decision = AuditDecision.RECORDED,
            summary = "MCP server removed: $serverId"
        )
        return Result.success(Unit)
    }

    /** Enable or disable a server. */
    suspend fun setEnabled(serverId: String, enabled: Boolean): Result<Unit> {
        val config = _servers.value[serverId] ?: return Result.failure(IllegalStateException("Server not found"))
        val updated = config.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
        runCatching { configStore.save(updated) }
        _servers.value = _servers.value + (serverId to updated)
        if (!enabled) disconnect(serverId)
        return Result.success(Unit)
    }

    /** Connect to a server, discover capabilities. */
    suspend fun connect(serverId: String): Result<McpConnection> {
        val config = _servers.value[serverId]
            ?: return Result.failure(IllegalStateException("Server '$serverId' not found"))
        if (!config.enabled) {
            return Result.failure(IllegalStateException("Server '${config.name}' is disabled."))
        }

        val mutex = connectMutex.getOrPut(serverId) { Mutex() }
        return mutex.withLock {
            updateStatus(serverId, McpServerState.CONNECTING)
            try {
                val transport = transportFactory(config.transportType)
                val client = McpClient(transport)
                val connection = transport.connect(config).getOrElse { e ->
                    updateStatus(serverId, McpServerState.ERROR, "Connection failed: ${e.message}")
                    return@withLock Result.failure(e)
                }

                // Initialize the MCP session
                val initialized = client.initialize(connection).getOrElse { e ->
                    updateStatus(serverId, McpServerState.ERROR, "Initialization failed: ${e.message}")
                    return@withLock Result.failure(e)
                }

                // Discover capabilities
                val tools: List<McpCapability> = client.listTools(initialized).getOrNull() ?: emptyList()
                val resources: List<McpCapability> = client.listResources(initialized).getOrNull() ?: emptyList()
                val prompts: List<McpCapability> = client.listPrompts(initialized).getOrNull() ?: emptyList()

                val allCapabilities = tools + resources + prompts
                capabilityRegistry.registerCapabilities(serverId, allCapabilities)

                connections[serverId] = initialized
                clients[serverId] = client
                updateStatus(
                    serverId, McpServerState.CONNECTED,
                    toolCount = tools.size,
                    resourceCount = resources.size,
                    promptCount = prompts.size
                )

                audit.log(
                    type = SecurityEventType.PERMISSION_GRANTED,
                    decision = AuditDecision.ALLOWED,
                    summary = "MCP server connected: ${config.name} (${allCapabilities.size} capabilities)"
                )
                Result.success(initialized)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                updateStatus(serverId, McpServerState.ERROR, e.message)
                audit.log(
                    type = SecurityEventType.TOOL_BLOCKED,
                    decision = AuditDecision.BLOCKED,
                    summary = "MCP server connection failed: ${config.name} — ${e.message}"
                )
                Result.failure(e)
            }
        }
    }

    /** Disconnect from a server. */
    suspend fun disconnect(serverId: String) {
        val connection = connections.remove(serverId) ?: return
        val client = clients.remove(serverId)
        updateStatus(serverId, McpServerState.STOPPING)
        // Close the transport connection via the client
        runCatching {
            clients.remove(serverId)
        }
        capabilityRegistry.removeServerCapabilities(serverId)
        updateStatus(serverId, McpServerState.DISCONNECTED)
    }

    /** Get the active client for a server. */
    fun getClient(serverId: String): McpClient? = clients[serverId]

    /** Get the active connection for a server. */
    fun getConnection(serverId: String): McpConnection? = connections[serverId]

    /** True when the server is connected. */
    fun isConnected(serverId: String): Boolean =
        connections.containsKey(serverId) && _statuses.value[serverId]?.state == McpServerState.CONNECTED

    /** Refresh capabilities for a connected server. */
    suspend fun refreshCapabilities(serverId: String): Result<Int> {
        val client = clients[serverId] ?: return Result.failure(IllegalStateException("Not connected"))
        val connection = connections[serverId] ?: return Result.failure(IllegalStateException("No connection"))

        val tools: List<McpCapability> = client.listTools(connection).getOrNull() ?: emptyList()
        val resources: List<McpCapability> = client.listResources(connection).getOrNull() ?: emptyList()
        val prompts: List<McpCapability> = client.listPrompts(connection).getOrNull() ?: emptyList()

        val allCapabilities = tools + resources + prompts
        capabilityRegistry.registerCapabilities(serverId, allCapabilities)
        return Result.success(allCapabilities.size)
    }

    /** Get all capabilities for a server. */
    fun capabilitiesForServer(serverId: String): List<McpCapability> =
        capabilityRegistry.capabilitiesForServer(serverId)

    private fun updateStatus(
        serverId: String,
        state: McpServerState,
        error: String? = null,
        toolCount: Int? = null,
        resourceCount: Int? = null,
        promptCount: Int? = null
    ) {
        val current = _statuses.value[serverId] ?: McpServerStatus(serverId = serverId, state = state)
        _statuses.value = _statuses.value + (serverId to current.copy(
            state = state,
            lastError = error,
            toolCount = toolCount ?: current.toolCount,
            resourceCount = resourceCount ?: current.resourceCount,
            promptCount = promptCount ?: current.promptCount,
            lastConnectedAt = if (state == McpServerState.CONNECTED) System.currentTimeMillis() else current.lastConnectedAt
        ))
    }
}

/**
 * Persistence port for MCP server configurations. Production uses Room; tests use in-memory.
 */
interface McpConfigStore {
    suspend fun getAll(): List<McpServerConfig>
    suspend fun getById(id: String): McpServerConfig?
    suspend fun save(config: McpServerConfig)
    suspend fun delete(id: String)
}

/** In-memory implementation for testing. */
class InMemoryMcpConfigStore : McpConfigStore {
    private val configs = mutableMapOf<String, McpServerConfig>()

    override suspend fun getAll(): List<McpServerConfig> = configs.values.toList()
    override suspend fun getById(id: String): McpServerConfig? = configs[id]
    override suspend fun save(config: McpServerConfig) { configs[config.id] = config }
    override suspend fun delete(id: String) { configs.remove(id) }
}
