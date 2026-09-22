package com.devstation.android.core.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitDiffParserTest {

    @Test
    fun testParseStandardUnifiedDiff() {
        val diffOutput = """
            diff --git a/file1.txt b/file1.txt
            index 83db48f..bf269f4 100644
            --- a/file1.txt
            +++ b/file1.txt
            @@ -1,3 +1,4 @@
             first line
            -second line
            +second line modified
             third line
            +fourth line added
        """.trimIndent()

        val diff = GitDiffParser.parse(diffOutput)

        assertEquals(1, diff.files.size)
        val file = diff.files[0]
        assertEquals("file1.txt", file.oldPath)
        assertEquals("file1.txt", file.newPath)
        assertFalse(file.isBinary)

        assertEquals(1, file.hunks.size)
        val hunk = file.hunks[0]
        assertEquals("@@ -1,3 +1,4 @@", hunk.header)

        assertEquals(5, hunk.lines.size)
        assertEquals(GitDiffLine.LineType.CONTEXT, hunk.lines[0].type)
        assertEquals("first line", hunk.lines[0].content)

        assertEquals(GitDiffLine.LineType.DELETION, hunk.lines[1].type)
        assertEquals("second line", hunk.lines[1].content)

        assertEquals(GitDiffLine.LineType.ADDITION, hunk.lines[2].type)
        assertEquals("second line modified", hunk.lines[2].content)

        assertEquals(GitDiffLine.LineType.CONTEXT, hunk.lines[3].type)
        assertEquals("third line", hunk.lines[3].content)

        assertEquals(GitDiffLine.LineType.ADDITION, hunk.lines[4].type)
        assertEquals("fourth line added", hunk.lines[4].content)
    }

    @Test
    fun testParseBinaryDiff() {
        val diffOutput = """
            diff --git a/logo.png b/logo.png
            index 83db48f..bf269f4 100644
            Binary files a/logo.png and b/logo.png differ
        """.trimIndent()

        val diff = GitDiffParser.parse(diffOutput)
        assertEquals(1, diff.files.size)
        val file = diff.files[0]
        assertEquals("logo.png", file.oldPath)
        assertEquals("logo.png", file.newPath)
        assertTrue(file.isBinary)
    }

    @Test
    fun testEmptyDiff() {
        val diff = GitDiffParser.parse("")
        assertEquals(0, diff.files.size)
        assertEquals("", diff.rawUnifiedDiff)
    }
}
