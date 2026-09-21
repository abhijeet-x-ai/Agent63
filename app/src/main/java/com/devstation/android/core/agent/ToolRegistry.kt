package com.devstation.android.core.agent

import com.devstation.android.core.ai.AIToolSpec

/**
 * Central registry of every tool the agent may use.
 *
 * This is the single extension point for later phases: MCP, browser, Git, GitHub, deployment
 * and remote tools register here through the same [Tool] API without any runtime change.
 * Phase 6 registers only the built-in DevStation tools.
 */
class ToolRegistry(initialTools: List<Tool> = emptyList()) {

    private val toolsByName = LinkedHashMap<String, Tool>()

    init {
        initialTools.forEach { register(it) }
    }

    fun register(tool: Tool) {
        val name = tool.definition.name
        require(NAME_PATTERN.matches(name)) { "Invalid tool name: '$name'" }
        require(toolsByName[name] == null) { "Tool '$name' is already registered." }
        toolsByName[name] = tool
    }

    fun get(name: String): Tool? = toolsByName[name]

    fun contains(name: String): Boolean = toolsByName.containsKey(name)

    val size: Int
        get() = toolsByName.size

    val names: List<String>
        get() = toolsByName.keys.toList()

    fun definitions(): List<ToolDefinition> = toolsByName.values.map { it.definition }

    /** Provider-neutral specs sent to models with native tool calling. */
    fun specs(): List<AIToolSpec> = toolsByName.values.map { it.definition.toSpec() }

    companion object {
        private val NAME_PATTERN = Regex("^[a-zA-Z0-9_-]{1,64}$")
    }
}
