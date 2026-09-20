package com.devstation.android.feature.editor.model

data class HistorySnapshot(
    val content: String,
    val cursorOffset: Int,
    val timestamp: Long = System.currentTimeMillis()
)

class EditorHistory(
    private val maxSnapshots: Int = 100
) {
    private val undoStack = ArrayDeque<HistorySnapshot>()
    private val redoStack = ArrayDeque<HistorySnapshot>()
    private var lastSnapshotTime: Long = 0L

    val canUndo: Boolean
        get() = undoStack.size > 1

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    /**
     * Initializes history with the initial document state.
     */
    fun initialize(initialContent: String, cursorOffset: Int = 0) {
        undoStack.clear()
        redoStack.clear()
        undoStack.addLast(HistorySnapshot(initialContent, cursorOffset, System.currentTimeMillis()))
        lastSnapshotTime = System.currentTimeMillis()
    }

    /**
     * Pushes a new snapshot onto the undo stack with debouncing/word boundary rules.
     */
    fun pushSnapshot(content: String, cursorOffset: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (undoStack.isNotEmpty()) {
            val top = undoStack.last()
            if (top.content == content) {
                return
            }
            if (!force && (now - lastSnapshotTime < 800) && !isWordBoundary(content, cursorOffset)) {
                // Replace top snapshot to avoid flooding undo stack with individual character keystrokes
                undoStack.removeLast()
                undoStack.addLast(HistorySnapshot(content, cursorOffset, now))
                return
            }
        }

        if (undoStack.size >= maxSnapshots) {
            undoStack.removeFirst()
        }
        undoStack.addLast(HistorySnapshot(content, cursorOffset, now))
        lastSnapshotTime = now
        redoStack.clear()
    }

    private fun isWordBoundary(content: String, cursorOffset: Int): Boolean {
        if (cursorOffset <= 0 || cursorOffset > content.length) return false
        val char = content[cursorOffset - 1]
        return char.isWhitespace() || char in "(){}[]<>=;:\"'`,./\\|&*+-#!"
    }

    fun undo(currentContent: String, currentCursor: Int): HistorySnapshot? {
        if (!canUndo) return null
        val current = undoStack.removeLast()
        redoStack.addLast(HistorySnapshot(currentContent, currentCursor, System.currentTimeMillis()))
        return undoStack.last()
    }

    fun redo(): HistorySnapshot? {
        if (!canRedo) return null
        val snapshot = redoStack.removeLast()
        undoStack.addLast(snapshot)
        return snapshot
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastSnapshotTime = 0L
    }
}
