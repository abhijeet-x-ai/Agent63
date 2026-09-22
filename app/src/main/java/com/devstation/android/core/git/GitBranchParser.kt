package com.devstation.android.core.git

/**
 * Phase 10 §11: Git branch parser.
 * Parses output from `git branch -a -v --no-color`.
 */
object GitBranchParser {

    fun parse(output: String): List<GitBranch> {
        val lines = output.lines().filter { it.isNotBlank() }
        val branches = mutableListOf<GitBranch>()

        for (line in lines) {
            val isCurrent = line.startsWith("* ")
            val cleanLine = line.removePrefix("* ").removePrefix("  ").trim()
            if (cleanLine.contains("->")) {
                // Ignore symbolic ref links like "remotes/origin/HEAD -> origin/main"
                continue
            }

            val parts = cleanLine.split(Regex("""\s+"""))
            if (parts.isEmpty()) continue

            val rawName = parts[0]
            val isRemote = rawName.startsWith("remotes/")
            val name = if (isRemote) rawName.removePrefix("remotes/") else rawName

            val commitHash = parts.getOrNull(1)

            // Upstream tracking branch if formatted like "[origin/main]"
            val upstreamMatch = Regex("""\[([^\]:]+)(?::[^\]]+)?\]""").find(cleanLine)
            val upstreamName = upstreamMatch?.groupValues?.getOrNull(1)

            branches.add(
                GitBranch(
                    name = name,
                    isCurrent = isCurrent,
                    isRemote = isRemote,
                    upstreamName = upstreamName,
                    headCommitHash = commitHash
                )
            )
        }

        return branches
    }
}
