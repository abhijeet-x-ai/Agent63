package com.devstation.android.core.agent

import com.devstation.android.core.ai.AIToolParameterType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Phase 6 §67: tool arguments produced by the model are UNTRUSTED input.
 *
 * Everything is validated against the tool's declared schema before a tool body runs:
 * unknown parameters are rejected, required parameters must exist, and types must match.
 * Values are additionally bounded (lengths, ranges) so a malformed call cannot blow up memory
 * or smuggle an enormous payload into a file write.
 */
sealed class ArgumentValidation {
    object Valid : ArgumentValidation()
    data class Invalid(val message: String) : ArgumentValidation()
}

object ToolArgumentValidator {

    const val MAX_STRING_LENGTH = 2_000_000
    const val MAX_PARAMETERS = 32

    fun validate(definition: ToolDefinition, args: JsonObject): ArgumentValidation {
        if (definition.parameters.size > MAX_PARAMETERS) {
            return ArgumentValidation.Invalid("Tool '${definition.name}' declares too many parameters.")
        }

        val declared = definition.parameters.associateBy { it.name }

        for (key in args.keys) {
            if (key !in declared) {
                return ArgumentValidation.Invalid("Unknown parameter '$key' for tool '${definition.name}'.")
            }
        }

        for (param in definition.parameters) {
            val value = args[param.name]
            if (value == null || value is JsonNull) {
                if (param.required) {
                    return ArgumentValidation.Invalid("Missing required parameter '${param.name}'.")
                }
                continue
            }
            val typeError = checkType(param.name, param.type, param.itemType, value)
            if (typeError != null) return ArgumentValidation.Invalid(typeError)
        }

        return ArgumentValidation.Valid
    }

    private fun checkType(
        name: String,
        type: AIToolParameterType,
        itemType: AIToolParameterType?,
        value: kotlinx.serialization.json.JsonElement
    ): String? {
        when (type) {
            AIToolParameterType.STRING -> {
                val primitive = value as? JsonPrimitive
                    ?: return "Parameter '$name' must be a string."
                // A JSON number/boolean is not a string, even though its content is renderable.
                if (!primitive.isString) return "Parameter '$name' must be a string."
                if (primitive.content.length > MAX_STRING_LENGTH) {
                    return "Parameter '$name' exceeds the maximum allowed length."
                }
            }
            AIToolParameterType.INTEGER -> {
                val primitive = value as? JsonPrimitive
                    ?: return "Parameter '$name' must be an integer."
                if (primitive.longOrNull == null) return "Parameter '$name' must be an integer."
            }
            AIToolParameterType.NUMBER -> {
                val primitive = value as? JsonPrimitive
                    ?: return "Parameter '$name' must be a number."
                if (primitive.doubleOrNull == null) return "Parameter '$name' must be a number."
            }
            AIToolParameterType.BOOLEAN -> {
                val primitive = value as? JsonPrimitive
                    ?: return "Parameter '$name' must be a boolean."
                if (primitive.booleanOrNull == null) return "Parameter '$name' must be a boolean."
            }
            AIToolParameterType.ARRAY -> {
                val array = value as? JsonArray ?: return "Parameter '$name' must be an array."
                if (array.size > 4096) return "Parameter '$name' has too many items."
                val expected = itemType ?: AIToolParameterType.STRING
                for (item in array) {
                    val error = checkType(name, expected, null, item)
                    if (error != null) return error
                }
            }
        }
        return null
    }
}
