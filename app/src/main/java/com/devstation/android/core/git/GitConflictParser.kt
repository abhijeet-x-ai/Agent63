package com.devstation.android.core.git

/**
 * Phase 10 §14: Git conflict parser & resolution helper.
 *
 * Parses conflict markers (<<<<<<<, =======, >>>>>>>) from conflicted files.
 * Provides functions to resolve chunks into chosen sides (Ours, Theirs, Base, or Manual).
 */
object GitConflictParser {

    fun parseConflicts(filePath: String, fileContent: String): GitConflict {
        val lines = fileContent.lines()
        val chunks = mutableListOf<GitConflictChunk>()

        var inConflict = false
        var startLine = 0
        var chunkIndex = 0

        val oursLines = mutableListOf<String>()
        val theirsLines = mutableListOf<String>()
        val baseLines = mutableListOf<String>()

        var section = ConflictSection.NONE

        for ((index, line) in lines.withIndex()) {
            val lineNum = index + 1

            if (line.startsWith("<<<<<<<")) {
                inConflict = true
                startLine = lineNum
                section = ConflictSection.OURS
                oursLines.clear()
                theirsLines.clear()
                baseLines.clear()
            } else if (line.startsWith("|||||||") && inConflict) {
                section = ConflictSection.BASE
            } else if (line.startsWith("=======") && inConflict) {
                section = ConflictSection.THEIRS
            } else if (line.startsWith(">>>>>>>") && inConflict) {
                inConflict = false
                section = ConflictSection.NONE

                chunks.add(
                    GitConflictChunk(
                        chunkIndex = chunkIndex++,
                        startLine = startLine,
                        endLine = lineNum,
                        oursContent = if (oursLines.isNotEmpty()) oursLines.joinToString("\n") + "\n" else "",
                        theirsContent = if (theirsLines.isNotEmpty()) theirsLines.joinToString("\n") + "\n" else "",
                        baseContent = if (baseLines.isNotEmpty()) baseLines.joinToString("\n") + "\n" else null
                    )
                )
            } else if (inConflict) {
                when (section) {
                    ConflictSection.OURS -> oursLines.add(line)
                    ConflictSection.BASE -> baseLines.add(line)
                    ConflictSection.THEIRS -> theirsLines.add(line)
                    ConflictSection.NONE -> {}
                }
            }
        }

        return GitConflict(filePath = filePath, chunks = chunks)
    }

    /**
     * Resolves all conflicts in [content] using the specified [resolutions] map (chunkIndex -> choice).
     */
    fun resolveAll(
        content: String,
        resolutions: Map<Int, ConflictResolutionChoice>,
        customEdits: Map<Int, String> = emptyMap()
    ): String {
        val lines = content.lines()
        val result = mutableListOf<String>()

        var inConflict = false
        var chunkIndex = 0

        val oursLines = mutableListOf<String>()
        val theirsLines = mutableListOf<String>()

        var section = ConflictSection.NONE

        for (line in lines) {
            if (line.startsWith("<<<<<<<")) {
                inConflict = true
                section = ConflictSection.OURS
                oursLines.clear()
                theirsLines.clear()
            } else if (line.startsWith("|||||||") && inConflict) {
                section = ConflictSection.BASE
            } else if (line.startsWith("=======") && inConflict) {
                section = ConflictSection.THEIRS
            } else if (line.startsWith(">>>>>>>") && inConflict) {
                inConflict = false
                section = ConflictSection.NONE

                val choice = resolutions[chunkIndex] ?: ConflictResolutionChoice.KEEP_OURS
                when (choice) {
                    ConflictResolutionChoice.KEEP_OURS -> result.addAll(oursLines)
                    ConflictResolutionChoice.KEEP_THEIRS -> result.addAll(theirsLines)
                    ConflictResolutionChoice.KEEP_BOTH -> {
                        result.addAll(oursLines)
                        result.addAll(theirsLines)
                    }
                    ConflictResolutionChoice.MANUAL -> {
                        val manual = customEdits[chunkIndex] ?: oursLines.joinToString("\n")
                        if (manual.isNotEmpty()) result.add(manual)
                    }
                }
                chunkIndex++
            } else if (inConflict) {
                when (section) {
                    ConflictSection.OURS -> oursLines.add(line)
                    ConflictSection.THEIRS -> theirsLines.add(line)
                    else -> {}
                }
            } else {
                result.add(line)
            }
        }

        return result.joinToString("\n")
    }

    private enum class ConflictSection { NONE, OURS, BASE, THEIRS }
}

object DefaultGitConflictParser {
    private val rawContents = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun parseFile(filePath: String, fileContent: String): GitConflict? {
        val conflict = GitConflictParser.parseConflicts(filePath, fileContent)
        if (conflict.chunks.isEmpty()) return null
        rawContents[filePath] = fileContent
        return conflict
    }

    fun resolve(conflict: GitConflict, strategy: ConflictResolutionStrategy): String {
        val choice = when (strategy) {
            ConflictResolutionStrategy.ACCEPT_CURRENT -> ConflictResolutionChoice.KEEP_OURS
            ConflictResolutionStrategy.ACCEPT_INCOMING -> ConflictResolutionChoice.KEEP_THEIRS
            ConflictResolutionStrategy.ACCEPT_BOTH -> ConflictResolutionChoice.KEEP_BOTH
        }
        val resolutions = conflict.chunks.indices.associateWith { choice }
        val content = rawContents[conflict.filePath] ?: conflict.chunks.joinToString("\n") {
            when (choice) {
                ConflictResolutionChoice.KEEP_OURS -> it.oursContent
                ConflictResolutionChoice.KEEP_THEIRS -> it.theirsContent
                ConflictResolutionChoice.KEEP_BOTH -> it.oursContent + it.theirsContent
                ConflictResolutionChoice.MANUAL -> it.oursContent
            }
        }
        return GitConflictParser.resolveAll(content, resolutions)
    }
}
