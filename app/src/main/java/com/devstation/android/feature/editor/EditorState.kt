package com.devstation.android.feature.editor

import com.devstation.android.feature.editor.model.EditorDiagnostics
import com.devstation.android.feature.editor.model.EditorSearchState
import com.devstation.android.feature.editor.model.EditorSettings
import com.devstation.android.feature.editor.model.EditorTab
import com.devstation.android.feature.editor.model.ProjectSearchState
import java.io.File

data class EditorUiState(
    val projectPath: String = "",
    val projectName: String = "",
    val tabs: List<EditorTab> = emptyList(),
    val activeTabId: String? = null,
    val searchState: EditorSearchState = EditorSearchState(),
    val projectSearchState: ProjectSearchState = ProjectSearchState(),
    val settings: EditorSettings = EditorSettings(),
    val diagnostics: EditorDiagnostics? = null,
    val pendingCloseTab: EditorTab? = null,
    val externalChangeNotice: EditorTab? = null,
    val largeFileNotice: File? = null,
    val recoveryPromptTab: Pair<File, String>? = null,
    val hasGitRepository: Boolean = false,
    val isGoToLineOpen: Boolean = false,
    val isDiagnosticsOpen: Boolean = false,
    val isSettingsOpen: Boolean = false,
    val userMessage: String? = null
) {
    val activeTab: EditorTab?
        get() = tabs.firstOrNull { it.id == activeTabId }

    val hasOpenTabs: Boolean
        get() = tabs.isNotEmpty()
}
