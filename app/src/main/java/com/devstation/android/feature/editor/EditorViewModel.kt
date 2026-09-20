package com.devstation.android.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.database.EditorSettingsDao
import com.devstation.android.core.database.EditorSettingsEntity
import com.devstation.android.core.database.RecentFileDao
import com.devstation.android.core.database.RecentFileEntity
import com.devstation.android.feature.editor.model.EditorCursor
import com.devstation.android.feature.editor.model.EditorDiagnostics
import com.devstation.android.feature.editor.model.EditorHistory
import com.devstation.android.feature.editor.model.EditorLanguage
import com.devstation.android.feature.editor.model.EditorSearchState
import com.devstation.android.feature.editor.model.EditorSettings
import com.devstation.android.feature.editor.model.EditorTab
import com.devstation.android.feature.editor.model.ProjectSearchState
import com.devstation.android.feature.editor.model.SearchMatch
import com.devstation.android.feature.editor.service.EditorFileManager
import com.devstation.android.feature.editor.service.ProjectSearchEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayDeque
import java.util.regex.Pattern

class EditorViewModel(
    private val projectRootDir: File,
    private val fileManager: EditorFileManager = EditorFileManager(projectRootDir),
    private val searchEngine: ProjectSearchEngine = ProjectSearchEngine(projectRootDir, fileManager),
    private val recentFileDao: RecentFileDao? = null,
    private val editorSettingsDao: EditorSettingsDao? = null,
    initialFilePath: String = "",
    initialLine: Int? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        EditorUiState(
            projectPath = projectRootDir.absolutePath,
            projectName = projectRootDir.name,
            hasGitRepository = File(projectRootDir, ".git").exists()
        )
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val histories = mutableMapOf<String, EditorHistory>()
    private val recentlyClosedTabs = ArrayDeque<EditorTab>()

    init {
        loadSettings()
        if (initialFilePath.isNotBlank()) {
            openFile(initialFilePath, initialLine)
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            editorSettingsDao?.getSettingsFlow()?.collect { entity ->
                if (entity != null) {
                    _uiState.update {
                        it.copy(
                            settings = it.settings.copy(
                                fontSizeSp = entity.fontSizeSp,
                                tabSize = entity.tabSize,
                                insertSpaces = entity.insertSpaces,
                                wordWrap = entity.wordWrap,
                                showLineNumbers = entity.showLineNumbers,
                                autoCloseBrackets = entity.autoCloseBrackets,
                                autoIndent = entity.autoIndent,
                                enableSyntaxHighlighting = entity.enableSyntaxHighlighting
                            )
                        )
                    }
                }
            }
        }
    }

    fun openFile(filePath: String, jumpToLine: Int? = null) {
        val file = File(filePath)
        if (!file.exists() || file.isDirectory) {
            _uiState.update { it.copy(userMessage = "File does not exist: ${file.name}") }
            return
        }

        // Check if tab is already open
        val existingTab = _uiState.value.tabs.firstOrNull { it.filePath == file.absolutePath }
        if (existingTab != null) {
            selectTab(existingTab.id)
            if (jumpToLine != null) {
                goToLine(jumpToLine)
            }
            return
        }

        // Check large file limits
        if (file.length() > _uiState.value.settings.maxFileThresholdBytes) {
            _uiState.update { it.copy(largeFileNotice = file) }
            return
        }

        viewModelScope.launch {
            try {
                // Check recovery snapshot
                if (fileManager.hasRecoverySnapshot(file)) {
                    val recovered = fileManager.getRecoverySnapshot(file)
                    if (recovered != null) {
                        _uiState.update { it.copy(recoveryPromptTab = Pair(file, recovered)) }
                        return@launch
                    }
                }

                val doc = fileManager.readFile(file)
                val tab = EditorTab(
                    filePath = doc.filePath,
                    fileName = file.name,
                    language = EditorLanguage.fromFileName(file.name),
                    document = doc,
                    cursor = if (jumpToLine != null) EditorCursor(line = jumpToLine, column = 1) else EditorCursor.DEFAULT
                )

                val history = EditorHistory()
                history.initialize(doc.content)
                histories[tab.id] = history

                _uiState.update { state ->
                    state.copy(
                        tabs = state.tabs + tab,
                        activeTabId = tab.id
                    )
                }

                recordRecentFile(file)
                if (jumpToLine != null) {
                    goToLine(jumpToLine)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(userMessage = "Could not open file: ${e.message}") }
            }
        }
    }

    private fun recordRecentFile(file: File) {
        viewModelScope.launch {
            try {
                recentFileDao?.insertOrUpdate(
                    RecentFileEntity(
                        filePath = file.absolutePath,
                        projectId = projectRootDir.name,
                        fileName = file.name,
                        lastOpenedAt = System.currentTimeMillis(),
                        lastEditedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                // Non-critical
            }
        }
    }

    fun selectTab(tabId: String) {
        _uiState.update { it.copy(activeTabId = tabId) }
        checkExternalModifications()
    }

    fun closeTab(tabId: String, force: Boolean = false) {
        val tab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.isModified && !force) {
            _uiState.update { it.copy(pendingCloseTab = tab) }
            return
        }

        histories.remove(tabId)
        fileManager.clearRecoverySnapshot(File(tab.filePath))
        recentlyClosedTabs.addLast(tab)
        if (recentlyClosedTabs.size > 20) recentlyClosedTabs.removeFirst()

        _uiState.update { state ->
            val remaining = state.tabs.filterNot { it.id == tabId }
            val nextActiveId = if (state.activeTabId == tabId) {
                remaining.lastOrNull()?.id
            } else {
                state.activeTabId
            }
            state.copy(
                tabs = remaining,
                activeTabId = nextActiveId,
                pendingCloseTab = null
            )
        }
    }

    fun closeOtherTabs(keepTabId: String) {
        val tabsToClose = _uiState.value.tabs.filterNot { it.id == keepTabId }
        for (tab in tabsToClose) {
            closeTab(tab.id, force = true)
        }
    }

    fun closeAllTabs() {
        val tabs = _uiState.value.tabs
        for (tab in tabs) {
            closeTab(tab.id, force = true)
        }
    }

    fun closeSavedTabs() {
        val savedTabs = _uiState.value.tabs.filterNot { it.isModified }
        for (tab in savedTabs) {
            closeTab(tab.id, force = true)
        }
    }

    fun reopenRecentlyClosed() {
        val tab = if (recentlyClosedTabs.isNotEmpty()) recentlyClosedTabs.removeLast() else null ?: return
        openFile(tab.filePath)
    }

    fun updateContent(newContent: String, cursorOffset: Int) {
        val currentTab = _uiState.value.activeTab ?: return
        val history = histories.getOrPut(currentTab.id) { EditorHistory().apply { initialize(currentTab.document.content) } }
        history.pushSnapshot(newContent, cursorOffset)

        val updatedCursor = EditorCursor.calculatePosition(newContent, cursorOffset)
        val updatedDoc = currentTab.document.copy(content = newContent)
        val isModified = newContent != currentTab.document.content

        // Save autosave recovery snapshot
        if (isModified) {
            fileManager.saveRecoverySnapshot(File(currentTab.filePath), newContent)
        }

        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == currentTab.id) {
                        tab.copy(
                            document = updatedDoc,
                            isModified = isModified,
                            cursor = updatedCursor
                        )
                    } else tab
                }
            )
        }
    }

    fun updateCursor(cursorOffset: Int) {
        val currentTab = _uiState.value.activeTab ?: return
        val updatedCursor = EditorCursor.calculatePosition(currentTab.document.content, cursorOffset)
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == currentTab.id) tab.copy(cursor = updatedCursor) else tab
                }
            )
        }
    }

    fun saveActiveFile() {
        val currentTab = _uiState.value.activeTab ?: return
        viewModelScope.launch {
            try {
                val savedDoc = fileManager.saveFile(
                    file = File(currentTab.filePath),
                    content = currentTab.document.content,
                    lineEnding = currentTab.document.lineEnding,
                    hasBom = currentTab.document.hasBom,
                    encoding = currentTab.document.encoding
                )
                _uiState.update { state ->
                    state.copy(
                        tabs = state.tabs.map { tab ->
                            if (tab.id == currentTab.id) {
                                tab.copy(document = savedDoc, isModified = false)
                            } else tab
                        },
                        userMessage = "Saved ${currentTab.fileName}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(userMessage = "Failed to save: ${e.message}") }
            }
        }
    }

    fun undo(): Pair<String, Int>? {
        val currentTab = _uiState.value.activeTab ?: return null
        val history = histories[currentTab.id] ?: return null
        val snapshot = history.undo(currentTab.document.content, currentTab.cursor.selectionStart) ?: return null
        updateContent(snapshot.content, snapshot.cursorOffset)
        return Pair(snapshot.content, snapshot.cursorOffset)
    }

    fun redo(): Pair<String, Int>? {
        val currentTab = _uiState.value.activeTab ?: return null
        val history = histories[currentTab.id] ?: return null
        val snapshot = history.redo() ?: return null
        updateContent(snapshot.content, snapshot.cursorOffset)
        return Pair(snapshot.content, snapshot.cursorOffset)
    }

    /**
     * Smart indentation on Enter.
     */
    fun handleSmartEnter(currentText: String, cursorOffset: Int): Pair<String, Int> {
        val safeOffset = cursorOffset.coerceIn(0, currentText.length)
        val textBeforeCursor = currentText.substring(0, safeOffset)
        val textAfterCursor = currentText.substring(safeOffset)

        // Find current line start
        val lastNewline = textBeforeCursor.lastIndexOf('\n')
        val currentLine = if (lastNewline != -1) textBeforeCursor.substring(lastNewline + 1) else textBeforeCursor

        // Determine current line indentation
        val indent = currentLine.takeWhile { it == ' ' || it == '\t' }

        val extraIndent = if (currentLine.trimEnd().endsWith("{") || currentLine.trimEnd().endsWith("(") || currentLine.trimEnd().endsWith("[")) {
            " ".repeat(_uiState.value.settings.tabSize)
        } else ""

        val newIndent = "\n" + indent + extraIndent
        val newText = textBeforeCursor + newIndent + textAfterCursor
        val newOffset = safeOffset + newIndent.length
        return Pair(newText, newOffset)
    }

    /**
     * Auto closing of brackets and quotes.
     */
    fun handleAutoClose(char: Char, currentText: String, cursorOffset: Int): Pair<String, Int>? {
        if (!_uiState.value.settings.autoCloseBrackets) return null
        val closingChar = when (char) {
            '(' -> ')'
            '{' -> '}'
            '[' -> ']'
            '"' -> '"'
            '\'' -> '\''
            '`' -> '`'
            else -> return null
        }

        val safeOffset = cursorOffset.coerceIn(0, currentText.length)
        // If next character is already the closing character, don't duplicate
        if (safeOffset < currentText.length && currentText[safeOffset] == closingChar && char == closingChar) {
            return Pair(currentText, safeOffset + 1)
        }

        val newText = currentText.substring(0, safeOffset) + char + closingChar + currentText.substring(safeOffset)
        val newOffset = safeOffset + 1
        return Pair(newText, newOffset)
    }

    /**
     * Toggle line comments for current line or selection.
     */
    fun toggleComment() {
        val currentTab = _uiState.value.activeTab ?: return
        val prefix = currentTab.language.lineCommentPrefix ?: return
        val content = currentTab.document.content
        val cursor = currentTab.cursor
        val lines = content.split("\n").toMutableList()

        val targetLineIdx = (cursor.line - 1).coerceIn(0, lines.size - 1)
        val line = lines[targetLineIdx]

        if (line.trimStart().startsWith(prefix)) {
            // Uncomment
            val firstNonWs = line.indexOfFirst { !it.isWhitespace() }
            val uncommented = line.substring(0, firstNonWs) + line.substring(firstNonWs + prefix.length).trimStart()
            lines[targetLineIdx] = uncommented
        } else {
            // Comment
            val firstNonWs = line.indexOfFirst { !it.isWhitespace() }
            val commented = if (firstNonWs != -1) {
                line.substring(0, firstNonWs) + "$prefix " + line.substring(firstNonWs)
            } else {
                "$prefix $line"
            }
            lines[targetLineIdx] = commented
        }

        val updatedContent = lines.joinToString("\n")
        updateContent(updatedContent, cursor.selectionStart)
    }

    fun indent() {
        val currentTab = _uiState.value.activeTab ?: return
        val indentSpaces = " ".repeat(_uiState.value.settings.tabSize)
        val content = currentTab.document.content
        val offset = currentTab.cursor.selectionStart
        val newContent = content.substring(0, offset) + indentSpaces + content.substring(offset)
        updateContent(newContent, offset + indentSpaces.length)
    }

    fun outdent() {
        val currentTab = _uiState.value.activeTab ?: return
        val tabSize = _uiState.value.settings.tabSize
        val content = currentTab.document.content
        val offset = currentTab.cursor.selectionStart
        val lines = content.split("\n").toMutableList()
        val lineIdx = (currentTab.cursor.line - 1).coerceIn(0, lines.size - 1)
        val line = lines[lineIdx]

        var spacesToRemove = 0
        while (spacesToRemove < tabSize && spacesToRemove < line.length && line[spacesToRemove] == ' ') {
            spacesToRemove++
        }
        if (spacesToRemove > 0) {
            lines[lineIdx] = line.substring(spacesToRemove)
            val updated = lines.joinToString("\n")
            updateContent(updated, (offset - spacesToRemove).coerceAtLeast(0))
        }
    }

    fun goToLine(lineNumber: Int) {
        val currentTab = _uiState.value.activeTab ?: return
        val lines = currentTab.document.content.split("\n")
        val safeLine = lineNumber.coerceIn(1, lines.size)
        var offset = 0
        for (i in 0 until safeLine - 1) {
            offset += lines[i].length + 1
        }
        updateCursor(offset)
        _uiState.update { it.copy(isGoToLineOpen = false) }
    }

    // --- Search & Replace ---
    fun openSearch(replaceMode: Boolean = false) {
        _uiState.update {
            it.copy(
                searchState = it.searchState.copy(
                    isVisible = true,
                    isReplaceMode = replaceMode
                )
            )
        }
        performSearch()
    }

    fun closeSearch() {
        _uiState.update {
            it.copy(searchState = it.searchState.copy(isVisible = false))
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update {
            it.copy(searchState = it.searchState.copy(query = query))
        }
        performSearch()
    }

    fun updateReplaceText(text: String) {
        _uiState.update {
            it.copy(searchState = it.searchState.copy(replaceText = text))
        }
    }

    fun setSearchOptions(caseSensitive: Boolean, wholeWord: Boolean, regex: Boolean) {
        _uiState.update {
            it.copy(
                searchState = it.searchState.copy(
                    isCaseSensitive = caseSensitive,
                    isWholeWord = wholeWord,
                    isRegex = regex
                )
            )
        }
        performSearch()
    }

    private fun performSearch() {
        val query = _uiState.value.searchState.query
        val currentTab = _uiState.value.activeTab ?: return
        if (query.isEmpty()) {
            _uiState.update { it.copy(searchState = it.searchState.copy(matches = emptyList(), currentMatchIndex = -1)) }
            return
        }

        val content = currentTab.document.content
        val matches = mutableListOf<SearchMatch>()
        val isCase = _uiState.value.searchState.isCaseSensitive
        val isWhole = _uiState.value.searchState.isWholeWord
        val isRegex = _uiState.value.searchState.isRegex

        try {
            val pattern = when {
                isRegex -> Pattern.compile(query, if (isCase) 0 else Pattern.CASE_INSENSITIVE)
                isWhole -> Pattern.compile("\\b${Pattern.quote(query)}\\b", if (isCase) 0 else Pattern.CASE_INSENSITIVE)
                else -> Pattern.compile(Pattern.quote(query), if (isCase) 0 else Pattern.CASE_INSENSITIVE)
            }
            val matcher = pattern.matcher(content)
            var currentLine = 1
            var lineStart = 0
            while (matcher.find()) {
                val start = matcher.start()
                while (lineStart <= start && lineStart < content.length) {
                    val nl = content.indexOf('\n', lineStart)
                    if (nl != -1 && nl < start) {
                        currentLine++
                        lineStart = nl + 1
                    } else {
                        break
                    }
                }
                matches.add(SearchMatch(matcher.start(), matcher.end(), currentLine, matcher.group()))
            }
        } catch (e: Exception) {
            // Regex error
        }

        _uiState.update {
            it.copy(
                searchState = it.searchState.copy(
                    matches = matches,
                    currentMatchIndex = if (matches.isNotEmpty()) 0 else -1
                )
            )
        }
    }

    fun findNext() {
        val state = _uiState.value.searchState
        if (state.matches.isEmpty()) return
        val nextIdx = (state.currentMatchIndex + 1) % state.matches.size
        _uiState.update { it.copy(searchState = state.copy(currentMatchIndex = nextIdx)) }
        val match = state.matches[nextIdx]
        updateCursor(match.startOffset)
    }

    fun findPrevious() {
        val state = _uiState.value.searchState
        if (state.matches.isEmpty()) return
        val prevIdx = if (state.currentMatchIndex <= 0) state.matches.size - 1 else state.currentMatchIndex - 1
        _uiState.update { it.copy(searchState = state.copy(currentMatchIndex = prevIdx)) }
        val match = state.matches[prevIdx]
        updateCursor(match.startOffset)
    }

    fun replaceCurrent() {
        val state = _uiState.value.searchState
        val match = state.currentMatch ?: return
        val currentTab = _uiState.value.activeTab ?: return
        val content = currentTab.document.content
        val replaceWith = state.replaceText

        val newContent = content.substring(0, match.startOffset) + replaceWith + content.substring(match.endOffset)
        updateContent(newContent, match.startOffset + replaceWith.length)
        performSearch()
    }

    fun replaceAll() {
        val state = _uiState.value.searchState
        val currentTab = _uiState.value.activeTab ?: return
        if (state.matches.isEmpty()) return

        val count = state.matches.size
        val replaceWith = state.replaceText
        val content = currentTab.document.content
        val builder = StringBuilder()
        var lastEnd = 0
        for (match in state.matches) {
            builder.append(content, lastEnd, match.startOffset)
            builder.append(replaceWith)
            lastEnd = match.endOffset
        }
        builder.append(content, lastEnd, content.length)

        updateContent(builder.toString(), 0)
        _uiState.update { it.copy(userMessage = "Replaced $count occurrences") }
        performSearch()
    }

    // --- Project Search ---
    fun openProjectSearch() {
        _uiState.update { it.copy(projectSearchState = it.projectSearchState.copy(isVisible = true)) }
    }

    fun closeProjectSearch() {
        _uiState.update { it.copy(projectSearchState = it.projectSearchState.copy(isVisible = false)) }
    }

    fun runProjectSearch(query: String, isCaseSensitive: Boolean = false, isWholeWord: Boolean = false) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    projectSearchState = it.projectSearchState.copy(
                        query = query,
                        isSearching = true,
                        results = emptyList(),
                        searchSummary = "Searching across project..."
                    )
                )
            }
            val results = searchEngine.search(query, isCaseSensitive, isWholeWord)
            _uiState.update {
                it.copy(
                    projectSearchState = it.projectSearchState.copy(
                        isSearching = false,
                        results = results,
                        searchSummary = "${results.size} matches found"
                    )
                )
            }
        }
    }

    // --- External Changes ---
    fun checkExternalModifications() {
        val currentTab = _uiState.value.activeTab ?: return
        viewModelScope.launch {
            if (fileManager.hasExternalModification(currentTab.document)) {
                if (!currentTab.isModified) {
                    // Silently reload if no user edits
                    reloadFromDisk(currentTab.id)
                } else {
                    _uiState.update { it.copy(externalChangeNotice = currentTab) }
                }
            }
        }
    }

    fun reloadFromDisk(tabId: String) {
        val tab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        viewModelScope.launch {
            try {
                val doc = fileManager.readFile(File(tab.filePath))
                _uiState.update { state ->
                    state.copy(
                        tabs = state.tabs.map {
                            if (it.id == tabId) it.copy(document = doc, isModified = false) else it
                        },
                        externalChangeNotice = null,
                        userMessage = "Reloaded ${tab.fileName} from disk"
                    )
                }
                histories[tabId]?.initialize(doc.content)
            } catch (e: Exception) {
                _uiState.update { it.copy(userMessage = "Reload failed: ${e.message}") }
            }
        }
    }

    fun keepEditorVersion(tabId: String) {
        _uiState.update { it.copy(externalChangeNotice = null) }
        saveActiveFile()
    }

    // --- Recovery ---
    fun restoreRecovery(file: File, recoveredContent: String) {
        _uiState.update { it.copy(recoveryPromptTab = null) }
        val doc = fileManager.readFile(file).copy(content = recoveredContent)
        val tab = EditorTab(
            filePath = doc.filePath,
            fileName = file.name,
            language = EditorLanguage.fromFileName(file.name),
            document = doc,
            isModified = true
        )
        val history = EditorHistory().apply { initialize(recoveredContent) }
        histories[tab.id] = history
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs + tab,
                activeTabId = tab.id,
                userMessage = "Restored unsaved edits for ${file.name}"
            )
        }
    }

    fun discardRecovery(file: File) {
        fileManager.clearRecoverySnapshot(file)
        _uiState.update { it.copy(recoveryPromptTab = null) }
        openFile(file.absolutePath)
    }

    // --- Dialogs ---
    fun showGoToLine(show: Boolean) = _uiState.update { it.copy(isGoToLineOpen = show) }
    fun showDiagnostics(show: Boolean) {
        if (show) loadDiagnostics()
        _uiState.update { it.copy(isDiagnosticsOpen = show) }
    }
    fun showSettings(show: Boolean) = _uiState.update { it.copy(isSettingsOpen = show) }
    fun dismissLargeFileDialog() = _uiState.update { it.copy(largeFileNotice = null) }
    fun dismissPendingCloseDialog() = _uiState.update { it.copy(pendingCloseTab = null) }
    fun dismissUserMessage() = _uiState.update { it.copy(userMessage = null) }

    private fun loadDiagnostics() {
        val tab = _uiState.value.activeTab ?: return
        val file = File(tab.filePath)
        val diag = EditorDiagnostics(
            filePath = tab.filePath,
            fileName = tab.fileName,
            fileSizeBytes = if (file.exists()) file.length() else 0L,
            encodingName = tab.document.encoding.name(),
            hasBom = tab.document.hasBom,
            lineEnding = tab.document.lineEnding,
            language = tab.language,
            lineCount = tab.document.lineCount,
            cursorLine = tab.cursor.line,
            cursorColumn = tab.cursor.column,
            isModified = tab.isModified,
            isBinary = tab.document.isBinary,
            lastModifiedTimestamp = tab.document.lastModified
        )
        _uiState.update { it.copy(diagnostics = diag) }
    }

    companion object {
        fun provideFactory(
            projectRootDir: File,
            recentFileDao: RecentFileDao?,
            editorSettingsDao: EditorSettingsDao?,
            initialFilePath: String = "",
            initialLine: Int? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return EditorViewModel(
                    projectRootDir = projectRootDir,
                    recentFileDao = recentFileDao,
                    editorSettingsDao = editorSettingsDao,
                    initialFilePath = initialFilePath,
                    initialLine = initialLine
                ) as T
            }
        }
    }
}
