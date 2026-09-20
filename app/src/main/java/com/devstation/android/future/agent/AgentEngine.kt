package com.devstation.android.future.agent

import kotlinx.coroutines.flow.Flow

data class AgentStep(
    val id: String,
    val thought: String?,
    val actionName: String?,
    val actionInput: String?,
    val observation: String?,
    val status: String
)

interface ToolExecutor {
    val supportedTools: List<String>
    suspend fun executeTool(name: String, argumentsJson: String): Result<String>
}

/**
 * Extension contract for Phase 5 & 6: AI Provider & Autonomous Agent Engine.
 */
interface AgentEngine {
    val isRunning: Flow<Boolean>
    val stepFlow: Flow<AgentStep>

    suspend fun startPlan(goal: String, projectPath: String, toolExecutor: ToolExecutor): Result<Unit>
    suspend fun stopPlan()
}
