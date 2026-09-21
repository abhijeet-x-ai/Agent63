package com.devstation.android.core.security

import com.devstation.android.core.agent.CommandCategory
import com.devstation.android.core.agent.ToolPermission
import com.devstation.android.core.agent.ToolRiskLevel
import com.devstation.android.core.agent.createTempProject
import com.devstation.android.core.agent.deleteTempProject
import com.devstation.android.core.security.policy.NetworkIntent
import com.devstation.android.core.security.policy.NetworkSecurityPolicy
import com.devstation.android.core.security.policy.TerminalSecurityPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Phase 7 §15–§24: terminal classification and network policy.
 */
class TerminalAndNetworkPolicyTest {

    private lateinit var root: File
    private val terminal = TerminalSecurityPolicy()
    private val network = NetworkSecurityPolicy()

    @Before
    fun setUp() {
        root = createTempProject(mapOf("src/App.kt" to "fun main() {}\n"))
    }

    @After
    fun tearDown() = deleteTempProject(root)

    private fun assess(command: String, guest: Boolean = true) = terminal.assess(command, root, guest)
    private fun category(command: String) = assess(command).category

    // ---- classification ----

    @Test
    fun `read-only commands are classified as read-only`() {
        listOf("pwd", "ls -la", "cat README.md", "git status", "git log --oneline", "node --version", "grep -rn foo .")
            .forEach { assertEquals(it, CommandCategory.READ_ONLY, category(it)) }
    }

    @Test
    fun `project modification requires approval`() {
        listOf("mkdir src", "touch notes.txt", "git commit -m x", "npm test", "sed -i s/a/b/ x.txt")
            .forEach { assertEquals(it, CommandCategory.MODIFY_PROJECT, category(it)) }
    }

    @Test
    fun `destructive commands are critical and always ask`() {
        listOf("rm -rf build", "rm file.txt", "git reset --hard", "git clean -fdx", "shred secrets.txt", "dd if=/dev/zero of=x")
            .forEach { command ->
                val assessment = assess(command)
                assertEquals(command, CommandCategory.DESTRUCTIVE, assessment.category)
                assertEquals(ToolRiskLevel.CRITICAL, assessment.risk)
                assertEquals(ToolPermission.ALWAYS_ASK, terminal.permissionFor(assessment))
            }
    }

    @Test
    fun `package install is ask and package removal is always-ask`() {
        assertEquals(CommandCategory.INSTALL_PACKAGE, category("npm install express"))
        assertEquals(CommandCategory.INSTALL_PACKAGE, category("apk add nodejs"))
        assertEquals(CommandCategory.PACKAGE_REMOVE, category("apk del nodejs"))
        assertEquals(CommandCategory.PACKAGE_REMOVE, category("pip uninstall requests"))
        assertEquals(ToolPermission.ASK, terminal.permissionFor(assess("npm install express")))
        assertEquals(ToolPermission.ALWAYS_ASK, terminal.permissionFor(assess("apk del nodejs")))
    }

    @Test
    fun `unknown commands always ask and are never assumed safe`() {
        listOf("frobnicate --all", "./mystery-binary --go", "blorp").forEach { command ->
            val assessment = assess(command)
            assertEquals(command, CommandCategory.UNKNOWN, assessment.category)
            assertEquals(ToolPermission.ALWAYS_ASK, terminal.permissionFor(assessment))
        }
    }

    @Test
    fun `a blacklist-free classifier still refuses an unmatched destructive segment`() {
        // The most dangerous segment of a compound command wins.
        assertEquals(CommandCategory.DESTRUCTIVE, category("ls && rm -rf build"))
        assertTrue(assess("ls && rm -rf build").compound)
    }

    @Test
    fun `system commands always ask`() {
        listOf("mount /dev/x", "su -", "setprop x y").forEach { command ->
            assertEquals(command, CommandCategory.SYSTEM, category(command))
            assertEquals(ToolPermission.ALWAYS_ASK, terminal.permissionFor(assess(command)))
        }
    }

    // ---- argument inspection (a command name alone is never enough) ----

    @Test
    fun `shell substitution makes a command unverifiable`() {
        listOf("echo \$(whoami)", "cat `ls`", "echo \${HOME}").forEach { command ->
            assertEquals(command, CommandCategory.UNKNOWN, category(command))
        }
    }

    @Test
    fun `output redirection can never be read-only`() {
        assertEquals(CommandCategory.MODIFY_PROJECT, category("echo hello > out.txt"))
        assertEquals(CommandCategory.MODIFY_PROJECT, category("cat x | tee y"))
    }

    @Test
    fun `a command referencing a private android path is blocked`() {
        listOf(
            "cat /data/data/com.devstation.android/files/x",
            "cat /sdcard/Download/x",
            "cp x /system/bin/sh",
            "cat /proc/self/environ"
        ).forEach { command ->
            val assessment = assess(command)
            assertNotNull("expected a structural block for: $command", assessment.blockedReason)
            assertEquals(ToolPermission.DENY, terminal.permissionFor(assessment))
        }
    }

    @Test
    fun `a sensitive filename argument is flagged even without a path separator`() {
        assertTrue(assess("cat .env").sensitiveArgument)
        assertTrue(assess("cp .env .env.bak").sensitiveArgument)
        assertFalse(assess("cat README.md").sensitiveArgument)
    }

    @Test
    fun `the fallback shell cannot reach outside the project`() {
        assertNotNull(assess("cat /etc/hosts", guest = false).blockedReason)
        assertNotNull(assess("cat ../../shared_prefs/x.xml", guest = false).blockedReason)
        assertNotNull(assess("cp src/App.kt /data/local/tmp/x", guest = false).blockedReason)
        assertNull(assess("cat src/App.kt", guest = false).blockedReason)
        assertNull(assess("ls -la", guest = false).blockedReason)
    }

    // ---- local servers ----

    @Test
    fun `starting a local server is a local-network action, not a harmless read`() {
        listOf("python3 -m http.server 8000", "npm run dev", "vite", "node server.js").forEach { command ->
            val assessment = assess(command)
            assertEquals(command, CommandCategory.LOCAL_NETWORK, assessment.category)
            assertEquals(NetworkIntent.LOCAL_NETWORK, assessment.networkIntent)
            assertEquals(ToolPermission.ASK, terminal.permissionFor(assessment))
            assertTrue(assessment.localServer)
        }
    }

    // ---- network destinations ----

    @Test
    fun `an explicit url yields an internet destination with host and port`() {
        val destination = network.destination("curl https://example.com:8443/pkg")
        assertEquals(NetworkIntent.INTERNET, destination.intent)
        assertEquals("example.com", destination.host)
        assertEquals(8443, destination.port)
    }

    @Test
    fun `localhost and private addresses are local network, never harmless`() {
        listOf(
            "curl http://127.0.0.1:8080/health",
            "curl http://localhost:3000",
            "curl http://192.168.1.10/x",
            "curl http://10.0.0.5/x",
            "curl http://[::1]:9000/x"
        ).forEach { command ->
            assertEquals(command, NetworkIntent.LOCAL_NETWORK, network.destination(command).intent)
            assertEquals(command, CommandCategory.LOCAL_NETWORK, category(command))
        }
    }

    @Test
    fun `a package manager has internet intent even without a url`() {
        assertEquals(NetworkIntent.INTERNET, network.destination("npm install express").intent)
        assertEquals(NetworkIntent.INTERNET, network.destination("pip install requests").intent)
        assertEquals(NetworkIntent.INTERNET, network.destination("git clone https://x/y.git").intent)
    }

    @Test
    fun `a local read has no network intent`() {
        listOf("ls -la", "git status", "cat src/App.kt").forEach { command ->
            assertEquals(command, NetworkIntent.NONE, network.destination(command).intent)
        }
    }

    @Test
    fun `destinations are redacted before they are shown or stored`() {
        val display = network.destination("curl https://user:pass@example.com/data?token=SECRET#frag").display
        assertNotNull(display)
        assertTrue(display!!.contains("example.com"))
        assertFalse(display.contains("pass"))
        assertFalse(display.contains("SECRET"))
        assertFalse(display.contains("#frag"))
    }

    @Test
    fun `ports can be discovered from flags, urls and environment assignments`() {
        assertEquals(8000, network.portHint("npx serve --port 8000"))
        assertEquals(8080, network.destination("curl http://127.0.0.1:8080/x").port)
        assertEquals(3000, network.portHint("PORT=3000 npm start"))
    }
}
