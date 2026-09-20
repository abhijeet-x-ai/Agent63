package com.devstation.android.future.terminal

import com.devstation.android.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalManagerTest {

    private lateinit var tempDir: File
    private lateinit var manager: TerminalManager

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("terminal_test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `createSession creates session and activates it`() = runTest {
        manager = TerminalManager(
            dispatchers = DefaultDispatcherProvider(),
            scope = this,
            engineFactory = { _, _ -> FakeTerminalEngine() }
        )

        val session = manager.createSession(tempDir.absolutePath, title = "Test Shell")
        assertNotNull(session)
        assertEquals(1, manager.sessions.value.size)
        assertEquals(session.id, manager.activeSessionId.value)
        assertEquals("Test Shell", session.title)

        manager.closeAll()
    }

    @Test
    fun `multiple sessions can be switched and closed`() = runTest {
        manager = TerminalManager(
            dispatchers = DefaultDispatcherProvider(),
            scope = this,
            engineFactory = { _, _ -> FakeTerminalEngine() }
        )

        val s1 = manager.createSession(tempDir.absolutePath, title = "Session 1")
        val s2 = manager.createSession(tempDir.absolutePath, title = "Session 2")

        assertEquals(2, manager.sessions.value.size)
        assertEquals(s2.id, manager.activeSessionId.value)

        manager.selectSession(s1.id)
        assertEquals(s1.id, manager.activeSessionId.value)

        manager.closeSession(s1.id)
        assertEquals(1, manager.sessions.value.size)
        assertEquals(s2.id, manager.activeSessionId.value)

        manager.closeAll()
    }

    @Test
    fun `createSession with non-existent directory fails explicitly`() = runTest {
        manager = TerminalManager(
            dispatchers = DefaultDispatcherProvider(),
            scope = this,
            engineFactory = { _, _ -> FakeTerminalEngine() }
        )

        val fakePath = File(tempDir, "does_not_exist_xyz").absolutePath
        val result = runCatching {
            manager.createSession(fakePath)
        }

        assertTrue(result.isFailure)
        manager.closeAll()
    }
}
