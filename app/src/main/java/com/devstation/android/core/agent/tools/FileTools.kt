package com.devstation.android.core.agent.tools

import com.devstation.android.core.agent.AgentLoopLimits
import com.devstation.android.core.agent.OutputLimiter
import com.devstation.android.core.agent.PathSandbox
import com.devstation.android.core.security.policy.ResourceType
import com.devstation.android.core.security.policy.SecurityAction
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
import com.devstation.android.feature.editor.model.LineEnding
import com.devstation.android.feature.editor.service.EditorFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Label prefixes keep tool data visually distinct from instructions in the agent context. */
object AgentLabels {
    const val FILE_CONTENT = "[FILE CONTENT]"
    const val TERMINAL_OUTPUT = "[TERMINAL OUTPUT]"
    const val SEARCH_RESULT = "[SEARCH RESULT]"
    const val EDITOR_STATE = "[EDITOR STATE]"
    const val TOOL_RESULT = "[TOOL RESULT]"
}

/**
 * Remembers the content hash of files the agent has read, so a later write can detect that the
 * editor, the terminal, or the user changed the file in between (Phase 6 §44).
 */
class FileStateTracker {
    private val hashes = ConcurrentHashMap<String, String>()

    fun record(taskId: String, path: String, hash: String) {
        hashes[key(taskId, path)] = hash
    }

    fun hashFor(taskId: String, path: String): String? = hashes[key(taskId, path)]

    fun clearTask(taskId: String) {
        hashes.keys.filter { it.startsWith("$taskId|") }.forEach { hashes.remove(it) }
    }

    private fun key(taskId: String, path: String) = "$taskId|$path"
}

/** Shared helpers for the file tools. */
private object FileToolSupport {

    /** Absolute cap on what we will even attempt to read into memory. */
    const val HARD_MAX_FILE_BYTES = 8L * 1024 * 1024

    /** Above this, a plain read returns metadata instead of content unless a range is asked for. */
    const val LARGE_FILE_BYTES = 256L * 1024

    fun stringArg(args: JsonObject, name: String): String? =
        (args[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: (args[name] as? JsonPrimitive)?.content

    fun intArg(args: JsonObject, name: String): Int? = (args[name] as? JsonPrimitive)?.intOrNull

    fun longArg(args: JsonObject, name: String): Long? = (args[name] as? JsonPrimitive)?.longOrNull

    fun safe(block: () -> ToolResult): ToolResult = try {
        block()
    } catch (e: PathSandbox.PathRejected) {
        ToolResult.Error("path_error", e.message ?: "Path rejected.")
    } catch (e: SecurityException) {
        ToolResult.Error("path_error", e.message ?: "Path rejected.")
    } catch (e: IllegalArgumentException) {
        ToolResult.Error("invalid_argument", e.message ?: "Invalid argument.")
    } catch (e: Exception) {
        ToolResult.Error("io_error", "File operation failed: ${e.message ?: "unknown error"}")
    }
}

/** read_file: bounded content reads that never dump a whole repository into the context. */
class ReadFileTool(
    private val limits: AgentLoopLimits,
    private val tracker: FileStateTracker = FileReadTrackerHolder.tracker
) : Tool {

    override val definition = ToolDefinition(
        name = "read_file",
        description = "Read a text file from the current project. Use startLine/endLine to read " +
            "a specific range of a large file. Binary files return metadata only.",
        parameters = listOf(
            AIToolParameter("path", AIToolParameterType.STRING, "Project-relative file path, e.g. src/main.py"),
            AIToolParameter("startLine", AIToolParameterType.INTEGER, "First line to read (1-based, inclusive)", required = false),
            AIToolParameter("endLine", AIToolParameterType.INTEGER, "Last line to read (1-based, inclusive)", required = false)
        ),
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.READ
    )

    override fun summarize(args: JsonObject) = "Read ${FileToolSupport.stringArg(args, "path") ?: "?"}"

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            FileToolSupport.safe {
                val rawPath = FileToolSupport.stringArg(args, "path")
                    ?: return@safe ToolResult.Error("read_file", "A 'path' is required.")
                val file = PathSandbox.resolve(context.projectRoot, rawPath)
                if (!file.exists()) return@safe ToolResult.Error("read_file", "File not found: $rawPath")
                if (file.isDirectory) {
                    return@safe ToolResult.Error("read_file", "$rawPath is a directory. Use list_directory instead.")
                }
                if (file.length() > FileToolSupport.HARD_MAX_FILE_BYTES) {
                    return@safe ToolResult.Error(
                        "read_file",
                        "File is too large to read (${file.length()} bytes). Request a smaller file or use search_project."
                    )
                }

                val manager = EditorFileManager(context.projectRoot)
                if (manager.isBinaryFile(file)) {
                    return@safe ToolResult.Success(
                        "read_file",
                        "${AgentLabels.FILE_CONTENT} ${relative(context, file)} is a binary file " +
                            "(${file.length()} bytes); contents are not returned.",
                        mapOf("binary" to "true", "sizeBytes" to file.length().toString())
                    )
                }

                val document = manager.readFile(file)
                val allLines = document.content.split("\n")
                val totalLines = allLines.size
                val startLine = FileToolSupport.intArg(args, "startLine")
                val endLine = FileToolSupport.intArg(args, "endLine")
                val rangeRequested = startLine != null || endLine != null

                if (!rangeRequested && document.fileSizeBytes > FileToolSupport.LARGE_FILE_BYTES) {
                    val suggested = suggestedRanges(totalLines)
                    return@safe ToolResult.Success(
                        "read_file",
                        "${AgentLabels.FILE_CONTENT} ${relative(context, file)} is large " +
                            "(${document.fileSizeBytes} bytes, $totalLines lines). " +
                            "Request a range with startLine/endLine, for example $suggested.",
                        mapOf("totalLines" to totalLines.toString(), "truncated" to "true")
                    )
                }

                val from = (startLine ?: 1).coerceAtLeast(1)
                val requestedTo = endLine ?: (from + limits.maxFileReadLines - 1)
                val to = requestedTo.coerceAtLeast(from)
                if (from > totalLines) {
                    return@safe ToolResult.Error(
                        "read_file",
                        "startLine $from is past the end of the file ($totalLines lines)."
                    )
                }
                val cappedTo = minOf(to, totalLines)
                val window = allLines.subList(from - 1, cappedTo)
                val overLineLimit = window.size > limits.maxFileReadLines
                val keptLines = if (overLineLimit) window.take(limits.maxFileReadLines) else window
                val omittedLines = window.size - keptLines.size

                val body = SecretRedactor.redact(
                    OutputLimiter.truncate(keptLines.joinToString("\n"), limits.maxToolOutputChars)
                )
                val notice = if (omittedLines > 0) {
                    "\n${OutputLimiter.omittedMarker(bufferApprox(keptLines, omittedLines))} ($omittedLines more lines)"
                } else ""

                tracker.record(context.taskId, file.path, document.contentHash)

                ToolResult.Success(
                    "read_file",
                    "${AgentLabels.FILE_CONTENT} ${relative(context, file)} " +
                        "(lines $from-$cappedTo of $totalLines):\n$body$notice",
                    mapOf(
                        "path" to relative(context, file),
                        "contentHash" to document.contentHash,
                        "totalLines" to totalLines.toString(),
                        "startLine" to from.toString(),
                        "endLine" to cappedTo.toString(),
                        "truncated" to (omittedLines > 0).toString()
                    )
                )
            }
        }

    private fun bufferApprox(kept: List<String>, omittedLines: Int): Int =
        kept.sumOf { it.length } + omittedLines * 20

    private fun suggestedRanges(totalLines: Int): String {
        val step = totalLines / 4
        return "startLine=1&endLine=$step or startLine=${step + 1}&endLine=${step * 2}"
    }

    private fun relative(context: ToolContext, file: File): String =
        runCatching { file.relativeTo(context.projectRoot).path }.getOrDefault(file.name)
}

/** write_file: atomically replaces a file's contents (approval required by default). */
class WriteFileTool : Tool {

    override val definition = ToolDefinition(
        name = "write_file",
        description = "Write the full contents of a text file inside the project, creating parent " +
            "directories if needed. Prefer apply_patch for targeted edits to existing files.",
        parameters = listOf(
            AIToolParameter("path", AIToolParameterType.STRING, "Project-relative file path"),
            AIToolParameter("content", AIToolParameterType.STRING, "Complete new file contents")
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.WRITE
    )

    override fun summarize(args: JsonObject) = "Modify ${FileToolSupport.stringArg(args, "path") ?: "?"}"

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            FileToolSupport.safe {
                val rawPath = FileToolSupport.stringArg(args, "path")
                    ?: return@safe ToolResult.Error("write_file", "A 'path' is required.")
                val content = FileToolSupport.stringArg(args, "content") ?: ""
                val file = PathSandbox.resolveModifiable(context.projectRoot, rawPath)

                if (file.isDirectory) {
                    return@safe ToolResult.Error("write_file", "$rawPath is a directory.")
                }
                val manager = EditorFileManager(context.projectRoot)
                file.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }

                val lineEnding = if (file.exists() && !manager.isBinaryFile(file)) {
                    LineEnding.detect(manager.readFile(file).content)
                } else {
                    LineEnding.LF
                }
                val document = manager.saveFile(file, content, lineEnding = lineEnding)
                FileReadTrackerHolder.tracker.record(context.taskId, file.path, document.contentHash)

                ToolResult.Success(
                    "write_file",
                    "${AgentLabels.FILE_CONTENT} Wrote ${document.fileSizeBytes} bytes to ${relative(context, file)}.",
                    mapOf(
                        "path" to relative(context, file),
                        "bytes" to document.fileSizeBytes.toString(),
                        "contentHash" to document.contentHash,
                        "lineEnding" to document.lineEnding.name
                    )
                )
            }
        }

    private fun relative(context: ToolContext, file: File): String =
        runCatching { file.relativeTo(context.projectRoot).path }.getOrDefault(file.name)
}

/** create_file: creates a new file and refuses to overwrite an existing one. */
class CreateFileTool : Tool {

    override val definition = ToolDefinition(
        name = "create_file",
        description = "Create a new text file inside the project. Fails if the file already exists, " +
            "so use write_file or apply_patch to change an existing file.",
        parameters = listOf(
            AIToolParameter("path", AIToolParameterType.STRING, "Project-relative path of the new file"),
            AIToolParameter("content", AIToolParameterType.STRING, "Initial file contents", required = false)
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.CREATE
    )

    override fun summarize(args: JsonObject) = "Create ${FileToolSupport.stringArg(args, "path") ?: "?"}"

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            FileToolSupport.safe {
                val rawPath = FileToolSupport.stringArg(args, "path")
                    ?: return@safe ToolResult.Error("create_file", "A 'path' is required.")
                val content = FileToolSupport.stringArg(args, "content") ?: ""
                val file = PathSandbox.resolveModifiable(context.projectRoot, rawPath)
                if (file.exists()) {
                    return@safe ToolResult.Error("create_file", "File already exists: $rawPath")
                }
                file.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }
                val manager = EditorFileManager(context.projectRoot)
                val document = manager.saveFile(file, content, lineEnding = LineEnding.LF)
                FileReadTrackerHolder.tracker.record(context.taskId, file.path, document.contentHash)
                ToolResult.Success(
                    "create_file",
                    "${AgentLabels.FILE_CONTENT} Created ${relative(context, file)} (${document.fileSizeBytes} bytes).",
                    mapOf("path" to relative(context, file), "contentHash" to document.contentHash)
                )
            }
        }

    private fun relative(context: ToolContext, file: File): String =
        runCatching { file.relativeTo(context.projectRoot).path }.getOrDefault(file.name)
}

/** delete_file: always requires explicit approval; never deletes the project root. */
class DeleteFileTool : Tool {

    override val definition = ToolDefinition(
        name = "delete_file",
        description = "Delete a file or directory inside the project. Requires explicit user " +
            "approval every time. The project root cannot be deleted.",
        parameters = listOf(
            AIToolParameter("path", AIToolParameterType.STRING, "Project-relative path to delete")
        ),
        riskLevel = ToolRiskLevel.HIGH,
        permission = ToolPermission.ALWAYS_ASK,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.DELETE,
        destructive = true
    )

    override fun summarize(args: JsonObject) = "Delete ${FileToolSupport.stringArg(args, "path") ?: "?"}"

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            FileToolSupport.safe {
                val rawPath = FileToolSupport.stringArg(args, "path")
                    ?: return@safe ToolResult.Error("delete_file", "A 'path' is required.")
                val file = PathSandbox.resolveModifiable(context.projectRoot, rawPath)
                if (!file.exists()) {
                    return@safe ToolResult.Error("delete_file", "Path not found: $rawPath")
                }
                val fileCount = if (file.isDirectory) file.walkTopDown().count { it.isFile } else 1
                val manager = EditorFileManager(context.projectRoot)
                val (filesDeleted, foldersDeleted) = manager.delete(file)
                FileReadTrackerHolder.tracker.clearTask(context.taskId)
                ToolResult.Success(
                    "delete_file",
                    "${AgentLabels.FILE_CONTENT} Deleted $rawPath " +
                        "($filesDeleted file(s), $foldersDeleted folder(s); $fileCount affected).",
                    mapOf("filesDeleted" to filesDeleted.toString(), "foldersDeleted" to foldersDeleted.toString())
                )
            }
        }
}

/** list_directory: names, sizes and basic metadata only — never file contents. */
class ListDirectoryTool : Tool {

    override val definition = ToolDefinition(
        name = "list_directory",
        description = "List the entries of a project directory with sizes and types. " +
            "Never returns file contents.",
        parameters = listOf(
            AIToolParameter("path", AIToolParameterType.STRING, "Project-relative directory (default '.')", required = false)
        ),
        riskLevel = ToolRiskLevel.LOW,
        permission = ToolPermission.ALLOW,
        resourceType = ResourceType.PROJECT_DIRECTORY,
        action = SecurityAction.READ
    )

    override fun summarize(args: JsonObject) = "List ${FileToolSupport.stringArg(args, "path") ?: "."}"

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            FileToolSupport.safe {
                val rawPath = FileToolSupport.stringArg(args, "path")?.takeIf { it.isNotBlank() } ?: "."
                val dir = PathSandbox.resolve(context.projectRoot, rawPath)
                if (!dir.exists()) return@safe ToolResult.Error("list_directory", "Directory not found: $rawPath")
                if (!dir.isDirectory) return@safe ToolResult.Error("list_directory", "$rawPath is not a directory.")

                val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: emptyList()
                val visible = children.filter { it.name != PathSandbox.INTERNAL_DIR }
                val limited = visible.take(MAX_ENTRIES)

                val body = buildString {
                    appendLine("$rawPath/ (${visible.size} entries${if (visible.size > limited.size) ", showing ${limited.size}" else ""})")
                    limited.forEach { child ->
                        val type = if (child.isDirectory) "dir " else "file"
                        appendLine("  $type  ${child.name}${if (child.isDirectory) "/" else ""}  ${child.length()} bytes")
                    }
                    if (visible.size > limited.size) {
                        append(OutputLimiter.omittedMarker(visible.size - limited.size))
                    }
                }
                ToolResult.Success("list_directory", "${AgentLabels.FILE_CONTENT} $body")
            }
        }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}

/** create_directory: creates a directory tree inside the project. */
class CreateDirectoryTool : Tool {

    override val definition = ToolDefinition(
        name = "create_directory",
        description = "Create a directory (and any missing parents) inside the project.",
        parameters = listOf(
            AIToolParameter("path", AIToolParameterType.STRING, "Project-relative directory path")
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.PROJECT_DIRECTORY,
        action = SecurityAction.CREATE
    )

    override fun summarize(args: JsonObject) = "Create directory ${FileToolSupport.stringArg(args, "path") ?: "?"}"

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            FileToolSupport.safe {
                val rawPath = FileToolSupport.stringArg(args, "path")
                    ?: return@safe ToolResult.Error("create_directory", "A 'path' is required.")
                val dir = PathSandbox.resolveModifiable(context.projectRoot, rawPath)
                if (dir.exists()) {
                    return@safe if (dir.isDirectory) {
                        ToolResult.Success("create_directory", "${AgentLabels.FILE_CONTENT} Directory already exists: $rawPath")
                    } else {
                        ToolResult.Error("create_directory", "A file already exists at $rawPath")
                    }
                }
                if (!dir.mkdirs()) {
                    return@safe ToolResult.Error("create_directory", "Failed to create directory: $rawPath")
                }
                ToolResult.Success("create_directory", "${AgentLabels.FILE_CONTENT} Created directory $rawPath")
            }
        }
}

/** rename_file: renames a file or directory in place (same parent directory). */
class RenameFileTool : Tool {

    override val definition = ToolDefinition(
        name = "rename_file",
        description = "Rename a file or directory inside the project. The new name must be a " +
            "single path segment (no slashes); use write_file plus delete_file to move across directories.",
        parameters = listOf(
            AIToolParameter("path", AIToolParameterType.STRING, "Project-relative path to rename"),
            AIToolParameter("newName", AIToolParameterType.STRING, "New name (no path separators)")
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.RENAME,
        auxiliaryPathArguments = listOf("newName")
    )

    override fun summarize(args: JsonObject) =
        "Rename ${FileToolSupport.stringArg(args, "path") ?: "?"} to ${FileToolSupport.stringArg(args, "newName") ?: "?"}"

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            FileToolSupport.safe {
                val rawPath = FileToolSupport.stringArg(args, "path")
                    ?: return@safe ToolResult.Error("rename_file", "A 'path' is required.")
                val newName = FileToolSupport.stringArg(args, "newName")
                    ?: return@safe ToolResult.Error("rename_file", "A 'newName' is required.")
                if (newName.contains('/') || newName.contains('\\') || newName == ".." || newName.isBlank()) {
                    return@safe ToolResult.Error("rename_file", "'newName' must be a single name without path separators.")
                }
                val file = PathSandbox.resolveModifiable(context.projectRoot, rawPath)
                if (!file.exists()) return@safe ToolResult.Error("rename_file", "Path not found: $rawPath")
                val target = PathSandbox.resolveModifiable(context.projectRoot, "${file.parentFile?.name ?: ""}/$newName")
                val manager = EditorFileManager(context.projectRoot)
                val renamed = manager.rename(file, newName)
                ToolResult.Success(
                    "rename_file",
                    "${AgentLabels.FILE_CONTENT} Renamed $rawPath -> ${renamed.name}",
                    mapOf("from" to file.name, "to" to renamed.name, "target" to target.name)
                )
            }
        }
}

/**
 * apply_patch: targeted edits without rewriting a whole file.
 *
 * Safety (Phase 6 §43): the patch only applies when the `find` block matches EXACTLY once, and
 * when the file hash still matches what the agent last read (if it read it). Otherwise the file
 * is left untouched and the model is told to re-read it.
 */
class ApplyPatchTool(private val tracker: FileStateTracker = FileReadTrackerHolder.tracker) : Tool {

    override val definition = ToolDefinition(
        name = "apply_patch",
        description = "Apply a targeted edit to an existing text file by replacing an exact block of " +
            "existing text with new text. 'find' must match exactly once. The patch is rejected " +
            "without modifying anything if the file changed since it was read.",
        parameters = listOf(
            AIToolParameter("path", AIToolParameterType.STRING, "Project-relative file path"),
            AIToolParameter("find", AIToolParameterType.STRING, "Exact existing text to replace (must be unique)"),
            AIToolParameter("replace", AIToolParameterType.STRING, "Replacement text"),
            AIToolParameter("expectedHash", AIToolParameterType.STRING, "Content hash from read_file, for conflict detection", required = false)
        ),
        riskLevel = ToolRiskLevel.MEDIUM,
        permission = ToolPermission.ASK,
        resourceType = ResourceType.PROJECT_FILE,
        action = SecurityAction.WRITE
    )

    override fun summarize(args: JsonObject) = "Patch ${FileToolSupport.stringArg(args, "path") ?: "?"}"

    override suspend fun execute(call: AIToolCall, args: JsonObject, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            FileToolSupport.safe {
                val rawPath = FileToolSupport.stringArg(args, "path")
                    ?: return@safe ToolResult.Error("apply_patch", "A 'path' is required.")
                val find = FileToolSupport.stringArg(args, "find")
                    ?: return@safe ToolResult.Error("apply_patch", "A 'find' block is required.")
                val replace = FileToolSupport.stringArg(args, "replace") ?: ""
                if (find.isEmpty()) {
                    return@safe ToolResult.Error("apply_patch", "'find' must not be empty; use write_file to create content.")
                }

                val file = PathSandbox.resolveModifiable(context.projectRoot, rawPath)
                if (!file.exists()) return@safe ToolResult.Error("apply_patch", "File not found: $rawPath")
                if (file.isDirectory) return@safe ToolResult.Error("apply_patch", "$rawPath is a directory.")

                val manager = EditorFileManager(context.projectRoot)
                if (manager.isBinaryFile(file)) {
                    return@safe ToolResult.Error("apply_patch", "Cannot patch a binary file: $rawPath")
                }

                val document = manager.readFile(file)
                val expectedHash = FileToolSupport.stringArg(args, "expectedHash")
                    ?: tracker.hashFor(context.taskId, file.path)
                if (expectedHash != null && expectedHash != document.contentHash) {
                    return@safe ToolResult.Error(
                        "apply_patch",
                        "Patch could not be applied because the file changed since it was read. " +
                            "Re-read $rawPath and try again."
                    )
                }

                val occurrences = countOccurrences(document.content, find)
                if (occurrences == 0) {
                    return@safe ToolResult.Error(
                        "apply_patch",
                        "Patch could not be applied because the original text was not found in $rawPath. " +
                            "Re-read the file and use an exact block from it."
                    )
                }
                if (occurrences > 1) {
                    return@safe ToolResult.Error(
                        "apply_patch",
                        "Patch could not be applied because the block matches $occurrences times in $rawPath. " +
                            "Include more surrounding context to make it unique."
                    )
                }

                val updated = document.content.replace(find, replace)
                val saved = manager.saveFile(
                    file,
                    updated,
                    lineEnding = document.lineEnding,
                    hasBom = document.hasBom,
                    encoding = document.encoding
                )
                tracker.record(context.taskId, file.path, saved.contentHash)

                val deltaLines = saved.content.count { it == '\n' } - document.content.count { it == '\n' }
                ToolResult.Success(
                    "apply_patch",
                    "${AgentLabels.FILE_CONTENT} Patched $rawPath " +
                        "(1 replacement, ${if (deltaLines >= 0) "+" else ""}$deltaLines lines).",
                    mapOf(
                        "path" to rawPath,
                        "contentHash" to saved.contentHash,
                        "replacements" to "1",
                        "lineDelta" to deltaLines.toString()
                    )
                )
            }
        }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}

/** Process-wide tracker shared by read_file and the write/patch tools. */
internal object FileReadTrackerHolder {
    val tracker = FileStateTracker()
}
