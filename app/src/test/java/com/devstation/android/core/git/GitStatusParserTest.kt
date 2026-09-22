package com.devstation.android.core.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GitStatusParserTest {

    private val securityPolicy = GitSecurityPolicy()

    @Test
    fun testParseCleanWorkingTreeWithUpstreamTracking() {
        val output = "## main...origin/main [ahead 3, behind 1]\n"
        val status = GitStatusParser.parse(output, securityPolicy)

        assertEquals("main", status.branch)
        assertEquals(3, status.aheadCount)
        assertEquals(1, status.behindCount)
        assertTrue(status.isClean)
        assertEquals(0, status.stagedFiles.size)
        assertEquals(0, status.unstagedFiles.size)
        assertEquals(0, status.untrackedFiles.size)
        assertEquals(0, status.conflictedFiles.size)
    }

    @Test
    fun testParseDetachedHead() {
        val output = "## HEAD (no branch)\n"
        val status = GitStatusParser.parse(output, securityPolicy)

        assertEquals("HEAD (detached)", status.branch)
        assertTrue(status.isClean)
    }

    @Test
    fun testParseMixedFileChanges() {
        val output = """
            ## feature/login...origin/feature/login
            M  src/Login.kt
            A  src/Auth.kt
             M src/Utils.kt
             D README.md
            ?? new_file.txt
            UU merge_conflict.kt
        """.trimIndent()

        val status = GitStatusParser.parse(output, securityPolicy)

        assertEquals("feature/login", status.branch)
        assertFalse(status.isClean)

        // Staged
        assertEquals(2, status.stagedFiles.size)
        assertEquals("src/Login.kt", status.stagedFiles[0].path)
        assertEquals("M", status.stagedFiles[0].indexStatus)
        assertEquals("src/Auth.kt", status.stagedFiles[1].path)
        assertEquals("A", status.stagedFiles[1].indexStatus)

        // Unstaged
        assertEquals(2, status.unstagedFiles.size)
        assertEquals("src/Utils.kt", status.unstagedFiles[0].path)
        assertEquals("M", status.unstagedFiles[0].workTreeStatus)
        assertEquals("README.md", status.unstagedFiles[1].path)
        assertEquals("D", status.unstagedFiles[1].workTreeStatus)

        // Untracked
        assertEquals(1, status.untrackedFiles.size)
        assertEquals("new_file.txt", status.untrackedFiles[0].path)

        // Conflicts
        assertEquals(1, status.conflictedFiles.size)
        assertEquals("merge_conflict.kt", status.conflictedFiles[0])
    }

    @Test
    fun testIgnoresPathTraversalInOutput() {
        val output = """
            ## main
            M  ../../outside_secret.txt
            A  /etc/passwd
            M  src/Safe.kt
        """.trimIndent()

        val status = GitStatusParser.parse(output, securityPolicy)
        assertEquals(1, status.stagedFiles.size)
        assertEquals("src/Safe.kt", status.stagedFiles[0].path)
    }
}
