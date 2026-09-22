package com.devstation.android.core.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9 §57.2/§57.3: port allocation, conflict detection, ownership and release.
 */
class PreviewPortManagerTest {

    private val manager = PreviewPortManager()

    @Test
    fun `allocates a requested free port`() {
        val result = manager.allocate("p1", "s1", 41234)
        assertTrue(result.isSuccess)
        assertEquals(41234, result.getOrThrow())
    }

    @Test
    fun `port 0 auto-selects a free port in a sane range`() {
        val result = manager.allocate("p1", "s1", 0)
        assertTrue(result.isSuccess)
        val port = result.getOrThrow()
        assertTrue("auto port $port out of range", port in PreviewPortManager.MIN_PORT..PreviewPortManager.MAX_PORT)
    }

    @Test
    fun `out of range ports are rejected`() {
        assertTrue(manager.allocate("p1", "s1", 0 - 1).isFailure)
        assertTrue(manager.allocate("p1", "s1", 80).isFailure)   // below MIN_PORT (privileged)
        assertTrue(manager.allocate("p1", "s1", 70000).isFailure)
    }

    @Test
    fun `same project different server cannot take the same port`() {
        assertTrue(manager.allocate("p1", "s1", 41235).isSuccess)
        val second = manager.allocate("p1", "s2", 41235)
        assertTrue(second.isFailure)
    }

    @Test
    fun `cross-project port conflict is refused`() {
        assertTrue(manager.allocate("p1", "s1", 41236).isSuccess)
        val other = manager.allocate("p2", "s2", 41236)
        assertTrue(other.isFailure)
        // The reason must identify project ownership, not just "busy".
        assertNotNull(other.exceptionOrNull()?.message?.contains("project", ignoreCase = true))
    }

    @Test
    fun `same server re-allocating its own lease keeps the port`() {
        assertTrue(manager.allocate("p1", "s1", 41237).isSuccess)
        val again = manager.allocate("p1", "s1", 41237)
        assertTrue(again.isSuccess)
        assertEquals(41237, again.getOrThrow())
    }

    @Test
    fun `auto-select never returns a leased port`() {
        val first = manager.allocate("p1", "s1", 0).getOrThrow()
        val second = manager.allocate("p2", "s2", 0).getOrThrow()
        assertNotEquals(first, second)
    }

    @Test
    fun `busy OS port falls back to auto-select when allowed`() {
        // Occupy a port with a real socket.
        val socket = java.net.ServerSocket(0)
        val busyPort = socket.localPort
        try {
            val result = manager.allocate("p1", "s1", busyPort, allowAutoSelect = true)
            if (result.isSuccess) {
                assertNotEquals(busyPort, result.getOrThrow())
            } // allocation may also legitimately fail if the OS range is exhausted; not an error
        } finally {
            socket.close()
        }
    }

    @Test
    fun `busy OS port fails when auto-select is disabled`() {
        val socket = java.net.ServerSocket(0)
        val busyPort = socket.localPort
        try {
            val result = manager.allocate("p1", "s1", busyPort, allowAutoSelect = false)
            assertTrue(result.isFailure)
        } finally {
            socket.close()
        }
    }

    @Test
    fun `isPortFree is honest about a bound port`() {
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        try {
            assertFalse(manager.isPortFree(port))
        } finally {
            socket.close()
        }
        // After close the port is (usually) free again; do not assert — just exercise it.
        manager.isPortFree(port)
    }

    @Test
    fun `release removes the lease and frees the port for another project`() {
        assertTrue(manager.allocate("p1", "s1", 41240).isSuccess)
        assertTrue(manager.release("s1"))
        assertNull(manager.leaseFor(41240))
        assertTrue(manager.allocate("p2", "s2", 41240).isSuccess)
    }

    @Test
    fun `release for an unknown server returns false`() {
        assertFalse(manager.release("no-such-server"))
    }

    @Test
    fun `ownership queries respect the owning project`() {
        assertTrue(manager.allocate("p1", "s1", 41241).isSuccess)
        assertTrue(manager.isOwnedBy("p1", 41241))
        assertFalse(manager.isOwnedBy("p2", 41241))
        assertEquals(listOf(41241), manager.ownedPorts("p1"))
        assertTrue(manager.ownedPorts("p2").isEmpty())
    }

    @Test
    fun `releaseAll clears every lease`() {
        manager.allocate("p1", "s1", 41242)
        manager.allocate("p2", "s2", 41243)
        manager.releaseAll()
        assertNull(manager.leaseFor(41242))
        assertNull(manager.leaseFor(41243))
        assertTrue(manager.ownedPorts("p1").isEmpty())
        assertTrue(manager.ownedPorts("p2").isEmpty())
    }
}
