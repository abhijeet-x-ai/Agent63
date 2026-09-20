package com.devstation.android.feature.editor.model

data class EditorCursor(
    val line: Int = 1,
    val column: Int = 1,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0
) {
    val hasSelection: Boolean
        get() = selectionStart != selectionEnd

    companion object {
        val DEFAULT = EditorCursor()

        fun calculatePosition(text: String, cursorOffset: Int): EditorCursor {
            val safeOffset = cursorOffset.coerceIn(0, text.length)
            var line = 1
            var col = 1
            for (i in 0 until safeOffset) {
                if (text[i] == '\n') {
                    line++
                    col = 1
                } else {
                    col++
                }
            }
            return EditorCursor(
                line = line,
                column = col,
                selectionStart = safeOffset,
                selectionEnd = safeOffset
            )
        }
    }
}
