package com.devstation.android.feature.editor.model

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class EditorDocument(
    val filePath: String,
    val content: String,
    val encoding: Charset = StandardCharsets.UTF_8,
    val lineEnding: LineEnding = LineEnding.LF,
    val hasBom: Boolean = false,
    val fileSizeBytes: Long = 0L,
    val lastModified: Long = 0L,
    val contentHash: String = computeHash(content),
    val isBinary: Boolean = false
) {
    val lineCount: Int
        get() = if (content.isEmpty()) 1 else content.count { it == '\n' } + 1

    companion object {
        fun computeHash(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(text.toByteArray(StandardCharsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }
}
