package com.devstation.android.core.agent

import com.devstation.android.core.agent.tools.ApplyPatchTool
import com.devstation.android.core.agent.tools.CreateDirectoryTool
import com.devstation.android.core.agent.tools.CreateFileTool
import com.devstation.android.core.agent.tools.DeleteFileTool
import com.devstation.android.core.agent.tools.EditorBridgeImpl
import com.devstation.android.core.agent.tools.FileStateTracker
import com.devstation.android.core.agent.tools.GetCurrentFileTool
import com.devstation.android.core.agent.tools.GetEditorStateTool
import com.devstation.android.core.agent.tools.ListDirectoryTool
import com.devstation.android.core.agent.tools.OpenFileTool
import com.devstation.android.core.agent.tools.ReadFileTool
import com.devstation.android.core.agent.tools.RenameFileTool
import com.devstation.android.core.agent.tools.SearchProjectTool
import com.devstation.android.core.agent.tools.WriteFileTool
import com.devstation.android.core.ai.AIToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FileToolsTest {

    private lateinit var root: File
    private val limits = AgentLoopLimits()

    private fun toolContext(taskId: String = "task-1") = ToolContext(
        projectId = "p1",
        projectRoot = root,
        workingDirectory = root,
        agentId = "agent",
        taskId = taskId
    )

    private fun call(name: String, args: JsonObject) = AIToolCall("call-1", name, args.toString())

    @Before
    fun setUp() {
        root = createTempProject(
            mapOf(
                "src/App.kt" to "fun main() {\n    println(\"hello\")\n}\n",
                "README.md" to "DevStation project\n"
            )
        )
    }

    @After
    fun tearDown() = deleteTempProject(root)

    @Test
    fun `read_file returns bounded labelled content`() = runBlocking {
        val result = ReadFileTool(limits).execute(
            call("read_file", buildJsonObject { put("path", "src/App.kt") }),
            buildJsonObject { put("path", "src/App.kt") },
            toolContext()
        )
        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("[FILE CONTENT]"))
        assertTrue(output.contains("println"))
        assertEquals("src/App.kt", result.metadata["path"])
    }

    @Test
    fun `read_file supports ranges and reports the window`() = runBlocking {
        val args = buildJsonObject {
            put("path", "src/App.kt")
            put("startLine", 2)
            put("endLine", 2)
        }
        val result = ReadFileTool(limits).execute(call("read_file", args), args, toolContext())
        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("println"))
        assertFalse(output.contains("fun main"))
        assertEquals("2", result.metadata["startLine"])
    }

    @Test
    fun `read_file refuses to escape the project`() = runBlocking {
        val args = buildJsonObject { put("path", "../../etc/passwd") }
        val result = ReadFileTool(limits).execute(call("read_file", args), args, toolContext())
        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).message.contains("outside the project"))
    }

    @Test
    fun `read_file refuses directories and missing files`() = runBlocking {
        val dirArgs = buildJsonObject { put("path", "src") }
        assertTrue(ReadFileTool(limits).execute(call("read_file", dirArgs), dirArgs, toolContext()) is ToolResult.Error)
        val missing = buildJsonObject { put("path", "nope.txt") }
        assertTrue(ReadFileTool(limits).execute(call("read_file", missing), missing, toolContext()) is ToolResult.Error)
    }

    @Test
    fun `write_file saves atomically and preserves content`() = runBlocking {
        val args = buildJsonObject {
            put("path", "src/App.kt")
            put("content", "fun main() = println(\"bye\")\n")
        }
        val result = WriteFileTool().execute(call("write_file", args), args, toolContext())
        assertTrue(result is ToolResult.Success)
        assertEquals("fun main() = println(\"bye\")\n", File(root, "src/App.kt").readText())
    }

    @Test
    fun `write_file creates missing parent directories`() = runBlocking {
        val args = buildJsonObject {
            put("path", "deep/nested/file.txt")
            put("content", "hi")
        }
        assertTrue(WriteFileTool().execute(call("write_file", args), args, toolContext()) is ToolResult.Success)
        assertTrue(File(root, "deep/nested/file.txt").exists())
    }

    @Test
    fun `write_file cannot modify the project root or internal state`() = runBlocking {
        val rootArgs = buildJsonObject { put("path", "."); put("content", "x") }
        assertTrue(WriteFileTool().execute(call("write_file", rootArgs), rootArgs, toolContext()) is ToolResult.Error)
        val internal = buildJsonObject { put("path", ".devstation/x"); put("content", "x") }
        assertTrue(WriteFileTool().execute(call("write_file", internal), internal, toolContext()) is ToolResult.Error)
    }

    @Test
    fun `create_file refuses to overwrite`() = runBlocking {
        val args = buildJsonObject { put("path", "README.md"); put("content", "new") }
        val result = CreateFileTool().execute(call("create_file", args), args, toolContext())
        assertTrue(result is ToolResult.Error)
        assertEquals("DevStation project\n", File(root, "README.md").readText())

        val fresh = buildJsonObject { put("path", "new.txt"); put("content", "hello") }
        assertTrue(CreateFileTool().execute(call("create_file", fresh), fresh, toolContext()) is ToolResult.Success)
        assertEquals("hello", File(root, "new.txt").readText())
    }

    @Test
    fun `delete_file removes files and reports counts`() = runBlocking {
        val args = buildJsonObject { put("path", "src/App.kt") }
        val result = DeleteFileTool().execute(call("delete_file", args), args, toolContext())
        assertTrue(result is ToolResult.Success)
        assertFalse(File(root, "src/App.kt").exists())
    }

    @Test
    fun `delete_file refuses the project root`() = runBlocking {
        val args = buildJsonObject { put("path", ".") }
        val result = DeleteFileTool().execute(call("delete_file", args), args, toolContext())
        assertTrue(result is ToolResult.Error)
        assertTrue(root.exists())
        assertTrue(File(root, "README.md").exists())
    }

    @Test
    fun `delete_file refuses traversal`() = runBlocking {
        val args = buildJsonObject { put("path", "../outside.txt") }
        assertTrue(DeleteFileTool().execute(call("delete_file", args), args, toolContext()) is ToolResult.Error)
    }

    @Test
    fun `list_directory lists entries without reading contents`() = runBlocking {
        val args = buildJsonObject { put("path", ".") }
        val result = ListDirectoryTool().execute(call("list_directory", args), args, toolContext())
        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("src"))
        assertTrue(output.contains("README.md"))
        assertFalse(output.contains("DevStation project"))
        // DevStation's internal directory is never listed.
        File(root, ".devstation").mkdirs()
        val again = ListDirectoryTool().execute(call("list_directory", args), args, toolContext())
        assertFalse((again as ToolResult.Success).output.contains(".devstation"))
    }

    @Test
    fun `create_directory creates nested directories and reports existing ones`() = runBlocking {
        val args = buildJsonObject { put("path", "a/b/c") }
        assertTrue(CreateDirectoryTool().execute(call("create_directory", args), args, toolContext()) is ToolResult.Success)
        assertTrue(File(root, "a/b/c").isDirectory)
        assertTrue(CreateDirectoryTool().execute(call("create_directory", args), args, toolContext()) is ToolResult.Success)
    }

    @Test
    fun `rename_file renames within the project and rejects separators`() = runBlocking {
        val args = buildJsonObject { put("path", "README.md"); put("newName", "README.txt") }
        assertTrue(RenameFileTool().execute(call("rename_file", args), args, toolContext()) is ToolResult.Success)
        assertTrue(File(root, "README.txt").exists())

        val bad = buildJsonObject { put("path", "README.txt"); put("newName", "../escape.txt") }
        assertTrue(RenameFileTool().execute(call("rename_file", bad), bad, toolContext()) is ToolResult.Error)
    }
}

class ApplyPatchToolTest {

    private lateinit var root: File
    private val tracker = FileStateTracker()

    private fun tool() = ApplyPatchTool(tracker)

    private fun context(taskId: String = "task-1") = ToolContext(
        projectId = "p1",
        projectRoot = root,
        workingDirectory = root,
        agentId = "agent",
        taskId = taskId
    )

    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(block)

    @Before
    fun setUp() {
        root = createTempProject(mapOf("src/App.kt" to "line one\nline two\nline three\n"))
    }

    @After
    fun tearDown() = deleteTempProject(root)

    @Test
    fun `applies an exact unique patch`() = runBlocking {
        val a = args {
            put("path", "src/App.kt")
            put("find", "line two")
            put("replace", "line TWO")
        }
        val result = tool().execute(AIToolCall("1", "apply_patch", a.toString()), a, context())
        assertTrue(result is ToolResult.Success)
        assertEquals("line one\nline TWO\nline three\n", File(root, "src/App.kt").readText())
    }

    @Test
    fun `refuses when the original text is not found`() = runBlocking {
        val a = args {
            put("path", "src/App.kt")
            put("find", "not present")
            put("replace", "x")
        }
        val result = tool().execute(AIToolCall("1", "apply_patch", a.toString()), a, context())
        assertTrue(result is ToolResult.Error)
        assertEquals("line one\nline two\nline three\n", File(root, "src/App.kt").readText())
    }

    @Test
    fun `refuses ambiguous patches`() = runBlocking {
        File(root, "dup.txt").writeText("same\nsame\n")
        val a = args {
            put("path", "dup.txt")
            put("find", "same")
            put("replace", "other")
        }
        val result = tool().execute(AIToolCall("1", "apply_patch", a.toString()), a, context())
        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).message.contains("matches 2 times"))
        assertEquals("same\nsame\n", File(root, "dup.txt").readText())
    }

    @Test
    fun `detects concurrent modification via the expected hash`() = runBlocking {
        val a = args {
            put("path", "src/App.kt")
            put("find", "line two")
            put("replace", "line TWO")
            put("expectedHash", "deadbeef")
        }
        val result = tool().execute(AIToolCall("1", "apply_patch", a.toString()), a, context())
        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).message.contains("file changed"))
        assertEquals("line one\nline two\nline three\n", File(root, "src/App.kt").readText())
    }

    @Test
    fun `detects modification after the agent read the file`() = runBlocking {
        val readArgs = buildJsonObject { put("path", "src/App.kt") }
        ReadFileTool(AgentLoopLimits(), tracker).execute(AIToolCall("r", "read_file", readArgs.toString()), readArgs, context())
        // Someone else (editor/terminal) changes the file after the read.
        File(root, "src/App.kt").writeText("changed by someone else\n")

        val a = args {
            put("path", "src/App.kt")
            put("find", "changed by someone else")
            put("replace", "patched")
        }
        val result = tool().execute(AIToolCall("1", "apply_patch", a.toString()), a, context())
        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).message.contains("file changed"))
        assertEquals("changed by someone else\n", File(root, "src/App.kt").readText())
    }

    @Test
    fun `malicious patch cannot escape the project`() = runBlocking {
        val a = args {
            put("path", "../../escape.txt")
            put("find", "x")
            put("replace", "y")
        }
        assertTrue(tool().execute(AIToolCall("1", "apply_patch", a.toString()), a, context()) is ToolResult.Error)
    }

    @Test
    fun `empty find block is rejected`() = runBlocking {
        val a = args {
            put("path", "src/App.kt")
            put("find", "")
            put("replace", "y")
        }
        assertTrue(tool().execute(AIToolCall("1", "apply_patch", a.toString()), a, context()) is ToolResult.Error)
    }
}

class SearchProjectToolTest {

    private lateinit var root: File
    private val limits = AgentLoopLimits()

    private fun context() = ToolContext("p1", root, root, "agent", "task-1")

    @Before
    fun setUp() {
        root = createTempProject(
            mapOf(
                "src/App.kt" to "val authProvider = 1\nval other = 2\n",
                "src/Util.kt" to "fun helper() {}\n",
                "node_modules/dep/index.js" to "authProvider = 1\n"
            )
        )
    }

    @After
    fun tearDown() = deleteTempProject(root)

    @Test
    fun `finds matches with file and line number`() = runBlocking {
        val a = buildJsonObject { put("query", "authProvider") }
        val result = SearchProjectTool(limits).execute(AIToolCall("1", "search_project", a.toString()), a, context())
        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("[SEARCH RESULT]"))
        assertTrue(output.contains("src/App.kt:1"))
        assertTrue(result.metadata["matchCount"]?.toIntOrNull() ?: 0 >= 1)
    }

    @Test
    fun `skips dependency directories`() = runBlocking {
        val a = buildJsonObject { put("query", "authProvider") }
        val result = SearchProjectTool(limits).execute(AIToolCall("1", "search_project", a.toString()), a, context())
        assertFalse((result as ToolResult.Success).output.contains("node_modules"))
    }

    @Test
    fun `supports file patterns`() = runBlocking {
        val a = buildJsonObject {
            put("query", "helper")
            put("filePattern", "*.kt")
        }
        val result = SearchProjectTool(limits).execute(AIToolCall("1", "search_project", a.toString()), a, context())
        assertTrue((result as ToolResult.Success).output.contains("Util.kt"))
    }

    @Test
    fun `rejects a search path outside the project`() = runBlocking {
        val a = buildJsonObject {
            put("query", "x")
            put("path", "../../")
        }
        assertTrue(SearchProjectTool(limits).execute(AIToolCall("1", "search_project", a.toString()), a, context()) is ToolResult.Error)
    }

    @Test
    fun `rejects blank queries`() = runBlocking {
        val a = buildJsonObject { put("query", "  ") }
        assertTrue(SearchProjectTool(limits).execute(AIToolCall("1", "search_project", a.toString()), a, context()) is ToolResult.Error)
    }

    @Test
    fun `reports no matches cleanly`() = runBlocking {
        val a = buildJsonObject { put("query", "definitely-not-present") }
        val result = SearchProjectTool(limits).execute(AIToolCall("1", "search_project", a.toString()), a, context())
        assertTrue(result is ToolResult.Success)
        assertEquals("0", result.metadata["matchCount"])
    }
}

class EditorToolsTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = createTempProject(mapOf("src/App.kt" to "content"))
    }

    @After
    fun tearDown() = deleteTempProject(root)

    private fun context() = ToolContext("p1", root, root, "agent", "task-1")

    @Test
    fun `open_file publishes a navigation request instead of reading the file`() = runBlocking {
        val bridge = EditorBridgeImpl()
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { bridge.openRequests.collect { received.add(it.filePath) } }

        val a = buildJsonObject { put("path", "src/App.kt") }
        val result = OpenFileTool(bridge).execute(AIToolCall("1", "open_file", a.toString()), a, context())
        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("[EDITOR STATE]"))
        assertEquals(listOf(File(root, "src/App.kt").absolutePath), received.toList())
        job.cancel()
    }

    @Test
    fun `open_file cannot request a file outside the project`() = runBlocking {
        val bridge = EditorBridgeImpl()
        val a = buildJsonObject { put("path", "../../escape.txt") }
        val result = OpenFileTool(bridge).execute(AIToolCall("1", "open_file", a.toString()), a, context())
        assertTrue(result is ToolResult.Error)
    }

    @Test
    fun `get_editor_state and get_current_file report empty state safely`() = runBlocking {
        val bridge = EditorBridgeImpl()
        val state = GetEditorStateTool(bridge).execute(AIToolCall("1", "get_editor_state", "{}"), JsonObject(emptyMap()), context())
        assertTrue(state is ToolResult.Success)
        assertTrue((state as ToolResult.Success).output.contains("none"))

        val current = GetCurrentFileTool(bridge).execute(AIToolCall("2", "get_current_file", "{}"), JsonObject(emptyMap()), context())
        assertTrue(current is ToolResult.Success)
    }

    @Test
    fun `get_current_file reports the reported active file`() = runBlocking {
        val bridge = EditorBridgeImpl()
        bridge.reportState("src/App.kt", listOf("src/App.kt"))
        val current = GetCurrentFileTool(bridge).execute(AIToolCall("1", "get_current_file", "{}"), JsonObject(emptyMap()), context())
        assertTrue((current as ToolResult.Success).output.contains("src/App.kt"))
    }
}
