package com.devstation.android.core.agent

import com.devstation.android.core.agent.tools.AgentProcessRegistry
import com.devstation.android.core.agent.tools.BaseProcessCommandRunner
import com.devstation.android.core.agent.tools.CommandRunResult
import com.devstation.android.core.agent.tools.RunTerminalCommandTool
import com.devstation.android.core.ai.AIToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class TerminalToolTest {

    private lateinit var root: File
    private val limits = AgentLoopLimits()

    private fun context(taskId: String = "task-1", cancelled: Boolean = false) = ToolContext(
        projectId = "p1",
        projectRoot = root,
        workingDirectory = root,
        agentId = "agent",
        taskId = taskId,
        cancellationCheck = { cancelled }
    )

    private fun call(args: JsonObject) = AIToolCall("call-1", "run_terminal_command", args.toString())

    private fun tool(
        linuxRunner: FakeCommandRunner? = FakeCommandRunner("linux"),
        androidRunner: FakeCommandRunner = FakeCommandRunner("android"),
        linuxAvailable: Boolean = true,
        allowFallback: Boolean = true,
        registry: AgentProcessRegistry = AgentProcessRegistry()
    ) = RunTerminalCommandTool(
        linuxRunner = linuxRunner,
        androidRunner = androidRunner,
        linuxAvailable = { linuxAvailable },
        allowAndroidFallback = { allowFallback },
        registry = registry,
        limits = limits
    )

    @Before
    fun setUp() {
        root = createTempProject(mapOf("src/App.kt" to "x"))
    }

    @After
    fun tearDown() = deleteTempProject(root)

    @Test
    fun `prefers the linux runner when the runtime is installed`() = runBlocking {
        val linux = FakeCommandRunner("linux")
        val android = FakeCommandRunner("android")
        val a = buildJsonObject { put("command", "npm test") }
        val result = tool(linux, android).execute(call(a), a, context())
        assertTrue(result is ToolResult.Success)
        assertEquals(listOf("npm test"), linux.commands)
        assertTrue(android.commands.isEmpty())
        assertTrue((result as ToolResult.Success).output.contains("[TERMINAL OUTPUT]"))
        assertTrue(result.output.contains("environment: linux"))
    }

    @Test
    fun `refuses to run when linux is missing and the fallback is disabled`() = runBlocking {
        val a = buildJsonObject { put("command", "ls") }
        val result = tool(linuxAvailable = false, allowFallback = false).execute(call(a), a, context())
        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).message.contains("Linux runtime is not installed"))
    }

    @Test
    fun `uses the labelled fallback only when explicitly allowed`() = runBlocking {
        val android = FakeCommandRunner("android-fallback")
        val a = buildJsonObject { put("command", "ls") }
        val result = tool(linuxRunner = null, androidRunner = android, linuxAvailable = false).execute(call(a), a, context())
        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("environment: android-fallback"))
    }

    @Test
    fun `timeout produces a structured timeout result`() = runBlocking {
        val runner = FakeCommandRunner { CommandRunResult(null, "partial", true, false, false, 5_000) }
        val a = buildJsonObject { put("command", "npm install") }
        val result = tool(runner).execute(call(a), a, context())
        assertTrue(result is ToolResult.Timeout)
    }

    @Test
    fun `cancellation produces a cancelled result`() = runBlocking {
        val runner = FakeCommandRunner { CommandRunResult(null, "partial", false, true, false, 10) }
        val a = buildJsonObject { put("command", "sleep 60") }
        val result = tool(runner).execute(call(a), a, context())
        assertTrue(result is ToolResult.Cancelled)
    }

    @Test
    fun `non-zero exit becomes an error result carrying the output`() = runBlocking {
        val runner = FakeCommandRunner { CommandRunResult(2, "3 tests failed", false, false, false, 10) }
        val a = buildJsonObject { put("command", "npm test") }
        val result = tool(runner).execute(call(a), a, context())
        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).message.contains("3 tests failed"))
    }

    @Test
    fun `secret-looking output is redacted`() = runBlocking {
        val runner = FakeCommandRunner {
            CommandRunResult(0, "export API_KEY=supersecretvalue123", false, false, false, 1)
        }
        val a = buildJsonObject { put("command", "env") }
        val result = tool(runner).execute(call(a), a, context())
        assertTrue(result is ToolResult.Success)
        assertFalse((result as ToolResult.Success).output.contains("supersecretvalue123"))
    }

    @Test
    fun `working directory cannot escape the project`() = runBlocking {
        val a = buildJsonObject {
            put("command", "ls")
            put("workingDirectory", "../..")
        }
        val result = tool().execute(call(a), a, context())
        assertTrue(result is ToolResult.Error)
    }

    @Test
    fun `accepts a project-relative working directory`() = runBlocking {
        val runner = FakeCommandRunner("linux")
        val a = buildJsonObject {
            put("command", "ls")
            put("workingDirectory", "src")
        }
        assertTrue(tool(runner).execute(call(a), a, context()) is ToolResult.Success)
        assertEquals(listOf("ls"), runner.commands)
    }

    @Test
    fun `rejects an empty or oversized command`() = runBlocking {
        val empty = buildJsonObject { put("command", "   ") }
        assertTrue(tool().execute(call(empty), empty, context()) is ToolResult.Error)
        val huge = buildJsonObject { put("command", "a".repeat(9_000)) }
        assertTrue(tool().execute(call(huge), huge, context()) is ToolResult.Error)
    }

    @Test
    fun `timeout is clamped to a bounded range and never infinite`() = runBlocking {
        val runner = FakeCommandRunner("linux")
        val tooLong = buildJsonObject {
            put("command", "ls")
            put("timeoutMs", 10_000_000)
        }
        assertTrue(tool(runner).execute(call(tooLong), tooLong, context()) is ToolResult.Success)
        assertEquals(900_000L, runner.lastTimeoutMs)

        val tooShort = buildJsonObject {
            put("command", "ls")
            put("timeoutMs", 1)
        }
        assertTrue(tool(runner).execute(call(tooShort), tooShort, context()) is ToolResult.Success)
        assertEquals(5_000L, runner.lastTimeoutMs)
    }

    @Test
    fun `commands are owned by the running task`() = runBlocking {
        val runner = FakeCommandRunner("linux")
        val a = buildJsonObject { put("command", "ls") }
        tool(runner).execute(call(a), a, context(taskId = "task-42"))
        assertEquals("task-42", runner.lastTaskId)
    }

    @Test
    fun `cancelled task context is propagated to the runner`() = runBlocking {
        val runner = FakeCommandRunner("linux") { CommandRunResult(null, "", false, true, false, 1) }
        val a = buildJsonObject { put("command", "npm test") }
        assertTrue(tool(runner).execute(call(a), a, context(cancelled = true)) is ToolResult.Cancelled)
    }
}

class AgentProcessRegistryTest {

    private class TrackingProcess : Process() {
        var destroyed = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
    }

    @Test
    fun `tracks ownership per task and only kills owned processes`() {
        val registry = AgentProcessRegistry()
        val taskOne = TrackingProcess()
        val taskTwo = TrackingProcess()
        registry.register("task-1", taskOne)
        registry.register("task-2", taskTwo)

        assertEquals(1, registry.ownedCount("task-1"))
        assertEquals(1, registry.terminate("task-1"))
        assertTrue(taskOne.destroyed)
        assertFalse(taskTwo.destroyed)
        assertEquals(0, registry.ownedCount("task-1"))
        assertEquals(1, registry.ownedCount("task-2"))

        assertEquals(1, registry.terminateAll())
        assertTrue(taskTwo.destroyed)
    }

    @Test
    fun `unregistering an unknown process is safe`() {
        val registry = AgentProcessRegistry()
        val process = TrackingProcess()
        registry.unregister("nobody", process)
        assertEquals(0, registry.terminate("nobody"))
    }
}

/**
 * Exercises the real process-runner path (host shell) for output capture, exit codes, timeout
 * and termination. Skipped on platforms without `/bin/sh`.
 */
class BaseProcessCommandRunnerTest {

    private class HostShellRunner(registry: AgentProcessRegistry) : BaseProcessCommandRunner(registry) {
        override val environmentLabel = "host"
        override fun startProcess(command: String, workingDir: File): Process =
            ProcessBuilder("/bin/sh", "-c", command).directory(workingDir).start()
    }

    @Test
    fun `captures output and exit code from a real process`() = runBlocking {
        assumeTrue(File("/bin/sh").exists())
        val root = createTempProject()
        try {
            val runner = HostShellRunner(AgentProcessRegistry())
            val result = runner.run("echo hello-from-agent", root, 10_000, 10_000, "task-1") { false }
            assertEquals(0, result.exitCode)
            assertTrue(result.output.contains("hello-from-agent"))
            assertFalse(result.timedOut)
        } finally {
            deleteTempProject(root)
        }
    }

    @Test
    fun `terminates a process that exceeds its timeout`() = runBlocking {
        assumeTrue(File("/bin/sh").exists())
        val root = createTempProject()
        try {
            val registry = AgentProcessRegistry()
            val runner = HostShellRunner(registry)
            val result = runner.run("sleep 30", root, 700, 10_000, "task-1") { false }
            assertTrue(result.timedOut)
            assertEquals(0, registry.ownedCount("task-1"))
        } finally {
            deleteTempProject(root)
        }
    }

    @Test
    fun `terminate kills the process owned by a task`() = runBlocking {
        assumeTrue(File("/bin/sh").exists())
        val root = createTempProject()
        try {
            val registry = AgentProcessRegistry()
            val runner = HostShellRunner(registry)
            val result = runner.run("sleep 30", root, 10_000, 10_000, "task-1") { true }
            assertTrue(result.cancelled)
            assertEquals(0, registry.ownedCount("task-1"))
        } finally {
            deleteTempProject(root)
        }
    }

    @Test
    fun `output is bounded even when the process floods`() = runBlocking {
        assumeTrue(File("/bin/sh").exists())
        val root = createTempProject()
        try {
            val runner = HostShellRunner(AgentProcessRegistry())
            val result = runner.run(
                command = "i=0; while [ \$i -lt 200 ]; do echo 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'; i=\$((i+1)); done",
                workingDir = root,
                timeoutMs = 15_000,
                maxOutputChars = 500,
                taskId = "task-1",
                isCancelled = { false }
            )
            assertTrue(result.truncated)
            assertTrue(result.output.length <= 700)
        } finally {
            deleteTempProject(root)
        }
    }
}
