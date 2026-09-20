package com.devstation.android.feature.editor.model

import java.io.File

data class EditorTab(
    val filePath: String,
    val id: String = filePath,
    val fileName: String = File(filePath).name,
    val language: EditorLanguage = EditorLanguage.fromFileName(fileName),
    val document: EditorDocument,
    val isModified: Boolean = false,
    val cursor: EditorCursor = EditorCursor.DEFAULT,
    val scrollLine: Int = 0
)
