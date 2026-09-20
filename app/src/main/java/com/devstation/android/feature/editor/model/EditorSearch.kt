package com.devstation.android.feature.editor.model

data class SearchMatch(
    val startOffset: Int,
    val endOffset: Int,
    val line: Int,
    val text: String
)

data class EditorSearchState(
    val isVisible: Boolean = false,
    val isReplaceMode: Boolean = false,
    val query: String = "",
    val replaceText: String = "",
    val isCaseSensitive: Boolean = false,
    val isWholeWord: Boolean = false,
    val isRegex: Boolean = false,
    val matches: List<SearchMatch> = emptyList(),
    val currentMatchIndex: Int = -1
) {
    val matchCount: Int
        get() = matches.size

    val currentMatch: SearchMatch?
        get() = if (currentMatchIndex in matches.indices) matches[currentMatchIndex] else null
}

data class ProjectSearchResult(
    val filePath: String,
    val relativePath: String,
    val lineNumber: Int,
    val lineContent: String,
    val matchStart: Int,
    val matchLength: Int
)

data class ProjectSearchState(
    val isVisible: Boolean = false,
    val query: String = "",
    val isCaseSensitive: Boolean = false,
    val isWholeWord: Boolean = false,
    val isSearching: Boolean = false,
    val results: List<ProjectSearchResult> = emptyList(),
    val searchSummary: String? = null
)
