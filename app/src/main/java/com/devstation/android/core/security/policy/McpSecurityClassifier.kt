package com.devstation.android.core.security.policy

import com.devstation.android.core.mcp.McpCapability
import com.devstation.android.core.mcp.McpCapabilityType
import com.devstation.android.core.mcp.McpSecurityClassification

/**
 * Phase 8 §8: Classifies MCP capabilities into DevStation security categories.
 *
 * Classification is based on capability metadata and naming heuristics.
 * Unknown or ambiguous capabilities always get the strictest treatment.
 * Server-provided descriptions are NEVER trusted for security decisions.
 */
object McpSecurityClassifier {

    private val READ_ONLY_KEYWORDS = setOf(
        "read", "get", "list", "search", "find", "query", "fetch", "describe",
        "inspect", "view", "show", "info", "status", "check", "look", "discover"
    )

    private val WRITE_KEYWORDS = setOf(
        "write", "create", "update", "modify", "edit", "set", "put", "patch",
        "save", "store", "insert", "add", "append"
    )

    private val DESTRUCTIVE_KEYWORDS = setOf(
        "delete", "remove", "destroy", "drop", "purge", "clear", "wipe",
        "erase", "truncate", "kill"
    )

    private val NETWORK_KEYWORDS = setOf(
        "http", "url", "web", "fetch_url", "download", "upload", "request",
        "api", "rest", "graphql", "websocket", "socket"
    )

    private val SYSTEM_KEYWORDS = setOf(
        "exec", "run", "shell", "command", "process", "spawn", "system",
        "terminal", "bash", "sh"
    )

    private val PACKAGE_KEYWORDS = setOf(
        "install", "uninstall", "npm", "pip", "apk", "package", "dependency"
    )

    /** Classify an MCP capability into a security classification. */
    fun classify(capability: McpCapability): McpSecurityClassification {
        if (capability.capabilityType == McpCapabilityType.RESOURCE ||
            capability.capabilityType == McpCapabilityType.PROMPT
        ) {
            // Resources and prompts are read-only by nature
            return McpSecurityClassification.READ_ONLY
        }

        // For tools, classify based on name + description keywords
        val combined = "${capability.name} ${capability.description}".lowercase()

        // Check in order of severity (most restrictive first)
        if (matchesAny(combined, SYSTEM_KEYWORDS)) return McpSecurityClassification.SYSTEM
        if (matchesAny(combined, DESTRUCTIVE_KEYWORDS)) return McpSecurityClassification.DESTRUCTIVE
        if (matchesAny(combined, PACKAGE_KEYWORDS)) return McpSecurityClassification.PACKAGE_INSTALL
        if (matchesAny(combined, NETWORK_KEYWORDS)) return McpSecurityClassification.NETWORK
        if (matchesAny(combined, WRITE_KEYWORDS)) return McpSecurityClassification.PROJECT_WRITE
        if (matchesAny(combined, READ_ONLY_KEYWORDS)) return McpSecurityClassification.READ_ONLY

        // Unknown: strictest treatment
        return McpSecurityClassification.UNKNOWN
    }

    /** Map MCP classification to the DevStation security resource type. */
    fun toResourceType(classification: McpSecurityClassification): ResourceType = when (classification) {
        McpSecurityClassification.READ_ONLY -> ResourceType.PROJECT_FILE
        McpSecurityClassification.PROJECT_WRITE -> ResourceType.PROJECT_FILE
        McpSecurityClassification.NETWORK -> ResourceType.NETWORK
        McpSecurityClassification.PACKAGE_INSTALL -> ResourceType.PACKAGE_MANAGER
        McpSecurityClassification.DESTRUCTIVE -> ResourceType.PROJECT_FILE
        McpSecurityClassification.SYSTEM -> ResourceType.PROCESS
        McpSecurityClassification.UNKNOWN -> ResourceType.UNKNOWN
    }

    /** Map MCP classification to a security action. */
    fun toSecurityAction(classification: McpSecurityClassification): SecurityAction = when (classification) {
        McpSecurityClassification.READ_ONLY -> SecurityAction.READ
        McpSecurityClassification.PROJECT_WRITE -> SecurityAction.WRITE
        McpSecurityClassification.NETWORK -> SecurityAction.NETWORK
        McpSecurityClassification.PACKAGE_INSTALL -> SecurityAction.INSTALL
        McpSecurityClassification.DESTRUCTIVE -> SecurityAction.DELETE
        McpSecurityClassification.SYSTEM -> SecurityAction.EXECUTE
        McpSecurityClassification.UNKNOWN -> SecurityAction.UNKNOWN
    }

    /** Map MCP classification to a tool permission level. */
    fun toToolPermission(classification: McpSecurityClassification): com.devstation.android.core.agent.ToolPermission =
        when (classification) {
            McpSecurityClassification.READ_ONLY -> com.devstation.android.core.agent.ToolPermission.ALLOW
            McpSecurityClassification.PROJECT_WRITE -> com.devstation.android.core.agent.ToolPermission.ASK
            McpSecurityClassification.NETWORK -> com.devstation.android.core.agent.ToolPermission.ASK
            McpSecurityClassification.PACKAGE_INSTALL -> com.devstation.android.core.agent.ToolPermission.ALWAYS_ASK
            McpSecurityClassification.DESTRUCTIVE -> com.devstation.android.core.agent.ToolPermission.ALWAYS_ASK
            McpSecurityClassification.SYSTEM -> com.devstation.android.core.agent.ToolPermission.ALWAYS_ASK
            McpSecurityClassification.UNKNOWN -> com.devstation.android.core.agent.ToolPermission.ALWAYS_ASK
        }

    /** Map MCP classification to risk level. */
    fun toRiskLevel(classification: McpSecurityClassification): com.devstation.android.core.agent.ToolRiskLevel =
        when (classification) {
            McpSecurityClassification.READ_ONLY -> com.devstation.android.core.agent.ToolRiskLevel.LOW
            McpSecurityClassification.PROJECT_WRITE -> com.devstation.android.core.agent.ToolRiskLevel.MEDIUM
            McpSecurityClassification.NETWORK -> com.devstation.android.core.agent.ToolRiskLevel.MEDIUM
            McpSecurityClassification.PACKAGE_INSTALL -> com.devstation.android.core.agent.ToolRiskLevel.HIGH
            McpSecurityClassification.DESTRUCTIVE -> com.devstation.android.core.agent.ToolRiskLevel.HIGH
            McpSecurityClassification.SYSTEM -> com.devstation.android.core.agent.ToolRiskLevel.CRITICAL
            McpSecurityClassification.UNKNOWN -> com.devstation.android.core.agent.ToolRiskLevel.HIGH
        }

    private fun matchesAny(text: String, keywords: Set<String>): Boolean =
        keywords.any { text.contains(it) }
}
