package com.devstation.android.feature.editor.model

data class EditorSettings(
    val fontSizeSp: Float = 14f,
    val tabSize: Int = 4,
    val insertSpaces: Boolean = true,
    val wordWrap: Boolean = false,
    val showLineNumbers: Boolean = true,
    val autoCloseBrackets: Boolean = true,
    val autoIndent: Boolean = true,
    val enableSyntaxHighlighting: Boolean = true,
    val largeFileThresholdBytes: Long = 5 * 1024 * 1024L, // 5MB
    val maxFileThresholdBytes: Long = 20 * 1024 * 1024L // 20MB
)
