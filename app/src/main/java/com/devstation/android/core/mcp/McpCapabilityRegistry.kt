package com.devstation.android.core.mcp

import com.devstation.android.core.security.policy.McpSecurityClassifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 8 §6: Central registry of all discovered MCP capabilities.
 *
 * Capabilities are discovered during server connection and classified by the security engine.
 * This registry is the bridge between MCP servers and the DevStation tool system.
 */
class McpCapabilityRegistry {

    /** serverId → capabilities */
    private val serverCapabilities = ConcurrentHashMap<String, MutableList<McpCapability>>()

    /** serverId:capabilityName → capability (fast lookup) */
    private val allCapabilities = ConcurrentHashMap<String, McpCapability>()

    /** Register capabilities from a single server (replaces previous capabilities for that server). */
    fun registerCapabilities(serverId: String, capabilities: List<McpCapability>) {
        removeServerCapabilities(serverId)
        val classified = capabilities.map { cap ->
            val classification = McpSecurityClassifier.classify(cap)
            cap.copy(
                serverId = serverId,
                securityClassification = classification
            )
        }
        serverCapabilities[serverId] = classified.toMutableList()
        classified.forEach { cap ->
            allCapabilities["$serverId:${cap.name}"] = cap
        }
    }

    /** Remove all capabilities from a server. */
    fun removeServerCapabilities(serverId: String) {
        val removed = serverCapabilities.remove(serverId) ?: return
        removed.forEach { cap ->
            allCapabilities.remove("$serverId:${cap.name}")
        }
    }

    /** Get all capabilities for a specific server. */
    fun capabilitiesForServer(serverId: String): List<McpCapability> =
        serverCapabilities[serverId]?.toList() ?: emptyList()

    /** Get a specific capability by server and name. */
    fun getCapability(serverId: String, name: String): McpCapability? =
        allCapabilities["$serverId:$name"]

    /** Get all capabilities across all servers. */
    fun allCapabilities(): List<McpCapability> =
        allCapabilities.values.toList()

    /** Get only tool capabilities across all servers. */
    fun allTools(): List<McpCapability> =
        allCapabilities.values.filter { it.capabilityType == McpCapabilityType.TOOL }

    /** Get tools from a specific server. */
    fun toolsForServer(serverId: String): List<McpCapability> =
        capabilitiesForServer(serverId).filter { it.capabilityType == McpCapabilityType.TOOL }

    /** Total capability count across all servers. */
    val totalCount: Int get() = allCapabilities.size

    /** Total tool count across all servers. */
    val totalToolCount: Int get() = allCapabilities.values.count { it.capabilityType == McpCapabilityType.TOOL }

    /** Clear all capabilities. */
    fun clear() {
        serverCapabilities.clear()
        allCapabilities.clear()
    }
}
