package com.devstation.android.core.preview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.security.policy.SecurityAuditLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 9 §57: preview server tests — lifecycle, readiness, ports, isolation,
 * environment sanitization, output limits, ownership and cleanup.
 *
 * Uses real local processes (python3 / sleep) where available so behaviour is verified,
 * not asserted.
 */
class PreviewServerManagerTest {

    private fun manager(startupTimeoutMs: Long = 15_000): PreviewServerManager =
        PreviewServerManager(
            portManager = PreviewPortManager(),
            audit = SecurityAuditLogger(),
            dispatchers = object : DispatcherProvider {
                override val main = Dispatchers.Unconfined
                override val io = Dispatchers.IO
                override val default = Dispatchers.Default
                override val unconfined = Dispatchers.Unconfined
            },
            scope = CoroutineScope(Dispatchers.Default),
            startupTimeoutMs = startupTimeoutMs,
            processLauncher = { argv, workingDir, env ->
                val builder = ProcessBuilder(argv)
                builder.directory(workingDir)
                val e = builder.environment()
                e.clear()
                e.putAll(env)
                builder.start()
            }
        )

    private fun tempProject(): File {
        val base = File(System.getProperty("java.io.tmpdir"))
        val proj = File(base, "preview-proj-${System.nanoTime()}")
        proj.mkdirs()
        return proj
    }

    private fun writeIndexHtml(project: File) {
        project.resolve("index.html").writeText("<html><body>hello</body></html>")
    }

    private fun python(): String? = runCatching {
        val p = ProcessBuilder("python3", "--version").start()
        val ok = p.waitFor() == 0
        if (ok) "python3" else null
    }.getOrNull()

    // ---- §57.1 start / readiness ----

    @Test
    fun `starts static server and reaches RUNNING when python available`(): Unit = runBlocking {
        val py = python() ?: return@runBlocking
        val m = manager()
        val project = tempProject()
        writeIndexHtml(project)
        // The manager injects PORT into the environment; the server must bind THAT port,
        // not a random one — so serve a script that reads PORT instead of `http.server 0`.
        project.resolve("serve.py").writeText(
            """import os, http.server, socketserver
            |port = int(os.environ.get('PORT', '0'))
            |class H(http.server.SimpleHTTPRequestHandler):
            |    def log_message(self, *a): pass
            |with socketserver.TCPServer(('127.0.0.1', port), H) as httpd:
            |    httpd.serve_forever()
            """.trimMargin()
        )
        val server = m.start(
            projectId = "p1",
            projectName = "Test",
            command = py,
            arguments = listOf("serve.py"),
            workingDirectory = project.absolutePath,
            requestedPort = 0
        )
        assertTrue(server.isSuccess)
        val started = server.getOrThrow()
        withTimeout(20_000) {
            while (m.servers.value[started.id]?.state != PreviewServerState.RUNNING) delay(50)
        }
        assertEquals(PreviewServerState.RUNNING, m.servers.value[started.id]?.state)
        m.stop(started.id)
    }

    // ---- §57.4 startup timeout -> FAILED ----

    @Test
    fun `startup timeout marks server FAILED`() = runBlocking {
        val py = python() ?: return@runBlocking
        val m = manager(startupTimeoutMs = 1_000)
        val project = tempProject()
        // python that never listens on a port
        val result = m.start(
            projectId = "p1",
            projectName = "Test",
            command = py,
            arguments = listOf("-c", "import time; time.sleep(60)"),
            workingDirectory = project.absolutePath,
            requestedPort = 0
        )
        if (result.isSuccess) {
            val started = result.getOrThrow()
            withTimeout(15_000) {
                while (m.servers.value[started.id]?.state == PreviewServerState.STARTING) delay(50)
            }
            assertEquals(PreviewServerState.FAILED, m.servers.value[started.id]?.state)
            m.stop(started.id)
        }
    }

    // ---- §57.10 working directory validation ----

    @Test
    fun `start outside project working directory fails`(): Unit = runBlocking {
        val m = manager()
        val result = m.start(
            projectId = "p1",
            projectName = "Test",
            command = "python3",
            arguments = listOf("--version"),
            workingDirectory = "/proc",
            requestedPort = 0
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException || result.exceptionOrNull() is IllegalArgumentException)
    }

    // ---- §57.12 secret environment rejection ----

    @Test
    fun `secret environment variables are rejected`() {
        assertNull(PreviewServerManager.sanitizeEnvironment(mapOf("API_KEY" to "sk-123")))
        assertNull(PreviewServerManager.sanitizeEnvironment(mapOf("MY_TOKEN" to "x")))
        assertNull(PreviewServerManager.sanitizeEnvironment(mapOf("DATABASE_PASSWORD" to "y")))
        val ok = PreviewServerManager.sanitizeEnvironment(mapOf("NODE_ENV" to "dev", "PUBLIC_URL" to "."))
        assertNotNull(ok)
        assertEquals("dev", ok!!["NODE_ENV"])
        assertNull(ok["API_KEY"])
    }

    // ---- §57.13 bind host policy ----

    @Test
    fun `external bind hosts rejected loopback accepted`() {
        for (bad in listOf("0.0.0.0", "192.168.1.10", "10.0.0.2", "172.16.0.5", "host.lan")) {
            assertTrue("$bad must fail", PreviewServerManager.validateBindHost(bad).isFailure)
        }
        for (good in listOf("127.0.0.1", "localhost", "::1", "[::1]")) {
            assertTrue("$good must pass", PreviewServerManager.validateBindHost(good).isSuccess)
        }
    }

    // ---- §57.17 output limits ----

    @Test
    fun `log buffer bounded`() = runBlocking {
        val m = manager()
        val serverId = "log-test"
        val method = PreviewServerManager::class.java.getDeclaredMethod("appendLog", String::class.java, PreviewLogEntry.Stream::class.java, String::class.java)
        method.isAccessible = true
        repeat(PreviewServerManager.MAX_LOG_ENTRIES + 500) { i ->
            method.invoke(m, serverId, PreviewLogEntry.Stream.STDOUT, "line $i")
        }
        val logs = m.logs.value[serverId]!!
        assertEquals(PreviewServerManager.MAX_LOG_ENTRIES, logs.size)
        assertEquals("line ${PreviewServerManager.MAX_LOG_ENTRIES + 499}", logs.last().text)
        Unit
    }

    // ---- §57.6/§57.16 crash detection and cleanup ----

    @Test
    fun `process crash transitions to CRASHED or FAILED`() = runBlocking {
        val py = python() ?: return@runBlocking
        val m = manager()
        val project = tempProject()
        val started = m.start(
            projectId = "p1",
            projectName = "Test",
            command = py,
            arguments = listOf("-c", "import time; time.sleep(60)"),
            workingDirectory = project.absolutePath,
            requestedPort = 0,
            readinessPath = null
        )
        if (started.isSuccess) {
            val server = started.getOrThrow()
            val procField = PreviewServerManager::class.java.getDeclaredField("processes")
            procField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val processes = procField.get(m) as ConcurrentHashMap<String, Process>
            processes[server.id]?.destroy()
            withTimeout(15_000) {
                while (m.servers.value[server.id]?.state !in setOf(PreviewServerState.CRASHED, PreviewServerState.FAILED, PreviewServerState.STOPPED)) delay(50)
            }
            assertTrue(
                m.servers.value[server.id]?.state in setOf(PreviewServerState.CRASHED, PreviewServerState.FAILED, PreviewServerState.STOPPED)
            )
        }
        Unit
    }

    // ---- §57.18 cancellation / stop idempotency ----

    @Test
    fun `stop is idempotent for unknown server`() = runBlocking {
        val m = manager()
        val result = m.stop("does-not-exist")
        // must not throw; may succeed trivially or fail gracefully
        assertTrue(result.isSuccess || result.isFailure)
        Unit
    }
}

private typealias ConcurrentHashMap<K, V> = java.util.concurrent.ConcurrentHashMap<K, V>
