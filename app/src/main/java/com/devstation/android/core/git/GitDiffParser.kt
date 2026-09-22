package com.devstation.android.core.git

/**
 * Phase 10 §7: Unified diff parser.
 *
 * Parses `git diff` output into structured files, hunks, line-by-line changes,
 * additions/deletions counts, and binary file flags.
 */
object GitDiffParser {

    private val HUNK_HEADER_REGEX = Regex("""^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@(.*)$""")

    fun parse(diffOutput: String): GitDiff {
        if (diffOutput.isBlank()) return GitDiff()

        val files = mutableListOf<GitDiffFile>()
        val lines = diffOutput.lines()
        var i = 0

        var totalAdditions = 0
        var totalDeletions = 0

        while (i < lines.size) {
            val line = lines[i]

            // Look for "diff --git a/... b/..."
            if (line.startsWith("diff --git ")) {
                val headerParts = line.substring(11).split(" ")
                val oldPathRaw = headerParts.getOrNull(0)?.removePrefix("a/") ?: ""
                val newPathRaw = headerParts.getOrNull(1)?.removePrefix("b/") ?: oldPathRaw

                var oldPath = oldPathRaw
                var newPath = newPathRaw
                var isBinary = false
                val hunks = mutableListOf<GitDiffHunk>()
                var fileAdditions = 0
                var fileDeletions = 0

                i++
                // Process headers until @@ or next diff --git
                while (i < lines.size && !lines[i].startsWith("diff --git ") && !lines[i].startsWith("@@")) {
                    val subLine = lines[i]
                    if (subLine.startsWith("--- a/")) {
                        oldPath = subLine.substring(6).trim()
                    } else if (subLine.startsWith("+++ b/")) {
                        newPath = subLine.substring(6).trim()
                    } else if (subLine.contains("Binary files") && subLine.contains("differ")) {
                        isBinary = true
                    }
                    i++
                }

                // Process hunks for this file
                while (i < lines.size && !lines[i].startsWith("diff --git ")) {
                    val hunkLine = lines[i]
                    if (hunkLine.startsWith("@@")) {
                        val match = HUNK_HEADER_REGEX.find(hunkLine)
                        val oldStart = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                        val oldCount = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1
                        val newStart = match?.groupValues?.getOrNull(3)?.toIntOrNull() ?: 1
                        val newCount = match?.groupValues?.getOrNull(4)?.toIntOrNull() ?: 1

                        val hunkLines = mutableListOf<GitDiffLine>()
                        var currentOld = oldStart
                        var currentNew = newStart

                        i++
                        while (i < lines.size && !lines[i].startsWith("@@") && !lines[i].startsWith("diff --git ")) {
                            val lineContent = lines[i]
                            if (lineContent.startsWith("+")) {
                                fileAdditions++
                                totalAdditions++
                                hunkLines.add(
                                    GitDiffLine(
                                        type = GitDiffLine.LineType.ADDITION,
                                        content = lineContent.substring(1),
                                        newLineNo = currentNew++
                                    )
                                )
                            } else if (lineContent.startsWith("-")) {
                                fileDeletions++
                                totalDeletions++
                                hunkLines.add(
                                    GitDiffLine(
                                        type = GitDiffLine.LineType.DELETION,
                                        content = lineContent.substring(1),
                                        oldLineNo = currentOld++
                                    )
                                )
                            } else if (lineContent.startsWith(" ")) {
                                hunkLines.add(
                                    GitDiffLine(
                                        type = GitDiffLine.LineType.CONTEXT,
                                        content = lineContent.substring(1),
                                        oldLineNo = currentOld++,
                                        newLineNo = currentNew++
                                    )
                                )
                            } else if (lineContent.startsWith("\\ No newline at end of file")) {
                                // Ignore git metadata line
                            } else {
                                break
                            }
                            i++
                        }

                        hunks.add(
                            GitDiffHunk(
                                header = hunkLine,
                                oldStartLine = oldStart,
                                oldLineCount = oldCount,
                                newStartLine = newStart,
                                newLineCount = newCount,
                                lines = hunkLines
                            )
                        )
                    } else {
                        i++
                    }
                }

                files.add(
                    GitDiffFile(
                        oldPath = oldPath,
                        newPath = newPath,
                        isBinary = isBinary,
                        additions = fileAdditions,
                        deletions = fileDeletions,
                        hunks = hunks
                    )
                )
            } else {
                i++
            }
        }

        return GitDiff(
            files = files,
            totalAdditions = totalAdditions,
            totalDeletions = totalDeletions
        )
    }
}
