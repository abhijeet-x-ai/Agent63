package com.devstation.android.core.security

import com.devstation.android.core.agent.createTempProject
import com.devstation.android.core.agent.deleteTempProject
import com.devstation.android.core.security.policy.FileFingerprint
import com.devstation.android.core.security.policy.FilesystemSandbox
import com.devstation.android.core.security.policy.SandboxOperation
import com.devstation.android.core.security.policy.SandboxToken
import com.devstation.android.core.security.policy.SensitiveFilePolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 7 §6–§10/§50: the filesystem sandbox and its change detection.
 */
class FilesystemSandboxTest {

    private lateinit var root: File
    private lateinit var sibling: File
    private val sandbox = FilesystemSandbox()

    @Before
    fun setUp() {
        root = createTempProject(mapOf("src/App.kt" to "fun main() {}\n", ".env" to "TOKEN=x\n"))
        sibling = createTempProject(mapOf("other.txt" to "nope\n"))
    }

    @After
    fun tearDown() {
        deleteTempProject(root)
        deleteTempProject(sibling)
    }

    private fun resolution(path: String, operation: SandboxOperation = SandboxOperation.READ) =
        sandbox.resolve(root, path, operation)

    // ---- containment ----

    @Test
    fun `paths inside the project resolve`() {
        val result = resolution("src/App.kt")
        assertTrue(result is FilesystemSandbox.Resolution.Allowed)
        assertEquals(File(root, "src/App.kt").canonicalPath, (result as FilesystemSandbox.Resolution.Allowed).token.canonicalPath)
    }

    @Test
    fun `traversal is rejected`() {
        listOf("../outside.txt", "../../etc/passwd", "src/../../outside.txt", "src/./../../x").forEach { path ->
            assertTrue("expected rejection for $path", resolution(path) is FilesystemSandbox.Resolution.Rejected)
        }
    }

    @Test
    fun `absolute system and app-private paths are rejected and named`() {
        listOf(
            "/data/data/com.devstation.android/files/db",
            "/data/user/0/com.devstation.android/shared_prefs/x.xml",
            "/data/misc/keystore/user_0/key",
            "/proc/self/environ",
            "/sys/kernel/notes",
            "/dev/urandom",
            "/vendor/lib64/libc.so",
            "/system/bin/sh",
            "/apex/com.android.runtime/bin/dalvikvm",
            "/etc/passwd"
        ).forEach { path ->
            val result = resolution(path)
            assertTrue("expected rejection for $path", result is FilesystemSandbox.Resolution.Rejected)
            assertTrue(sandbox.isAndroidPrivatePath(path))
        }
    }

    @Test
    fun `an absolute path inside the project is still accepted`() {
        val inside = File(root, "src/App.kt").absolutePath
        // The project itself lives in app storage; only paths *inside it* are reachable.
        assertTrue(resolution(inside) is FilesystemSandbox.Resolution.Allowed)
    }

    @Test
    fun `another project is rejected even though it is inside app storage`() {
        val other = File(sibling, "other.txt").absolutePath
        val result = resolution(other)
        assertTrue(result is FilesystemSandbox.Resolution.Rejected)
    }

    @Test
    fun `the project root itself cannot be modified or deleted`() {
        listOf(".", "", "/").forEach { path ->
            val result = resolution(path, SandboxOperation.DELETE)
            assertTrue("expected rejection for '$path'", result is FilesystemSandbox.Resolution.Rejected)
        }
    }

    @Test
    fun `devstation internal directory is not modifiable`() {
        val result = resolution(".devstation/recovery.txt", SandboxOperation.WRITE)
        assertTrue(result is FilesystemSandbox.Resolution.Rejected)
    }

    @Test
    fun `no project root means no resolution`() {
        assertTrue(sandbox.resolve(null, "x", SandboxOperation.READ) is FilesystemSandbox.Resolution.Rejected)
    }

    // ---- symlinks ----

    @Test
    fun `a symlink escaping the project is rejected`() {
        val link = File(root, "escape-link")
        val target = File(sibling, "other.txt")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        if (!created) return // filesystem without symlink support

        val result = sandbox.resolve(root, "escape-link", SandboxOperation.READ)
        assertTrue("a symlink outside the project must be rejected", result is FilesystemSandbox.Resolution.Rejected)
    }

    @Test
    fun `a symlink to an existing file outside the project is rejected for reads and writes`() {
        val outside = File.createTempFile("devstation-sandbox-target", ".txt")
        try {
            val link = File(root, "outside-link")
            val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
            if (!created) return

            assertTrue(sandbox.resolve(root, "outside-link", SandboxOperation.READ) is FilesystemSandbox.Resolution.Rejected)
            assertTrue(sandbox.resolve(root, "outside-link", SandboxOperation.WRITE) is FilesystemSandbox.Resolution.Rejected)
            assertTrue(sandbox.resolve(root, "outside-link", SandboxOperation.DELETE) is FilesystemSandbox.Resolution.Rejected)
        } finally {
            outside.delete()
        }
    }

    // ---- sensitive files ----

    @Test
    fun `sensitive files are flagged without blocking reads`() {
        val result = resolution(".env")
        assertTrue(result is FilesystemSandbox.Resolution.Allowed)
        assertTrue((result as FilesystemSandbox.Resolution.Allowed).sensitive)
    }

    @Test
    fun `sensitive detection covers names, extensions and directories`() {
        val policy = SensitiveFilePolicy()
        listOf(
            ".env", ".env.production", "server.pem", "private.key", "cert.p12", "cert.pfx",
            "id_rsa", "id_ed25519", "credentials.json", "secrets.json", "service-account-x.json",
            "keystore.jks", "truststore.jks", "devstation_secure_prefs"
        ).forEach { name -> assertTrue("$name should be sensitive", policy.isSensitivePath(name)) }

        assertTrue(policy.isSensitive(File("/project/.devstation/state")))
        assertTrue(policy.isSensitive(File("/project/.ssh/id_rsa")))
        assertFalse(policy.isSensitivePath("README.md"))
        assertFalse(policy.isSensitivePath("App.kt"))
    }

    // ---- change detection (§50) ----

    private fun token(path: String, operation: SandboxOperation) =
        (sandbox.resolve(root, path, operation) as FilesystemSandbox.Resolution.Allowed).token

    @Test
    fun `an unchanged file passes revalidation`() {
        val token = token("src/App.kt", SandboxOperation.WRITE)
        assertNull(sandbox.revalidate(token))
    }

    @Test
    fun `a write is blocked when the file changed underneath the agent`() {
        val token = token("src/App.kt", SandboxOperation.WRITE)
        File(root, "src/App.kt").writeText("fun main() { println(2) }\n")
        assertNotNull(sandbox.revalidate(token))
    }

    @Test
    fun `a delete is blocked when the file was already removed`() {
        val token = token("src/App.kt", SandboxOperation.DELETE)
        File(root, "src/App.kt").delete()
        assertNotNull(sandbox.revalidate(token))
    }

    @Test
    fun `a read is not failed by a concurrent content change`() {
        val token = token("src/App.kt", SandboxOperation.READ)
        File(root, "src/App.kt").writeText("fun main() { println(3) }\n")
        assertNull(sandbox.revalidate(token))
    }

    @Test
    fun `a read is blocked when the file disappeared`() {
        val token = token("src/App.kt", SandboxOperation.READ)
        File(root, "src/App.kt").delete()
        assertNotNull(sandbox.revalidate(token))
    }

    @Test
    fun `a create is blocked when something else took the name`() {
        val result = sandbox.resolve(root, "new-file.txt", SandboxOperation.CREATE)
        val token = (result as FilesystemSandbox.Resolution.Allowed).token
        File(root, "new-file.txt").writeText("created by someone else\n")
        assertNotNull(sandbox.revalidate(token))
    }

    @Test
    fun `a create passes when the name is still free`() {
        val result = sandbox.resolve(root, "new-file.txt", SandboxOperation.CREATE)
        val token = (result as FilesystemSandbox.Resolution.Allowed).token
        assertNull(sandbox.revalidate(token))
    }

    @Test
    fun `fingerprints change when content changes`() {
        val first = sandbox.fingerprint(File(root, "src/App.kt"))
        File(root, "src/App.kt").appendText("// changed\n")
        val second = sandbox.fingerprint(File(root, "src/App.kt"))
        assertTrue(first.exists && second.exists)
        assertFalse(first.contentHash == second.contentHash)
    }
}
