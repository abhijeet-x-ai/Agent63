package com.devstation.android.core.git

/**
 * Phase 10 §6: Machine-readable Git status parser.
 *
 * Parses output from `git status --porcelain=v1 -b -uall`.
 * Never parses human-formatted output; porcelain v1 is guaranteed stable across Git versions.
 */
object GitStatusParser {

    fun parse(output: String, securityPolicy: GitSecurityPolicy = GitSecurityPolicy()): GitStatus {
        val lines = output.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            return GitStatus(branch = "HEAD", isClean = true)
        }

        var branch = "HEAD"
        var upstream: String? = null
        var ahead = 0
        var behind = 0

        val staged = mutableListOf<GitFileStatus>()
        val unstaged = mutableListOf<GitFileStatus>()
        val untracked = mutableListOf<GitFileStatus>()
        val conflicted = mutableListOf<GitFileStatus>()

        for (line in lines) {
            if (line.startsWith("## ")) {
                val branchHeader = line.substring(3).trim()
                parseBranchHeader(branchHeader).let {
                    branch = it.branch
                    upstream = it.upstream
                    ahead = it.ahead
                    behind = it.behind
                }
                continue
            }

            if (line.length < 3) continue

            val x = line[0]
            val y = line[1]
            val pathPart = line.substring(3).trim().trim('\"')

            // Handle rename: "old -> new"
            val (originalPath, filePath) = if (pathPart.contains(" -> ")) {
                val parts = pathPart.split(" -> ", limit = 2)
                Pair(parts[0].trim('\"'), parts[1].trim('\"'))
            } else {
                Pair(null, pathPart)
            }

            // Phase 10 §5: Ignore path traversal or system directory injections
            if (isUnsafePath(filePath, securityPolicy) || (originalPath != null && isUnsafePath(originalPath, securityPolicy))) {
                continue
            }

            val isSensitive = securityPolicy.isSensitiveFile(filePath)

            // Conflict states: both modified/added/deleted
            if (isConflict(x, y)) {
                conflicted.add(
                    GitFileStatus(
                        path = filePath,
                        originalPath = originalPath,
                        status = FileDeltaStatus.CONFLICTED,
                        isStaged = false,
                        isSensitive = isSensitive
                    )
                )
                continue
            }

            // Untracked
            if (x == '?' && y == '?') {
                untracked.add(
                    GitFileStatus(
                        path = filePath,
                        status = FileDeltaStatus.UNTRACKED,
                        isStaged = false,
                        isSensitive = isSensitive
                    )
                )
                continue
            }

            // Ignored
            if (x == '!' && y == '!') {
                continue
            }

            // Staged changes (X is not space or ?)
            if (x != ' ' && x != '?') {
                val deltaStatus = when (x) {
                    'M' -> FileDeltaStatus.MODIFIED
                    'A' -> FileDeltaStatus.ADDED
                    'D' -> FileDeltaStatus.DELETED
                    'R' -> FileDeltaStatus.RENAMED
                    'C' -> FileDeltaStatus.COPIED
                    'T' -> FileDeltaStatus.TYPE_CHANGED
                    else -> FileDeltaStatus.MODIFIED
                }
                staged.add(
                    GitFileStatus(
                        path = filePath,
                        originalPath = originalPath,
                        status = deltaStatus,
                        isStaged = true,
                        isSensitive = isSensitive
                    )
                )
            }

            // Unstaged working tree changes (Y is not space or ?)
            if (y != ' ' && y != '?') {
                val deltaStatus = when (y) {
                    'M' -> FileDeltaStatus.MODIFIED
                    'D' -> FileDeltaStatus.DELETED
                    'T' -> FileDeltaStatus.TYPE_CHANGED
                    else -> FileDeltaStatus.MODIFIED
                }
                unstaged.add(
                    GitFileStatus(
                        path = filePath,
                        originalPath = originalPath,
                        status = deltaStatus,
                        isStaged = false,
                        isSensitive = isSensitive
                    )
                )
            }
        }

        val isClean = staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty() && conflicted.isEmpty()

        return GitStatus(
            branch = branch,
            upstreamBranch = upstream,
            aheadCount = ahead,
            behindCount = behind,
            isClean = isClean,
            staged = staged,
            unstaged = unstaged,
            untracked = untracked,
            conflicted = conflicted
        )
    }

    private fun isConflict(x: Char, y: Char): Boolean = when {
        x == 'U' || y == 'U' -> true
        x == 'A' && y == 'A' -> true
        x == 'D' && y == 'D' -> true
        else -> false
    }

    private data class BranchInfo(val branch: String, val upstream: String?, val ahead: Int, val behind: Int)

    private fun parseBranchHeader(header: String): BranchInfo {
        // e.g. "main...origin/main [ahead 1, behind 2]" or "HEAD (no branch)" or "main"
        if (header.contains("(no branch)") || header.startsWith("detached")) {
            return BranchInfo("HEAD (detached)", null, 0, 0)
        }

        var ahead = 0
        var behind = 0

        val bracketIndex = header.indexOf('[')
        val branchPart = if (bracketIndex != -1) header.substring(0, bracketIndex).trim() else header.trim()

        if (bracketIndex != -1) {
            val trackingInfo = header.substring(bracketIndex + 1).trimEnd(']', ' ')
            val aheadMatch = Regex("""ahead (\d+)""").find(trackingInfo)
            if (aheadMatch != null) {
                ahead = aheadMatch.groupValues[1].toIntOrNull() ?: 0
            }
            val behindMatch = Regex("""behind (\d+)""").find(trackingInfo)
            if (behindMatch != null) {
                behind = behindMatch.groupValues[1].toIntOrNull() ?: 0
            }
        }

        val dotsIndex = branchPart.indexOf("...")
        return if (dotsIndex != -1) {
            val local = branchPart.substring(0, dotsIndex).trim()
            val remote = branchPart.substring(dotsIndex + 3).trim()
            BranchInfo(local, remote, ahead, behind)
        } else {
            BranchInfo(branchPart, null, ahead, behind)
        }
    }

    private fun isUnsafePath(path: String, securityPolicy: GitSecurityPolicy): Boolean {
        val normalized = path.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.matches("""^[a-zA-Z]:[/\\].*""".toRegex())) return true
        if (normalized.contains("../") || normalized.endsWith("/..") || normalized == "..") return true
        if (securityPolicy.isSystemDirectory(normalized)) return true
        return false
    }
}
