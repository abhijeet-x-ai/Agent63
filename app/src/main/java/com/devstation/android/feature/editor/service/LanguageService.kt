package com.devstation.android.feature.editor.service

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
    HINT
}

data class DiagnosticItem(
    val line: Int,
    val startColumn: Int,
    val endColumn: Int,
    val message: String,
    val severity: DiagnosticSeverity
)

data class CompletionItem(
    val label: String,
    val insertText: String = label,
    val detail: String? = null,
    val documentation: String? = null
)

interface LanguageService {
    suspend fun getCompletions(filePath: String, line: Int, col: Int): List<CompletionItem>
    suspend fun getDiagnostics(filePath: String): List<DiagnosticItem>
    suspend fun getHoverInfo(filePath: String, line: Int, col: Int): String?
}

/**
 * Clean no-op implementation for Phase 4.
 * Future phases will connect Language Server Protocol (LSP) processes to this interface.
 */
class NoOpLanguageService : LanguageService {
    override suspend fun getCompletions(filePath: String, line: Int, col: Int): List<CompletionItem> = emptyList()
    override suspend fun getDiagnostics(filePath: String): List<DiagnosticItem> = emptyList()
    override suspend fun getHoverInfo(filePath: String, line: Int, col: Int): String? = null
}
