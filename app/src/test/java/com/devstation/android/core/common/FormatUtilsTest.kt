package com.devstation.android.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatUtilsTest {

    @Test
    fun `formatBytes returns correct units`() {
        assertEquals("0 B", FormatUtils.formatBytes(0))
        assertEquals("500.0 B", FormatUtils.formatBytes(500))
        assertEquals("1.0 KB", FormatUtils.formatBytes(1024))
        assertEquals("1.5 MB", FormatUtils.formatBytes(1572864))
        assertEquals("2.0 GB", FormatUtils.formatBytes(2147483648))
    }

    @Test
    fun `formatRelativeTime handles recent timestamp`() {
        val now = System.currentTimeMillis()
        assertEquals("Just now", FormatUtils.formatRelativeTime(now - 1000))
        assertEquals("5m ago", FormatUtils.formatRelativeTime(now - (5 * 60 * 1000)))
        assertEquals("2h ago", FormatUtils.formatRelativeTime(now - (2 * 3600 * 1000)))
    }
}
