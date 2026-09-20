package com.devstation.android.feature.editor.model

data class EditorDiagnostics(
    val filePath: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val encodingName: String,
    val hasBom: Boolean,
    val lineEnding: LineEnding,
    val language: EditorLanguage,
    val lineCount: Int,
    val cursorLine: Int,
    val cursorColumn: Int,
    val isModified: Boolean,
    val isBinary: Boolean,
    val lastModifiedTimestamp: Long
)
