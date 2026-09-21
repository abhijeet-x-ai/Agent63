package com.devstation.android.core.agent

import com.devstation.android.core.agent.tools.ReadFileTool
import com.devstation.android.core.ai.AIToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Phase 6 §66: the sandbox must fail safely against the specific escapes an AI could attempt —
 * Android-private paths, credential files outside the project, and oversized requests.
 */
class AgentSandboxSecurityTest {

    private lateinit var root: File
    private lateinit var outside: File
    private val limits = AgentLoopLimits()

    private fun context(taskId: String = "task-1") = ToolContext(
        projectId = "p1",
        projectRoot = root,
        workingDirectory = root,
        agentId = "agent",
        taskId = taskId
    )

    private suspend fun read(args: JsonObject) =
        ReadFileTool(limits).execute(AIToolCall("c1", "read_file", args.toString()), args, context())

    @Before
    fun setUp() {
        root = createTempProject(mapOf("src/App.kt" to "fun main() {}\n"))
        outside = File(root.parentFile, "outside-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        deleteTempProject(root)
        deleteTempProject(outside)
    }

    @Test
    fun `android private and system paths are rejected`() {
        val forbidden = listOf(
            "/data/data/com.devstation.android/files/keys",
            "/data/user/0/com.devstation.android/shared_prefs/credentials.xml",
            "/data/misc/keystore/user_0/1000_USRCERT_ai_key",
            "/system/bin/sh",
            "/proc/self/environ"
        )
        forbidden.forEach { path ->
            assertThrows(PathSandbox.PathRejected::class.java) {
                PathSandbox.resolve(root, path)
            }
        }
    }

    @Test
    fun `credential material outside the project is unreachable`() = runBlocking {
        val creds = File(outside, "credentials.json")
        creds.writeText("""{"provider":"openai","api_key":"sk-outside-secret-value-123456"}""")
        val args = buildJsonObject { put("path", creds.absolutePath) }

        val result = read(args)
        assertTrue(result is ToolResult.Error)
        assertFalse(result.output.contains("sk-outside-secret-value"))
        assertEquals(
            """{"provider":"openai","api_key":"sk-outside-secret-value-123456"}""",
            creds.readText()
        )
    }

    @Test
    fun `traversal cannot reach an environment file`() = runBlocking {
        File(outside, ".env").writeText("OPENAI_API_KEY=sk-live-secret-abcdef123456\n")
        val relative = "../${outside.name}/.env"
        val args = buildJsonObject { put("path", relative) }

        val result = read(args)
        assertTrue(result is ToolResult.Error)
        assertFalse(result.output.contains("sk-live-secret"))
    }

    @Test
    fun `oversized files return metadata and a suggested range instead of content`() = runBlocking {
        val big = File(root, "big.txt")
        big.writeText(buildString { repeat(20_000) { append("line $it padding pad\n") } })

        val whole = read(buildJsonObject { put("path", "big.txt") })
        assertTrue(whole is ToolResult.Success)
        val success = whole as ToolResult.Success
        assertTrue(success.output.contains("is large"))
        assertTrue(success.output.contains("startLine"))
        assertFalse(success.output.contains("padding pad"))
        assertEquals("true", success.metadata["truncated"])

        val ranged = read(buildJsonObject { put("path", "big.txt"); put("startLine", 1); put("endLine", 5) })
        assertTrue(ranged is ToolResult.Success)
        assertTrue((ranged as ToolResult.Success).output.contains("lines 1-5"))
    }

    @Test
    fun `a file beyond the hard size cap is refused with guidance`() = runBlocking {
        val huge = File(root, "huge.bin")
        huge.outputStream().use { stream -> stream.write(ByteArray(1024)) }
        // Sparse-style growth: extend past the 8 MiB hard cap without writing 8 MiB of data.
        java.io.RandomAccessFile(huge, "rw").use { it.setLength(9L * 1024 * 1024) }

        val result = read(buildJsonObject { put("path", "huge.bin") })
        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).message.contains("too large"))
    }
}
