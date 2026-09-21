package com.devstation.android.core.agent.tools

import com.devstation.android.core.agent.PathSandbox
import com.devstation.android.core.agent.Tool
import com.devstation.android.core.agent.ToolContext
import com.devstation.android.core.agent.ToolDefinition
import com.devstation.android.core.agent.ToolPermission
import com.devstation.android.core.agent.ToolResult
import com.devstation.android.core.agent.ToolRiskLevel
import com.devstation.android.core.ai.AIToolCall
import com.devstation.android.core.ai.AIToolParameter
import com.devstation.android.core.ai.AIToolParameterType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bridge between the agent and the Phase 4 editor.
 *
 * The agent never implements its own editor: `open_file` publishes a navigation request that
 * the UI collects, and the editor reports its real state here so `get_editor_state` can answer
 * from the actual editor instance.
 */
/** A navigation request from the agent, carrying everything the UI needs to open the file. */
 data class EditorOpenRequest(
    val projectRoot: String,
    val filePath: String
)

interface EditorBridge {
    val currentFilePath: StateFlow<String?>
    val openFilePaths: StateFlow<List<String>>
    /** Navigation requests emitted by the agent. */
    val openRequests: SharedFlow<EditorOpenRequest>

    fun reportState(currentFilePath: String?, openFiles: List<String>)

    fun requestOpenFile(projectRoot: String, filePath: String)
}

class EditorBridgeImpl : EditorBridge {

    private val _currentFilePath = MutableStateFlow<String?>(null)
    override val currentFilePath: StateFlow<String?> = _currentFilePath.asStateFlow()

    private val _openFilePaths = MutableStateFlow<List<String>>(emptyList())
    override val openFilePaths: StateFlow<List<String>> = _openFilePaths.asStateFlow()

    private val _openRequests = MutableSharedFlow<EditorOpenRequest>(extraBufferCapacity = 16)
    override val openRequests: SharedFlow<EditorOpenRequest> = _openRequests.asSharedFlow()

    override fun reportState(currentFilePath: String?, openFiles: List<String>) {
        _currentFilePath.value = currentFilePath
        _openFilePaths.value = openFiles.toList()
    }

    override fun requestOpenFile(projectRoot: String, filePath: String) {
        _openRequests.tryEmit(EditorOpenRequest(projectRoot = projectRoot, filePath = filePath))
    }
}

/** open_file: asks the UI to open a project file in the real editor. */
class OpenFileTool(private val bridge: EditorBridge) : Tool {

    override val definition = ToolDefinition(
        name = "open_file",
        description = "Ask DevStation to open a project file in the editor so the user can see it. " +
            "This only opens the file; it does not read its contents or modify it.",
        parameters = listOf(
            AIToolParameter("path", AIToolParameterType.STRING, "Project-relative file path to open"),
            AIToolParameter("line", AIToolParameterType.INTEGER, "Optional 1-based line to focus", required = false)
        ),
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW
    )

    override fun summarize(args: JsonObject) = "Open ${string(args, "path") ?: "?"}"

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val rawPath = string(args, "path") ?: return ToolResult.Error("open_file", "A 'path' is required.")
        return try {
            val file = PathSandbox.resolve(context.projectRoot, rawPath)
            if (!file.exists()) return ToolResult.Error("open_file", "File not found: $rawPath")
            if (file.isDirectory) return ToolResult.Error("open_file", "$rawPath is a directory.")
            val relative = runCatching { file.relativeTo(context.projectRoot).path }.getOrDefault(file.name)
            bridge.requestOpenFile(
                projectRoot = context.projectRoot.absolutePath,
                filePath = file.absolutePath
            )
            ToolResult.Success(
                "open_file",
                "${AgentLabels.EDITOR_STATE} Requested that the editor open $relative.",
                mapOf("path" to relative)
            )
        } catch (e: PathSandbox.PathRejected) {
            ToolResult.Error("open_file", e.message ?: "Path rejected.")
        }
    }

    private fun string(args: JsonObject, name: String): String? = (args[name] as? JsonPrimitive)?.content
}

/** get_editor_state: what the editor currently has open (paths only, no contents). */
class GetEditorStateTool(private val bridge: EditorBridge) : Tool {

    override val definition = ToolDefinition(
        name = "get_editor_state",
        description = "Report which files are open in the editor and which one is active. " +
            "Returns paths only; use read_file for contents.",
        parameters = emptyList(),
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val active = bridge.currentFilePath.value
        val open = bridge.openFilePaths.value
        val body = buildString {
            appendLine("Active file: ${active ?: "none"}")
            if (open.isEmpty()) {
                appendLine("Open files: none")
            } else {
                appendLine("Open files:")
                open.forEach { appendLine("  $it") }
            }
        }
        return ToolResult.Success(
            "get_editor_state",
            "${AgentLabels.EDITOR_STATE} $body",
            mapOf("activeFile" to (active ?: ""), "openCount" to open.size.toString())
        )
    }
}

/** get_current_file: the file the user is looking at right now. */
class GetCurrentFileTool(private val bridge: EditorBridge) : Tool {

    override val definition = ToolDefinition(
        name = "get_current_file",
        description = "Return the project-relative path of the file currently active in the editor.",
        parameters = emptyList(),
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW
    )

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult {
        val active = bridge.currentFilePath.value
            ?: return ToolResult.Success(
                "get_current_file",
                "${AgentLabels.EDITOR_STATE} No file is currently open in the editor.",
                mapOf("activeFile" to "")
            )
        return ToolResult.Success(
            "get_current_file",
            "${AgentLabels.EDITOR_STATE} Current file: $active",
            mapOf("activeFile" to active)
        )
    }
}
