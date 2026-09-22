package com.devstation.android.core.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitConflictParserTest {

    @Test
    fun testParseConflictMarkers() {
        val content = """
            line 1
            <<<<<<< HEAD
            current feature implementation
            =======
            incoming feature implementation
            >>>>>>> feature-branch
            line 5
        """.trimIndent()

        val conflict = DefaultGitConflictParser.parseFile("src/App.kt", content)
        org.junit.Assert.assertNotNull(conflict)
        assertEquals("src/App.kt", conflict!!.filePath)
        assertEquals(1, conflict.chunks.size)

        val chunk = conflict.chunks[0]
        assertEquals("current feature implementation\n", chunk.currentContent)
        assertEquals("incoming feature implementation\n", chunk.incomingContent)
    }

    @Test
    fun testResolveConflictStrategies() {
        val content = """
            prefix line
            <<<<<<< HEAD
            our change
            =======
            their change
            >>>>>>> incoming
            suffix line
        """.trimIndent()

        val conflict = DefaultGitConflictParser.parseFile("file.txt", content)!!

        // Accept current
        val currentResolved = DefaultGitConflictParser.resolve(conflict, ConflictResolutionStrategy.ACCEPT_CURRENT)
        assertEquals("prefix line\nour change\nsuffix line", currentResolved)

        // Accept incoming
        val incomingResolved = DefaultGitConflictParser.resolve(conflict, ConflictResolutionStrategy.ACCEPT_INCOMING)
        assertEquals("prefix line\ntheir change\nsuffix line", incomingResolved)

        // Accept both
        val bothResolved = DefaultGitConflictParser.resolve(conflict, ConflictResolutionStrategy.ACCEPT_BOTH)
        assertEquals("prefix line\nour change\ntheir change\nsuffix line", bothResolved)
    }

    @Test
    fun testParseNoConflictsReturnsNull() {
        val content = "regular file\nno conflicts here\n"
        val conflict = DefaultGitConflictParser.parseFile("safe.txt", content)
        assertNull(conflict)
    }
}
