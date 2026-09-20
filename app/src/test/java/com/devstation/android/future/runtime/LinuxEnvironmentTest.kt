package com.devstation.android.future.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LinuxEnvironmentTest {

    private lateinit var tempDir: File
    private lateinit var storagePaths: LinuxStoragePaths
    private lateinit var environment: LinuxEnvironment

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("linux_env_test").toFile()
        storagePaths = LinuxStoragePaths(
            linuxRootDir = tempDir,
            rootfsDir = File(tempDir, "rootfs"),
            homeDir = File(tempDir, "home/devstation"),
            downloadsDir = File(tempDir, "downloads"),
            metadataDir = File(tempDir, "metadata")
        )
        environment = LinuxEnvironment(storagePaths)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `buildEnvironment constructs proper standard environment`() {
        val env = environment.buildEnvironment()

        assertEquals("/home/devstation", env["HOME"])
        assertEquals("xterm-256color", env["TERM"])
        assertEquals("/workspace", env["PWD"])
        assertTrue(env["PATH"]!!.contains("/usr/bin"))
        assertTrue(env["PATH"]!!.contains("/bin"))
    }

    @Test
    fun `bootstrapFilesystem creates resolv_conf, profile and mountpoints`() {
        val projectDir = File(tempDir, "projects/my-test-project")
        projectDir.mkdirs()

        environment.bootstrapFilesystem(projectDir)

        val resolvConf = File(storagePaths.rootfsDir, "etc/resolv.conf")
        assertTrue(resolvConf.exists())
        assertTrue(resolvConf.readText().contains("nameserver 8.8.8.8"))

        val profile = File(storagePaths.homeDir, ".profile")
        assertTrue(profile.exists())
        assertTrue(profile.readText().contains("export HOME=\"/home/devstation\""))

        val workspaceMount = File(storagePaths.rootfsDir, "workspace")
        assertTrue(workspaceMount.exists() && workspaceMount.isDirectory)
    }
}
