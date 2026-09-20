package com.devstation.android.feature.editor

import com.devstation.android.feature.editor.model.EditorHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorHistoryTest {

    @Test
    fun `history initializes and manages undo redo transitions`() {
        val history = EditorHistory(maxSnapshots = 10)
        history.initialize("Initial content", 0)

        assertFalse(history.canUndo)
        assertFalse(history.canRedo)

        history.pushSnapshot("Second content", 14, force = true)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        val undoResult = history.undo("Second content", 14)
        assertNotNull(undoResult)
        assertEquals("Initial content", undoResult!!.content)
        assertTrue(history.canRedo)

        val redoResult = history.redo()
        assertNotNull(redoResult)
        assertEquals("Second content", redoResult!!.content)
        assertFalse(history.canRedo)
    }

    @Test
    fun `redo stack is cleared when new snapshot is pushed`() {
        val history = EditorHistory()
        history.initialize("First", 0)
        history.pushSnapshot("Second", 6, force = true)

        history.undo("Second", 6)
        assertTrue(history.canRedo)

        // New edit occurs
        history.pushSnapshot("Third", 5, force = true)
        assertFalse(history.canRedo)
    }

    @Test
    fun `cannot undo past initial snapshot`() {
        val history = EditorHistory()
        history.initialize("Initial", 0)
        assertNull(history.undo("Initial", 0))
    }
}
