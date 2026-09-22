package com.devstation.android.core.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitLogParserTest {

    @Test
    fun testParseStructuredLogOutput() {
        val rs = "\u001e"
        val us = "\u001f"

        val logOutput = buildString {
            append("a1b2c3d4e5f67890123456789012345678901234")
            append(us).append("a1b2c3d")
            append(us).append("Alice Developer")
            append(us).append("alice@devstation.local")
            append(us).append("1710000000")
            append(us).append("feat: implement git log parser")
            append(us).append("Detailed explanation of log parsing logic.")
            append(us).append("")
            append(rs)

            append("f6e5d4c3b2a10987654321098765432109876543")
            append(us).append("f6e5d4c")
            append(us).append("Bob Contributor")
            append(us).append("bob@devstation.local")
            append(us).append("1709990000")
            append(us).append("fix: prevent NPE on empty diff")
            append(us).append("")
            append(us).append("a1b2c3d4e5f67890123456789012345678901234")
            append(rs)
        }

        val commits = GitLogParser.parse(logOutput)
        assertEquals(2, commits.size)

        val first = commits[0]
        assertEquals("a1b2c3d4e5f67890123456789012345678901234", first.hash)
        assertEquals("a1b2c3d", first.shortHash)
        assertEquals("Alice Developer", first.authorName)
        assertEquals("alice@devstation.local", first.authorEmail)
        assertEquals(1710000000000L, first.timestamp)
        assertTrue(first.relativeDate.isNotBlank())
        assertEquals("feat: implement git log parser", first.subject)
        assertEquals("Detailed explanation of log parsing logic.", first.body)

        val second = commits[1]
        assertEquals("f6e5d4c3b2a10987654321098765432109876543", second.hash)
        assertEquals("f6e5d4c", second.shortHash)
        assertEquals("Bob Contributor", second.authorName)
        assertEquals("fix: prevent NPE on empty diff", second.subject)
        assertEquals("", second.body)
        assertEquals(listOf("a1b2c3d4e5f67890123456789012345678901234"), second.parentHashes)
    }

    @Test
    fun testParseEmptyLog() {
        val commits = GitLogParser.parse("")
        assertTrue(commits.isEmpty())
    }
}
