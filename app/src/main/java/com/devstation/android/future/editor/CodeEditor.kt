package com.devstation.android.future.editor

import kotlinx.coroutines.flow.StateFlow

/**
 * Extension contract for Phase 4: Code Editor.
 */
interface CodeEditor {
    val activeFilePath: StateFlow<String?>
    val isModified: StateFlow<Boolean>

    suspend fun openFile(filePath: String): Result<String>
    suspend fun saveFile(filePath: String, content: String): Result<Unit>
    suspend fun closeFile()
}
