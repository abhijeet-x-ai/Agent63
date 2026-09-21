package com.devstation.android.core.agent.tools

import com.devstation.android.core.agent.AgentLoopLimits
import com.devstation.android.core.agent.OutputLimiter
import com.devstation.android.core.agent.PathSandbox
import com.devstation.android.core.agent.SecretRedactor
import com.devstation.android.core.agent.Tool
import com.devstation.android.core.agent.ToolContext
import com.devstation.android.core.agent.ToolDefinition
import com.devstation.android.core.agent.ToolPermission
import com.devstation.android.core.agent.ToolResult
import com.devstation.android.core.agent.ToolRiskLevel
import com.devstation.android.core.ai.AIToolCall
import com.devstation.android.core.ai.AIToolParameter
import com.devstation.android.core.ai.AIToolParameterType
import com.devstation.android.feature.editor.service.ProjectSearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import com.devstation.android.core.security.policy.ResourceType
import com.devstation.android.core.security.policy.SecurityAction
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * search_project: project-wide text search.
 *
 * Reuses the Phase 4 [ProjectSearchEngine] (regex/whole-word/case handling already verified)
 * instead of introducing a second search implementation.
 */
class SearchProjectTool(private val limits: AgentLoopLimits) : Tool {

    override val definition = ToolDefinition(
        name = "search_project",
        description = "Search the project text files for a query and return matching file, line " +
            "number and matching text. Generated and dependency directories are skipped.",
        parameters = listOf(
            AIToolParameter("query", AIToolParameterType.STRING, "Text to search for"),
            AIToolParameter("path", AIToolParameterType.STRING, "Optional subdirectory to search (default: project root)", required = false),
            AIToolParameter("filePattern", AIToolParameterType.STRING, "Optional filename filter, e.g. *.kt", required = false),
            AIToolParameter("caseSensitive", AIToolParameterType.BOOLEAN, "Match case (default false)", required = false),
            AIToolParameter("wholeWord", AIToolParameterType.BOOLEAN, "Match whole words only (default false)", required = false),
            AIToolParameter("maxResults", AIToolParameterType.INTEGER, "Maximum matches to return", required = false)
        ),
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        resourceType = ResourceType.PROJECT_DIRECTORY,
        action = SecurityAction.READ
    )

    override fun summarize(args: JsonObject) = "Search for \"${string(args, "query") ?: ""}\""

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val query = string(args, "query")
                    ?: return@withContext ToolResult.Error("search_project", "A 'query' is required.")
                if (query.isBlank()) {
                    return@withContext ToolResult.Error("search_project", "The search query must not be blank.")
                }
                if (query.length > MAX_QUERY_LENGTH) {
                    return@withContext ToolResult.Error("search_project", "The search query is too long.")
                }

                val baseDir = PathSandbox.resolve(context.projectRoot, string(args, "path")?.takeIf { it.isNotBlank() } ?: ".")
                if (!baseDir.exists() || !baseDir.isDirectory) {
                    return@withContext ToolResult.Error("search_project", "Search path is not a directory.")
                }

                val maxResults = (int(args, "maxResults") ?: limits.maxSearchResults)
                    .coerceIn(1, limits.maxSearchResults)
                val caseSensitive = bool(args, "caseSensitive") ?: false
                val wholeWord = bool(args, "wholeWord") ?: false
                val filePattern = string(args, "filePattern")

                val engine = ProjectSearchEngine(baseDir)
                val all = engine.search(
                    query = query,
                    isCaseSensitive = caseSensitive,
                    isWholeWord = wholeWord,
                    maxResults = maxResults
                )
                val filtered = if (filePattern.isNullOrBlank()) all else {
                    val matcher = globToRegex(filePattern)
                    all.filter { matcher.matches(it.filePath.substringAfterLast('/')) || matcher.matches(it.relativePath) }
                }

                if (filtered.isEmpty()) {
                    return@withContext ToolResult.Success(
                        "search_project",
                        "${AgentLabels.SEARCH_RESULT} No matches for \"$query\".",
                        mapOf("matchCount" to "0")
                    )
                }

                val body = buildString {
                    appendLine("${filtered.size} match(es) for \"$query\":")
                    filtered.forEach { result ->
                        appendLine("${result.relativePath}:${result.lineNumber}: ${result.lineContent}")
                    }
                }
                val bounded = OutputLimiter.truncate(body, limits.maxToolOutputChars)
                ToolResult.Success(
                    "search_project",
                    "${AgentLabels.SEARCH_RESULT} ${SecretRedactor.redact(bounded)}",
                    mapOf("matchCount" to filtered.size.toString(), "query" to query)
                )
            } catch (e: PathSandbox.PathRejected) {
                ToolResult.Error("search_project", e.message ?: "Search path rejected.")
            } catch (e: Exception) {
                ToolResult.Error("search_project", "Search failed: ${e.message ?: "unknown error"}")
            }
        }

    private fun string(args: JsonObject, name: String): String? =
        (args[name] as? JsonPrimitive)?.content

    private fun int(args: JsonObject, name: String): Int? =
        (args[name] as? JsonPrimitive)?.intOrNull

    private fun bool(args: JsonObject, name: String): Boolean? =
        (args[name] as? JsonPrimitive)?.booleanOrNull

    private fun globToRegex(pattern: String): Regex {
        val escaped = StringBuilder("^")
        pattern.forEach { ch ->
            when (ch) {
                '*' -> escaped.append(".*")
                '?' -> escaped.append('.')
                '.' -> escaped.append("\\.")
                else -> escaped.append(Regex.escape(ch.toString()))
            }
        }
        escaped.append('$')
        return Regex(escaped.toString(), RegexOption.IGNORE_CASE)
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 500
    }
}
