package com.devstation.android.feature.editor.model

enum class LineEnding(val displayName: String, val chars: String) {
    LF("LF", "\n"),
    CRLF("CRLF", "\r\n"),
    CR("CR", "\r");

    companion object {
        fun detect(content: String): LineEnding {
            return when {
                content.contains("\r\n") -> CRLF
                content.contains("\n") -> LF
                content.contains("\r") -> CR
                else -> LF
            }
        }
    }
}
