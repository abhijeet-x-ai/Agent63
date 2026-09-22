package com.devstation.android.core.git

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GitSecurityAttackTest {

    private val policy = GitSecurityPolicy()
    private val fakeProjectDir = File("C:/test_project").takeIf { File("C:/").exists() } ?: File("/tmp/test_project")

    @Test
    fun testPathTraversalRejection() {
        val attackPaths = listOf(
            "../../etc/passwd",
            "..\\..\\windows\\system32",
            "/etc/shadow",
            "C:/Windows/System32/cmd.exe",
            "sub/../../../../secret.txt",
            "foo/bar/../../../outside.txt"
        )

        for (path in attackPaths) {
            val result = policy.validatePathWithinProject(fakeProjectDir, path)
            assertTrue("Expected failure for path traversal: $path", result.isFailure)
        }
    }

    @Test
    fun testValidProjectPathsAccepted() {
        val validPaths = listOf(
            "src/Main.kt",
            "README.md",
            "app/build.gradle.kts",
            "assets/images/logo.png"
        )

        for (path in validPaths) {
            val result = policy.validatePathWithinProject(fakeProjectDir, path)
            assertTrue("Expected success for valid path: $path", result.isSuccess)
        }
    }

    @Test
    fun testSensitiveFilesDetection() {
        val sensitiveFiles = listOf(
            ".env",
            ".env.production",
            "id_rsa",
            "id_ed25519",
            "server.pem",
            "private.key",
            "credentials.json",
            "google-services.json",
            "certs/server.crt",
            "secrets/keystore.jks"
        )

        for (file in sensitiveFiles) {
            assertTrue("Expected sensitive file match: $file", policy.isSensitiveFile(file))
        }

        val nonSensitiveFiles = listOf(
            "Main.kt",
            "App.js",
            "package.json",
            "README.md",
            "index.html",
            "style.css"
        )

        for (file in nonSensitiveFiles) {
            assertFalse("Expected non-sensitive file: $file", policy.isSensitiveFile(file))
        }
    }

    @Test
    fun testRemoteUrlSsrfAndProtocolAttacks() {
        val dangerousUrls = listOf(
            "file:///etc/passwd",
            "http://127.0.0.1/repo.git",
            "http://localhost:8080/repo.git",
            "http://169.254.169.254/latest/meta-data",
            "http://10.0.0.1/private.git",
            "http://192.168.1.50/private.git",
            "http://172.16.0.1/private.git",
            "http://[::1]/repo.git",
            "http://github.com/owner/repo.git", // Plaintext HTTP rejected
            "ssh://git@github.com/owner/repo.git", // SSH rejected
            "ftp://example.com/repo.git"
        )

        for (url in dangerousUrls) {
            val res = policy.validateRemoteUrl(url)
            assertTrue("Expected dangerous URL to be rejected: $url", res.isFailure)
        }

        val safeUrls = listOf(
            "https://github.com/owner/repo.git",
            "https://gitlab.com/group/project.git",
            "https://bitbucket.org/team/repo.git"
        )

        for (url in safeUrls) {
            val res = policy.validateRemoteUrl(url)
            assertTrue("Expected safe HTTPS URL to be accepted: $url", res.isSuccess)
        }
    }

    @Test
    fun testDangerousGitConfigDirectivesNeutralized() {
        val dangerousDirectives = listOf(
            "core.sshCommand",
            "core.fsmonitor",
            "core.hooksPath",
            "credential.helper"
        )

        val overrides = policy.buildSecurityConfigOverrides(allowHooks = false)
        for (directive in dangerousDirectives) {
            assertTrue(
                "Expected security override for dangerous config directive: $directive",
                overrides.any { it.startsWith("$directive=") }
            )
        }
    }
}
