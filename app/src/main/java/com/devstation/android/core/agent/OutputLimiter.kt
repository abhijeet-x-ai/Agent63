package com.devstation.android.core.agent

/**
 * Bounds every piece of text that can enter the agent context.
 *
 * Truncation is always explicit — the model is told how much was dropped so it can request a
 * narrower range instead of assuming it saw everything.
 */
object OutputLimiter {

    private const val HEAD_RATIO = 0.6

    private const val MAX_ADJUST_STEPS = 8

    fun omittedMarker(omitted: Int): String = "[Output truncated. $omitted characters omitted.]"

    /**
     * Keep the head and the tail of [text] so both the beginning (often a command banner) and
     * the end (often the actual error) stay visible.
     */
    fun truncate(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.length <= maxChars) return text

        val room = maxChars - omittedMarker(0).length - 2
        if (room < 8) return text.take(maxChars)

        var headSize = (room * HEAD_RATIO).toInt().coerceAtLeast(1)
        var tailSize = room - headSize
        // The marker itself grows with the digit count of the omitted total, so shrink the tail
        // until the fully assembled string provably fits the budget (§31).
        repeat(MAX_ADJUST_STEPS) {
            val marker = omittedMarker((text.length - headSize - tailSize).coerceAtLeast(0))
            val overflow = headSize + tailSize + marker.length + 2 - maxChars
            if (overflow <= 0) {
                return buildString {
                    append(text.take(headSize))
                    append('\n')
                    append(marker)
                    append('\n')
                    append(text.takeLast(tailSize))
                }
            }
            tailSize -= overflow
            if (tailSize < 1) return text.take(maxChars)
        }
        return text.take(maxChars)
    }

    /** Trim a list of lines to at most [maxLines], appending an explicit omission notice. */
    fun truncateLines(lines: List<String>, maxLines: Int): List<String> {
        if (maxLines <= 0 || lines.size <= maxLines) return lines
        val kept = lines.take(maxLines)
        return kept + omittedMarker(lines.size - maxLines)
    }
}

/**
 * Result of a bounded read: either content, or metadata describing why content was withheld.
 * Large files return metadata + a range hint instead of forcing the whole file into context.
 */
data class BoundedFileRead(
    val content: String?,
    val totalLines: Int,
    val totalChars: Long,
    val startLine: Int,
    val endLine: Int,
    val truncated: Boolean,
    val notice: String? = null
)
