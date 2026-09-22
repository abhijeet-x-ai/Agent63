package com.devstation.android.core.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitBranchAndRemoteParserTest {

    @Test
    fun testParseBranches() {
        val output = """
            * main 83db48f [origin/main: ahead 1] Initial commit
              feature/phase10 a1b2c3d Add git support
              remotes/origin/HEAD -> origin/main
              remotes/origin/main 83db48f Initial commit
              remotes/origin/feature/phase10 a1b2c3d Add git support
        """.trimIndent()

        val branches = GitBranchParser.parse(output)
        assertEquals(4, branches.size) // remotes/origin/HEAD is filtered out

        val main = branches.find { it.name == "main" }!!
        assertTrue(main.isCurrent)
        assertFalse(main.isRemote)
        assertEquals("origin/main", main.trackingUpstream)

        val feature = branches.find { it.name == "feature/phase10" }!!
        assertFalse(feature.isCurrent)
        assertFalse(feature.isRemote)

        val remoteMain = branches.find { it.name == "origin/main" }!!
        assertFalse(remoteMain.isCurrent)
        assertTrue(remoteMain.isRemote)
    }

    @Test
    fun testParseRemotes() {
        val output = """
            origin  https://github.com/owner/repo.git (fetch)
            origin  https://github.com/owner/repo.git (push)
            upstream  https://github.com/upstream/repo.git (fetch)
            upstream  https://github.com/upstream/repo.git (push)
        """.trimIndent()

        val remotes = GitRemoteParser.parse(output)
        assertEquals(2, remotes.size)

        val origin = remotes.find { it.name == "origin" }!!
        assertEquals("https://github.com/owner/repo.git", origin.fetchUrl)
        assertEquals("https://github.com/owner/repo.git", origin.pushUrl)

        val upstream = remotes.find { it.name == "upstream" }!!
        assertEquals("https://github.com/upstream/repo.git", upstream.fetchUrl)
        assertEquals("https://github.com/upstream/repo.git", upstream.pushUrl)
    }
}
