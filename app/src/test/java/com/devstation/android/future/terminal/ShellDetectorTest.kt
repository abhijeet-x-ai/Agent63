package com.devstation.android.future.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellDetectorTest {

    @Test
    fun `detectShell returns valid shell information`() {
        val info = ShellDetector.detectShell()

        assertNotNull(info)
        assertTrue(info.name.isNotBlank())
        assertTrue(info.path.isNotBlank())
        // Standard Android Java environment does not have native PTY driver in Phase 2
        assertFalse(info.ptySupported)
    }
}
