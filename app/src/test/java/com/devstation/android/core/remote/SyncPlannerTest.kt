package com.devstation.android.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {

    private fun local(path: String, size: Long = 10, mtime: Long = 10_000) =
        SyncFileInfo(path, size, mtime, isDirectory = false)

    private fun remote(path: String, size: Long = 10, mtime: Long = 10_000) =
        SyncFileInfo(path, size, mtime, isDirectory = false)

    @Test
    fun `new files are uploaded`() {
        val plan = SyncPlanner.plan(listOf(local("a.txt")), emptyList())
        assertEquals(1, plan.items.size)
        assertEquals(SyncItem.Reason.NEW, plan.items[0].reason)
        assertEquals(10L, plan.totalBytes)
        assertEquals(0, plan.skippedCount)
    }

    @Test
    fun `unchanged files are skipped within mtime tolerance`() {
        val plan = SyncPlanner.plan(
            listOf(local("a.txt", mtime = 11_500)),
            listOf(remote("a.txt", mtime = 10_000))
        )
        assertTrue(plan.items.isEmpty())
        assertEquals(1, plan.skippedCount)
    }

    @Test
    fun `size change and newer mtime trigger upload`() {
        val plan = SyncPlanner.plan(
            listOf(
                local("size.txt", size = 99),
                local("newer.txt", mtime = 20_000)
            ),
            listOf(
                remote("size.txt", size = 10),
                remote("newer.txt", mtime = 10_000)
            )
        )
        assertEquals(2, plan.items.size)
        val byPath = plan.items.associateBy { it.relativePath }
        assertEquals(SyncItem.Reason.SIZE_CHANGED, byPath["size.txt"]?.reason)
        assertEquals(SyncItem.Reason.NEWER, byPath["newer.txt"]?.reason)
    }

    @Test
    fun `older local file is not uploaded`() {
        val plan = SyncPlanner.plan(
            listOf(local("a.txt", mtime = 5_000)),
            listOf(remote("a.txt", mtime = 10_000))
        )
        assertTrue(plan.items.isEmpty())
        assertEquals(1, plan.skippedCount)
    }

    @Test
    fun `excluded segments are skipped and unsafe or oversized paths rejected`() {
        val plan = SyncPlanner.plan(
            listOf(
                local(".git/config"),
                local("app/build/output.apk"),
                local("../escape.txt"),
                local("big.bin", size = 100_000_000)
            ),
            emptyList(),
            maxFileBytes = 1_000_000
        )
        assertTrue(plan.items.isEmpty())
        assertEquals(2, plan.skippedCount)
        assertEquals(listOf("../escape.txt", "big.bin"), plan.rejectedPaths)
    }

    @Test
    fun `directories never appear in the plan`() {
        val plan = SyncPlanner.plan(
            listOf(SyncFileInfo("src", 0, 0, isDirectory = true), local("src/Main.kt")),
            emptyList()
        )
        assertEquals(listOf("src/Main.kt"), plan.items.map { it.relativePath })
    }

    @Test
    fun `normalizePath resolves dots and refuses escapes`() {
        assertEquals("a/b", SyncPlanner.normalizePath("a/./b"))
        assertEquals("b", SyncPlanner.normalizePath("a/../b"))
        assertNull(SyncPlanner.normalizePath("../a"))
        assertNull(SyncPlanner.normalizePath(""))
        assertNull(SyncPlanner.normalizePath("a/../../b"))
    }

    @Test
    fun `joinRemote builds absolute paths and refuses escapes`() {
        assertEquals("/srv/app/x/y.txt", SyncPlanner.joinRemote("/srv/app", "x/y.txt"))
        assertNull(SyncPlanner.joinRemote("/srv/app", "../secret"))
    }
}
