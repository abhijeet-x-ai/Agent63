package com.devstation.android.core.mcp

import com.devstation.android.core.agent.Tool
import com.devstation.android.core.agent.ToolContext
import com.devstation.android.core.agent.ToolDefinition
import com.devstation.android.core.agent.ToolResult
import com.devstation.android.core.ai.AIToolCall
import com.devstation.android.core.ai.AIToolParameter
import com.devstation.android.core.ai.AIToolParameterType
import com.devstation.android.core.security.policy.McpSecurityClassifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.boolean

/**
 * Phase 8 §7: Wraps an MCP tool as a DevStation [Tool].
 *
 * When the agent calls an MCP tool, the request flows through:
 *   AgentRuntime → ToolExecutor → SecurityPolicyEngine → this wrapper → MCP transport
 *
 * The wrapper never bypasses security. It is registered in the ToolRegistry like any other tool,
 * so the full permission/revalidation/audit pipeline applies.
 */
class McpToolWrapper(
    private val capability: McpCapability,
    private val serverManager: McpServerManager,
    private val serverName: String
) : Tool {

    private val classifier = McpSecurityClassifier
    private val classification = classifier.classify(capability)

    override val definition: ToolDefinition = ToolDefinition(
        name = "mcp_${sanitizeName(capability.serverId)}_${sanitizeName(capability.name)}",
        description = buildString {
            append("[MCP:${serverName}] ")
            append(capability.description.ifBlank { "MCP tool: ${capability.name}" })
        },
        parameters = buildParameters(),
        riskLevel = classifier.toRiskLevel(classification),
        permission = classifier.toToolPermission(classification),
        classificationDriven = false,
        requiresProject = false, // MCP tools are server-scoped, not project-scoped
        resourceType = classifier.toResourceType(classification),
        action = classifier.toSecurityAction(classification),
        resourceArgument = "mcp_arguments",
        networkImpact = if (classification == McpSecurityClassification.NETWORK) {
            com.devstation.android.core.security.policy.NetworkIntent.INTERNET
        } else {
            com.devstation.android.core.security.policy.NetworkIntent.NONE
        },
        filesystemImpact = com.devstation.android.core.security.policy.ImpactLevel.PROJECT,
        destructive = classification == McpSecurityClassification.DESTRUCTIVE,
        sensitive = false
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val client = serverManager.getClient(capability.serverId)
            ?: return ToolResult.Error(definition.name, "MCP server '${capability.serverId}' is not connected.")
        val connection = serverManager.getConnection(capability.serverId)
            ?: return ToolResult.Error(definition.name, "No active connection to MCP server '${capability.serverId}'.")

        // Extract arguments — pass through to the MCP server
        val arguments = mutableMapOf<String, Any>()
        args.forEach { (key, value) ->
            if (key != "mcp_arguments") {
                arguments[key] = when (value) {
                    is JsonPrimitive -> {
                        if (value.isString) value.content
                        else value.content
                    }
                    else -> value.toString()
                }
            }
        }

        // If there's a single "mcp_arguments" JSON object, merge it
        val mcpArgs = args["mcp_arguments"]
        if (mcpArgs is JsonObject) {
            mcpArgs.forEach { (key, value) ->
                arguments[key] = when (value) {
                    is JsonPrimitive -> if (value.isString) value.content else value.content
                    else -> value.toString()
                }
            }
        }

        // Execute through the MCP client
        val result = client.callTool(connection, capability.name, arguments).getOrElse { e ->
            return ToolResult.Error(definition.name, "MCP call failed: ${e.message}")
        }

        return when (result) {
            is McpToolResult.Success -> ToolResult.Success(
                toolName = definition.name,
                output = result.output,
                metadata = mapOf(
                    "mcp_server" to capability.serverId,
                    "mcp_tool" to capability.name
                )
            )
            is McpToolResult.Error -> ToolResult.Error(definition.name, result.message)
            is McpToolResult.Denied -> ToolResult.Denied(definition.name, result.reason)
            is McpToolResult.Timeout -> ToolResult.Timeout(definition.name, result.timeoutMs)
        }
    }

    override fun summarize(args: JsonObject): String = "MCP:${serverName}/${capability.name}"

    private fun buildParameters(): List<AIToolParameter> {
        val params = mutableListOf<AIToolParameter>()

        // Parse the input schema from the MCP capability
        val schemaProps = capability.inputSchema["properties"] as? Map<*, *>
        val required = (capability.inputSchema["required"] as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet()

        schemaProps?.forEach { (key, value) ->
            val propMap = value as? Map<*, *>
            val type = propMap?.get("type")?.toString() ?: "string"
            val description = propMap?.get("description")?.toString() ?: ""
            val isRequired = key in required

            val paramType = when (type) {
                "integer" -> AIToolParameterType.INTEGER
                "number" -> AIToolParameterType.NUMBER
                "boolean" -> AIToolParameterType.BOOLEAN
                "array" -> AIToolParameterType.ARRAY
                else -> AIToolParameterType.STRING
            }
            params.add(
                AIToolParameter(
                    name = key.toString(),
                    type = paramType,
                    description = description,
                    required = isRequired
                )
            )
        }

        if (params.isEmpty()) {
            // At minimum, accept a JSON arguments object
            params.add(
                AIToolParameter(
                    name = "mcp_arguments",
                    type = AIToolParameterType.STRING,
                    description = "Arguments to pass to the MCP tool as a JSON object",
                    required = false
                )
            )
        }

        return params
    }

    companion object {
        /** Sanitize a name to be a valid DevStation tool name (alphanumeric + underscore + hyphen). */
        fun sanitizeName(raw: String): String =
            raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
    }
}
