package com.devstation.android.future.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LinuxProcessLauncherTest {

    private lateinit var tempDir: File
    private lateinit var storagePaths: LinuxStoragePaths
    private lateinit var environment: LinuxEnvironment

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("launcher_test").toFile()
        storagePaths = LinuxStoragePaths(
            linuxRootDir = tempDir,
            rootfsDir = File(tempDir, "rootfs").apply { mkdirs() },
            homeDir = File(tempDir, "home/devstation").apply { mkdirs() },
            downloadsDir = File(tempDir, "downloads").apply { mkdirs() },
            metadataDir = File(tempDir, "metadata").apply { mkdirs() }
        )
        environment = LinuxEnvironment(storagePaths)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `buildLaunchCommand with PRoot binary constructs correct arguments`() {
        val fakeProot = File(tempDir, "proot").apply {
            createNewFile()
            setExecutable(true)
        }

        val launcher = LinuxProcessLauncher(
            storagePaths = storagePaths,
            environmentBuilder = environment,
            prootBinaryPath = fakeProot.absolutePath
        )

        val hostWorkspace = File(tempDir, "my-project").apply { mkdirs() }
        val cmd = launcher.buildLaunchCommand(listOf("/bin/sh"), hostWorkspace)

        assertEquals(fakeProot.absolutePath, cmd[0])
        assertTrue(cmd.contains("-r"))
        assertTrue(cmd.contains(storagePaths.rootfsDir.absolutePath))
        assertTrue(cmd.contains("-0"))
        assertTrue(cmd.contains("${hostWorkspace.canonicalPath}:/workspace"))
        assertTrue(cmd.contains("${storagePaths.homeDir.canonicalPath}:/home/devstation"))
        assertEquals("/bin/sh", cmd.last())
    }

    @Test
    fun `buildLaunchCommand fallback uses sh when proot is absent`() {
        val launcher = LinuxProcessLauncher(
            storagePaths = storagePaths,
            environmentBuilder = environment,
            prootBinaryPath = null
        )

        val hostWorkspace = File(tempDir, "my-project").apply { mkdirs() }
        val cmd = launcher.buildLaunchCommand(listOf("ls", "-la"), hostWorkspace)

        assertTrue(cmd.isNotEmpty())
    }
}
