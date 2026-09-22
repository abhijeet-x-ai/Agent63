package com.devstation.android.core.skills

import com.devstation.android.core.agent.AgentRuntime
import com.devstation.android.core.agent.AgentStartResult
import com.devstation.android.core.agent.AgentTaskRequest

/**
 * Phase 8 §16/§22: Executes a skill by delegating to the existing AgentRuntime.
 *
 * The skill provides instructions that become the system prompt, and the AgentRuntime
 * handles the actual AI interaction, tool calling, and security enforcement.
 */
class SkillExecutor(
    private val agentRuntime: AgentRuntime
) {
    /**
     * Execute a skill. This creates an agent task with the skill's instructions as
     * context, and lets the existing AgentRuntime handle the loop.
     */
    suspend fun execute(
        skill: SkillDefinition,
        goal: String,
        projectId: String,
        conversationId: String?,
        context: SkillExecutionContext
    ): SkillExecutionResult {
        // Build a combined goal that includes the skill instructions
        val combinedGoal = buildString {
            appendLine("## Skill: ${skill.name}")
            appendLine("## Instructions:")
            appendLine(skill.instructions)
            appendLine()
            appendLine("## Task:")
            appendLine(goal)
            if (skill.requiredTools.isNotEmpty()) {
                appendLine()
                appendLine("## Available tools: ${skill.requiredTools.joinToString(", ")}")
            }
        }

        val request = AgentTaskRequest(
            goal = combinedGoal,
            projectId = projectId,
            conversationId = conversationId
        )

        val startTime = System.currentTimeMillis()

        return when (val result = agentRuntime.startTask(request)) {
            is AgentStartResult.Started -> {
                // The task is now running asynchronously. For now, we report that it started.
                // A more complete implementation would wait for completion, but the AgentRuntime
                // is designed for async execution.
                SkillExecutionResult.Success(
                    skillId = skill.id,
                    output = "Skill '${skill.name}' execution started (task: ${result.taskId})",
                    toolCallsUsed = context.toolCalls,
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
            is AgentStartResult.Rejected -> {
                SkillExecutionResult.Error(
                    skillId = skill.id,
                    message = "Skill execution rejected: ${result.message}"
                )
            }
        }
    }
}
