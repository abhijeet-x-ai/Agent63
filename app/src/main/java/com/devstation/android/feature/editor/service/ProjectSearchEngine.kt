package com.devstation.android.feature.editor.service

import com.devstation.android.feature.editor.model.ProjectSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

class ProjectSearchEngine(
    private val projectRootDir: File,
    private val fileManager: EditorFileManager = EditorFileManager(projectRootDir)
) {
    suspend fun search(
        query: String,
        isCaseSensitive: Boolean = false,
        isWholeWord: Boolean = false,
        maxResults: Int = 200
    ): List<ProjectSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank() || !projectRootDir.exists()) {
            return@withContext emptyList()
        }

        val results = mutableListOf<ProjectSearchResult>()
        val rootCanonical = projectRootDir.canonicalFile

        val wordBoundaryRegex = if (isWholeWord) {
            val flags = if (isCaseSensitive) 0 else java.util.regex.Pattern.CASE_INSENSITIVE
            java.util.regex.Pattern.compile("\\b${java.util.regex.Pattern.quote(query)}\\b", flags)
        } else null

        projectRootDir.walk()
            .onEnter { dir ->
                // Skip common large/generated or internal directories
                val name = dir.name
                name !in EXCLUDED_DIRS && !name.startsWith(".")
            }
            .filter { it.isFile && it.length() <= MAX_SEARCHABLE_FILE_SIZE }
            .forEach { file ->
                currentCoroutineContext().ensureActive()

                if (results.size >= maxResults) {
                    return@withContext results
                }

                if (fileManager.isBinaryFile(file)) {
                    return@forEach
                }

                try {
                    val relativePath = file.canonicalFile.toRelativeString(rootCanonical)
                    var lineNum = 1
                    file.useLines(StandardCharsets.UTF_8) { lines ->
                        for (line in lines) {
                            if (results.size >= maxResults) break

                            if (wordBoundaryRegex != null) {
                                val matcher = wordBoundaryRegex.matcher(line)
                                while (matcher.find()) {
                                    results.add(
                                        ProjectSearchResult(
                                            filePath = file.absolutePath,
                                            relativePath = relativePath,
                                            lineNumber = lineNum,
                                            lineContent = line.trim(),
                                            matchStart = matcher.start(),
                                            matchLength = matcher.end() - matcher.start()
                                        )
                                    )
                                    if (results.size >= maxResults) break
                                }
                            } else {
                                val searchLine = if (isCaseSensitive) line else line.lowercase()
                                val searchQuery = if (isCaseSensitive) query else query.lowercase()
                                var index = searchLine.indexOf(searchQuery)
                                while (index >= 0) {
                                    results.add(
                                        ProjectSearchResult(
                                            filePath = file.absolutePath,
                                            relativePath = relativePath,
                                            lineNumber = lineNum,
                                            lineContent = line.trim(),
                                            matchStart = index,
                                            matchLength = query.length
                                        )
                                    )
                                    if (results.size >= maxResults) break
                                    index = searchLine.indexOf(searchQuery, index + searchQuery.length)
                                }
                            }
                            lineNum++
                        }
                    }
                } catch (e: Exception) {
                    // Ignore unreadable files
                }
            }

        results
    }

    companion object {
        private val EXCLUDED_DIRS = setOf(
            ".git", "node_modules", "build", "dist", ".devstation", ".gradle", ".idea", "out", "target"
        )
        private const val MAX_SEARCHABLE_FILE_SIZE = 5 * 1024 * 1024L // 5MB
    }
}
