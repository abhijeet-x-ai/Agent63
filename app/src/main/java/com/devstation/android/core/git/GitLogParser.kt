package com.devstation.android.core.git

/**
 * Phase 10 §15: Machine-readable Git log parser.
 *
 * Uses ASCII Record Separator (\u001e) and Unit Separator (\u001f)
 * to parse formatted git log entries without being fooled by multi-line commit messages.
 */
object GitLogParser {

    const val RECORD_SEPARATOR = "\u001e"
    const val FIELD_SEPARATOR = "\u001f"

    /** Format string passed to `git log --format=...` */
    const val LOG_FORMAT = "%H%x1f%h%x1f%an%x1f%ae%x1f%at%x1f%s%x1f%b%x1f%P%x1e"

    fun parse(logOutput: String): List<GitCommit> {
        if (logOutput.isBlank()) return emptyList()

        val commits = mutableListOf<GitCommit>()
        val records = logOutput.split(RECORD_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

        for (record in records) {
            val fields = record.split(FIELD_SEPARATOR)
            if (fields.size < 7) continue

            val hash = fields[0].trim()
            val shortHash = fields[1].trim()
            val authorName = fields[2].trim()
            val authorEmail = fields[3].trim()
            val timestampSec = fields[4].trim().toLongOrNull() ?: 0L
            val subject = fields[5].trim()
            val body = fields[6].trim()
            val parents = fields.getOrNull(7)?.trim()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()

            commits.add(
                GitCommit(
                    hash = hash,
                    shortHash = shortHash,
                    authorName = authorName,
                    authorEmail = authorEmail,
                    timestamp = timestampSec * 1000L, // Convert to milliseconds
                    subject = subject,
                    body = body,
                    parentHashes = parents
                )
            )
        }

        return commits
    }
}
