package com.devstation.android.core.git

import com.devstation.android.core.common.FormatUtils
import java.io.File

/**
 * Phase 10: Git domain models.
 * Git itself remains the single source of truth for repository state.
 */

/** Overall working tree and branch status of a repository. */
data class GitStatus(
    val branch: String,
    val upstreamBranch: String? = null,
    val aheadCount: Int = 0,
    val behindCount: Int = 0,
    val isClean: Boolean = true,
    val staged: List<GitFileStatus> = emptyList(),
    val unstaged: List<GitFileStatus> = emptyList(),
    val untracked: List<GitFileStatus> = emptyList(),
    val conflicted: List<GitFileStatus> = emptyList()
) {
    val totalChanged: Int get() = staged.size + unstaged.size + untracked.size + conflicted.size
    val stagedFiles: List<GitFileStatus> get() = staged
    val unstagedFiles: List<GitFileStatus> get() = unstaged
    val untrackedFiles: List<GitFileStatus> get() = untracked
    val conflictedFiles: List<String> get() = conflicted.map { it.path }
}

/** Status of a single file in the working tree or index. */
data class GitFileStatus(
    val path: String,
    val originalPath: String? = null,
    val status: FileDeltaStatus,
    val isStaged: Boolean,
    val isSensitive: Boolean = false
) {
    val indexStatus: String
        get() = if (isStaged) when (status) {
            FileDeltaStatus.ADDED -> "A"
            FileDeltaStatus.MODIFIED -> "M"
            FileDeltaStatus.DELETED -> "D"
            FileDeltaStatus.RENAMED -> "R"
            FileDeltaStatus.COPIED -> "C"
            FileDeltaStatus.CONFLICTED -> "U"
            else -> "M"
        } else " "

    val workTreeStatus: String
        get() = if (!isStaged) when (status) {
            FileDeltaStatus.UNTRACKED -> "?"
            FileDeltaStatus.MODIFIED -> "M"
            FileDeltaStatus.DELETED -> "D"
            FileDeltaStatus.CONFLICTED -> "U"
            else -> " "
        } else " "
}

enum class FileDeltaStatus {
    MODIFIED,
    ADDED,
    DELETED,
    RENAMED,
    COPIED,
    UNTRACKED,
    CONFLICTED,
    IGNORED,
    TYPE_CHANGED
}

/** One commit in git history. */
data class GitCommit(
    val hash: String,
    val shortHash: String,
    val authorName: String,
    val authorEmail: String,
    val timestamp: Long,
    val subject: String,
    val body: String = "",
    val parentHashes: List<String> = emptyList()
) {
    val relativeDate: String
        get() = FormatUtils.formatRelativeTime(if (timestamp > 9999999999L) timestamp else timestamp * 1000L)
}

/** Git branch details. */
data class GitBranch(
    val name: String,
    val isCurrent: Boolean,
    val isRemote: Boolean,
    val upstreamName: String? = null,
    val headCommitHash: String? = null
) {
    val trackingUpstream: String? get() = upstreamName
}

/** Git remote configuration. */
data class GitRemote(
    val name: String,
    val fetchUrl: String,
    val pushUrl: String
)

/** Complete diff summary for a repository or commit. */
data class GitDiff(
    val files: List<GitDiffFile> = emptyList(),
    val totalAdditions: Int = 0,
    val totalDeletions: Int = 0,
    val rawText: String = ""
) {
    val rawUnifiedDiff: String
        get() = rawText.ifBlank {
            files.joinToString("\n") { f ->
                "diff --git a/${f.oldPath} b/${f.newPath}\n" +
                f.hunks.joinToString("\n") { h ->
                    h.header + "\n" + h.lines.joinToString("\n") { l ->
                        val prefix = when (l.type) {
                            GitDiffLine.LineType.ADDITION -> "+"
                            GitDiffLine.LineType.DELETION -> "-"
                            else -> " "
                        }
                        "$prefix${l.content}"
                    }
                }
            }
        }
}

/** Diff of a single file. */
data class GitDiffFile(
    val oldPath: String,
    val newPath: String,
    val isBinary: Boolean = false,
    val additions: Int = 0,
    val deletions: Int = 0,
    val hunks: List<GitDiffHunk> = emptyList()
)

/** One hunk within a unified diff. */
data class GitDiffHunk(
    val header: String,
    val oldStartLine: Int,
    val oldLineCount: Int,
    val newStartLine: Int,
    val newLineCount: Int,
    val lines: List<GitDiffLine> = emptyList()
)

/** A single line in a diff hunk. */
data class GitDiffLine(
    val type: LineType,
    val content: String,
    val oldLineNo: Int? = null,
    val newLineNo: Int? = null
) {
    enum class LineType { ADDITION, DELETION, CONTEXT, HEADER }
}

/** Conflict information for a single file. */
data class GitConflict(
    val filePath: String,
    val chunks: List<GitConflictChunk> = emptyList()
)

/** A conflict section parsed from <<<<<<<, =======, >>>>>>> markers. */
data class GitConflictChunk(
    val chunkIndex: Int,
    val startLine: Int,
    val endLine: Int,
    val oursContent: String,
    val theirsContent: String,
    val baseContent: String? = null
) {
    val currentContent: String get() = oursContent
    val incomingContent: String get() = theirsContent
}

/** Availability status of Git in the development environment (§4). */
sealed class GitAvailability {
    data class Available(val version: String, val binaryPath: String) : GitAvailability()
    data class NotInstalled(val installHint: String = "apk add git") : GitAvailability()
    data object RuntimeUnavailable : GitAvailability()
    data class VersionError(val error: String) : GitAvailability()
    data class SecurityRejected(val reason: String) : GitAvailability()

    val isUsable: Boolean get() = this is Available
}

/** Result of executing a low-level Git command. */
data class GitCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/** Security policy for executing repository hooks (§10). */
enum class GitHookPolicy {
    DENY,
    ASK,
    ALLOW_FOR_REPOSITORY
}

/** Resolution choice for a merge conflict chunk. */
enum class ConflictResolutionChoice {
    KEEP_OURS,
    KEEP_THEIRS,
    KEEP_BOTH,
    MANUAL
}

/** Strategy for resolving conflicts across a file. */
enum class ConflictResolutionStrategy {
    ACCEPT_CURRENT,
    ACCEPT_INCOMING,
    ACCEPT_BOTH
}
