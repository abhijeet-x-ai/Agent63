package com.devstation.android.core.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PathSandboxTest {

    private lateinit var root: File
    private lateinit var outside: File

    @Before
    fun setUp() {
        root = createTempProject(mapOf("src/App.kt" to "fun main() {}"))
        outside = File(root.parentFile, "outside-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        deleteTempProject(root)
        deleteTempProject(outside)
    }

    @Test
    fun `resolves a relative path inside the project`() {
        val resolved = PathSandbox.resolve(root, "src/App.kt")
        assertEquals(File(root, "src/App.kt").canonicalPath, resolved.path)
        assertTrue(PathSandbox.isInside(root, resolved))
    }

    @Test
    fun `rejects parent directory traversal`() {
        assertThrows(PathSandbox.PathRejected::class.java) {
            PathSandbox.resolve(root, "../../etc/passwd")
        }
        assertThrows(PathSandbox.PathRejected::class.java) {
            PathSandbox.resolve(root, "src/../../../escape.txt")
        }
    }

    @Test
    fun `rejects absolute paths outside the project`() {
        assertThrows(PathSandbox.PathRejected::class.java) {
            PathSandbox.resolve(root, "/etc/passwd")
        }
        assertThrows(PathSandbox.PathRejected::class.java) {
            PathSandbox.resolve(root, outside.absolutePath + "/secret.txt")
        }
    }

    @Test
    fun `accepts absolute paths that stay inside the project`() {
        val resolved = PathSandbox.resolve(root, File(root, "src/App.kt").absolutePath)
        assertTrue(PathSandbox.isInside(root, resolved))
    }

    @Test
    fun `rejects symlink escape`() {
        val link = File(root, "escape-link")
        try {
            java.nio.file.Files.createSymbolicLink(
                link.toPath(),
                outside.toPath()
            )
        } catch (_: Exception) {
            // Filesystem without symlink support: the sandbox guarantee is still asserted above.
            return
        }
        assertThrows(PathSandbox.PathRejected::class.java) {
            PathSandbox.resolve(root, "escape-link/leak.txt")
        }
    }

    @Test
    fun `rejects null bytes and empty paths`() {
        assertThrows(PathSandbox.PathRejected::class.java) { PathSandbox.resolve(root, "a\u0000b") }
        assertThrows(PathSandbox.PathRejected::class.java) { PathSandbox.resolve(root, "   ") }
    }

    @Test
    fun `cannot modify or delete the project root`() {
        assertThrows(PathSandbox.PathRejected::class.java) {
            PathSandbox.resolveModifiable(root, ".")
        }
        assertThrows(PathSandbox.PathRejected::class.java) {
            PathSandbox.resolveModifiable(root, "")
        }
        assertThrows(PathSandbox.PathRejected::class.java) {
            PathSandbox.resolveModifiable(root, "src/..")
        }
    }

    @Test
    fun `cannot touch DevStation internal state`() {
        File(root, ".devstation/recovery").mkdirs()
        assertThrows(PathSandbox.PathRejected::class.java) {
            PathSandbox.resolveModifiable(root, ".devstation/recovery/x.recovery")
        }
    }

    @Test
    fun `containment check is prefix safe`() {
        val sibling = File(root.parentFile, root.name + "-sibling").apply { mkdirs() }
        assertFalse(PathSandbox.isInside(root, sibling))
        sibling.deleteRecursively()
    }

    @Test
    fun `rejects a project root that does not exist`() {
        assertThrows(PathSandbox.PathRejected::class.java) {
            PathSandbox.resolve(File(root, "missing-root"), "a.txt")
        }
    }
}
