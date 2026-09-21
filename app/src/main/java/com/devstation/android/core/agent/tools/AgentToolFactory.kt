package com.devstation.android.core.agent.tools

import com.devstation.android.core.agent.AgentLoopLimits
import com.devstation.android.core.agent.Tool
import com.devstation.android.core.agent.ToolRegistry

/** Builds the tool registry for one task, and lets the runtime release per-task tool state. */
interface AgentToolFactory {
    /** Tools available to the agent, bound to nothing yet — tools read their root from ToolContext. */
    fun create(limits: AgentLoopLimits): ToolRegistry

    /** Drops per-task caches (read hashes) when a task ends. */
    fun clearTaskState(taskId: String)

    val editorBridge: EditorBridge

    /** Names of tools that mutate project files; used for the completion summary. */
    val fileMutationToolNames: Set<String>
}

/**
 * Default Phase 6 tool set. Every entry is a built-in DevStation tool — no MCP, browser, Git,
 * GitHub, remote or deployment tools exist in this phase.
 */
class DefaultAgentToolFactory(
    override val editorBridge: EditorBridge,
    private val processRegistry: AgentProcessRegistry,
    private val linuxRunner: CommandRunner?,
    private val androidRunner: CommandRunner,
    private val linuxAvailable: () -> Boolean,
    private val allowAndroidFallback: () -> Boolean,
    /** Phase 7: current policy limits, read per task so a policy change applies to new tasks. */
    private val resourceLimits: () -> com.devstation.android.core.security.policy.AgentResourceLimits =
        { com.devstation.android.core.security.policy.AgentResourceLimits() },
    private val terminalPolicy: com.devstation.android.core.security.policy.TerminalSecurityPolicy =
        com.devstation.android.core.security.policy.TerminalSecurityPolicy(),
    private val audit: com.devstation.android.core.security.policy.SecurityAuditLogger =
        com.devstation.android.core.security.policy.SecurityAuditLogger.NoOp
) : AgentToolFactory {

    override val fileMutationToolNames: Set<String> = setOf(
        "write_file", "apply_patch", "create_file", "create_directory", "rename_file", "delete_file"
    )

    override fun create(limits: AgentLoopLimits): ToolRegistry {
        val tools: List<Tool> = listOf(
            ReadFileTool(limits),
            WriteFileTool(),
            CreateFileTool(),
            DeleteFileTool(),
            ListDirectoryTool(),
            CreateDirectoryTool(),
            RenameFileTool(),
            ApplyPatchTool(),
            SearchProjectTool(limits),
            OpenFileTool(editorBridge),
            GetEditorStateTool(editorBridge),
            GetCurrentFileTool(editorBridge),
            RunTerminalCommandTool(
                linuxRunner = linuxRunner,
                androidRunner = androidRunner,
                linuxAvailable = linuxAvailable,
                allowAndroidFallback = allowAndroidFallback,
                registry = processRegistry,
                limits = limits,
                resourceLimits = resourceLimits(),
                terminalPolicy = terminalPolicy,
                audit = audit
            )
        )
        return ToolRegistry(tools)
    }

    override fun clearTaskState(taskId: String) {
        FileReadTrackerHolder.tracker.clearTask(taskId)
    }
}
