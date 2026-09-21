package com.devstation.android.core.ai

/**
 * Phase 6: provider-neutral tool calling.
 *
 * This is the ONLY place where tool definitions and tool calls cross the AI provider
 * boundary. The agent layer speaks [AIToolSpec] / [AIToolCall]; each adapter translates
 * them into its own wire format (OpenAI `tools`, Anthropic `tools`, Gemini
 * `functionDeclarations`) and back.
 *
 * Nothing here may reference a provider-specific format.
 */

/** JSON-schema-like parameter type. Deliberately small: only what tools actually need. */
enum class AIToolParameterType(val wireType: String) {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    ARRAY("array")
}

/** One parameter of a tool's input schema. */
data class AIToolParameter(
    val name: String,
    val type: AIToolParameterType,
    val description: String,
    val required: Boolean = true,
    /** For [AIToolParameterType.ARRAY]: element type. */
    val itemType: AIToolParameterType? = null
)

/**
 * A tool advertised to a model. Provider-neutral mirror of a DevStation [ToolDefinition].
 * Parameter schemas are structured — arbitrary Kotlin functions are never exposed.
 */
data class AIToolSpec(
    val name: String,
    val description: String,
    val parameters: List<AIToolParameter> = emptyList()
)

/**
 * A tool invocation requested by the model. [argumentsJson] is **untrusted input** and must
 * be schema-validated before execution.
 */
data class AIToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)
