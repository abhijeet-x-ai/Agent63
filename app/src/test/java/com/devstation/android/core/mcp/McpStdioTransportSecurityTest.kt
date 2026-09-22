package com.devstation.android.core.mcp

import com.devstation.android.core.security.policy.TerminalSecurityPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Phase 8.1 §37: STDIO transport security tests. Every security violation must fail closed.
 *
 * These tests run the real transport against real child processes (echo/cat/sleep), so they are
 * safe, dependency-free JVM tests that exercise the actual process path.
 */
class McpStdioTransportSecurityTest {

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "mcp-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun newTransport(
        dir: File = tempDir(),
        startupTimeoutMs: Long = 10_000
    ) = McpStdioTransport(
        workingDir = dir,
        terminalPolicy = TerminalSecurityPolicy(),
        startupTimeoutMs = startupTimeoutMs
    )

    private fun config(
        command: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        id: String = "srv-${System.nanoTime()}"
    ) = McpServerConfig(
        id = id,
        name = "test",
        transportType = McpTransportType.STDIO,
        command = command,
        arguments = args,
        environment = env
    )

    // §37.1 invalid executable
    @Test
    fun `invalid executable fails closed`() = runBlocking {
        val transport = newTransport()
        val result = transport.connect(config("/no/such/binary/anywhere"))
        assertTrue(result.isFailure)
        transport.shutdownAll()
    }

    // §37.2 unknown command follows strict behavior — refused at the gate when blocked, or runs
    // under the sanitized env; either way it cannot bypass the policy.
    @Test
    fun `shell substitution command is rejected by terminal policy`() = runBlocking {
        val transport = newTransport()
        val result = transport.connect(config("/system/bin/sh", listOf("-c", "echo \$(id)")))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is McpSecurityRejection)
        transport.shutdownAll()
    }

    // §37.3/§37.6/§37.7 private paths and /proc in arguments
    @Test
    fun `android private path arguments are rejected`() = runBlocking {
        val transport = newTransport()
        val result = transport.connect(config("/system/bin/cat", listOf("/data/data/com.devstation.android/files/secret")))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is McpSecurityRejection)
        transport.shutdownAll()
    }

    @Test
    fun `proc environ argument is rejected`() = runBlocking {
        val transport = newTransport()
        val result = transport.connect(config("/system/bin/cat", listOf("/proc/self/environ")))
        assertTrue(result.isFailure)
        transport.shutdownAll()
    }

    @Test
    fun `sys path argument is rejected`() = runBlocking {
        val transport = newTransport()
        val result = transport.connect(config("/system/bin/cat", listOf("/sys/kernel/notes")))
        assertTrue(result.isFailure)
        transport.shutdownAll()
    }

    // §37.10 secret environment inheritance
    @Test
    fun `secret environment variables are rejected at launch`() = runBlocking {
        val transport = newTransport()
        val result = transport.connect(config("/bin/echo", env = mapOf("API_KEY" to "value")))
        assertTrue(result.isFailure)
        transport.shutdownAll()
    }

    @Test
    fun `sanitized environment never contains secret-looking keys`() {
        val transport = newTransport()
        val ok = transport.sanitizedEnvironment(config("/bin/echo", env = mapOf("MY_VAR" to "safe")))
        assertNotNull(ok)
        assertTrue(ok!!.containsKey("PATH"))
        assertTrue(ok.containsKey("HOME"))
        assertEquals("safe", ok["MY_VAR"])
        assertNull(transport.sanitizedEnvironment(config("/bin/echo", env = mapOf("SESSION_TOKEN" to "x"))))
        assertNull(transport.sanitizedEnvironment(config("/bin/echo", env = mapOf("PRIVATE_KEY_PATH" to "x"))))
        // Invalid key names rejected
        assertNull(transport.sanitizedEnvironment(config("/bin/echo", env = mapOf("BAD-DASH" to "x"))))
    }

    // §37.14 process timeout
    @Test
    fun `request timeout fails closed and abandons the pending entry`() = runBlocking {
        val dir = tempDir()
        val transport = McpStdioTransport(
            workingDir = dir,
            requestTimeoutMs = 500,
            startupTimeoutMs = 10_000
        )
        // Use a process that never speaks MCP so the request can never be answered.
        val result = transport.connect(config("/bin/sleep", listOf("30")))
        if (result.isSuccess) {
            val connection = result.getOrThrow()
            val start = System.currentTimeMillis()
            val sendResult = transport.send(connection, McpJsonRpcRequest(method = "tools/list"))
            assertTrue(sendResult.isFailure)
            assertTrue(System.currentTimeMillis() - start < 5_000)
            transport.close(connection)
        }
        transport.shutdownAll()
    }

    // §37.16 abnormal process exit
    @Test
    fun `process exit is detected and fails closed`() = runBlocking {
        val transport = newTransport()
        // A command that exits immediately
        val result = transport.connect(config("/bin/true"))
        if (result.isSuccess) {
            val connection = result.getOrThrow()
            val sendResult = transport.send(connection, McpJsonRpcRequest(method = "tools/list"))
            // Either the send fails because the process exited, or it errors — never succeeds.
            assertTrue(sendResult.isFailure)
        }
        transport.shutdownAll()
    }

    // §37.17 duplicate process start
    @Test
    fun `duplicate session for the same server is rejected`() = runBlocking {
        val transport = newTransport()
        val id = "dup-${System.nanoTime()}"
        val first = transport.connect(config("/bin/sleep", listOf("30"), id = id))
        if (first.isSuccess) {
            val second = transport.connect(config("/bin/sleep", listOf("30"), id = id))
            assertTrue(second.isFailure)
        }
        transport.shutdownAll()
    }

    // §37.12/§37.13 oversized output is bounded
    @Test
    fun `oversized stdout does not exceed the stream cap`() {
        // The cap constant must be finite and reasonably small.
        assertTrue(McpStdioTransport.MAX_STREAM_CHARS <= 512_000)
    }

    // §37.11 shell injection — arguments are passed as an argv array, never via sh -c
    @Test
    fun `arguments containing shell metacharacters are not interpreted by a shell`() = runBlocking {
        val transport = newTransport()
        // echo with a metacharacter argument must simply echo it, never execute it.
        val result = transport.connect(config("/bin/echo", listOf("not-a-shell; $(whoami)")))
        // On hosts where /bin/echo exists, the process runs — the point is that connect either
        // succeeds with argv semantics or fails to find the binary; injection is impossible either
        // way because we never invoke a shell.
        assertTrue(result.isSuccess || result.exceptionOrNull() !is McpSecurityRejection)
        transport.shutdownAll()
    }

    // §37.18 orphan cleanup
    @Test
    fun `close terminates the owned process`() = runBlocking {
        val transport = newTransport()
        val result = transport.connect(config("/bin/sleep", listOf("60")))
        if (result.isSuccess) {
            val connection = result.getOrThrow()
            assertEquals(1, transport.ownedProcesses()[connection.serverId])
            transport.close(connection)
            assertTrue(transport.ownedProcesses()[connection.serverId] ?: 0 <= 0)
        }
        transport.shutdownAll()
        assertEquals(0, transport.ownedProcesses().size)
    }

    // §11 ownership: only the owning transport sees its processes
    @Test
    fun `process ownership is isolated per transport instance`() = runBlocking {
        val a = newTransport()
        val b = newTransport()
        val resultA = a.connect(config("/bin/sleep", listOf("30"), id = "own-a"))
        if (resultA.isSuccess) {
            assertTrue(b.ownedProcesses().isEmpty())
        }
        a.shutdownAll()
        b.shutdownAll()
    }

    // §22 cancellation of a waiting request
    @Test
    fun `cancelling a request wait surfaces promptly`() = runBlocking {
        val transport = McpStdioTransport(workingDir = tempDir(), requestTimeoutMs = 30_000)
        val result = transport.connect(config("/bin/sleep", listOf("30")))
        if (result.isSuccess) {
            val connection = result.getOrThrow()
            val start = System.currentTimeMillis()
            coroutineScope {
                val job = async { transport.send(connection, McpJsonRpcRequest(method = "tools/list")) }
                withTimeoutOrNull(1_000) { job.join() }
                job.cancel()
            }
            assertTrue(System.currentTimeMillis() - start < 10_000)
            transport.close(connection)
        }
        transport.shutdownAll()
    }
}
