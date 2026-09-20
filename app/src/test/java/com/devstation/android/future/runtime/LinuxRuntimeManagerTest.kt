package com.devstation.android.future.runtime

import com.devstation.android.core.common.DefaultDispatcherProvider
import com.devstation.android.core.filesystem.ProjectFileSystemManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class LinuxRuntimeManagerTest {

    private lateinit var tempDir: File
    private lateinit var projectFileSystemManager: ProjectFileSystemManager
    private lateinit var manager: LinuxRuntimeManager

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("runtime_manager_test").toFile()
        projectFileSystemManager = ProjectFileSystemManager(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `initial state is NOT_INSTALLED when rootfs is absent`() = runTest {
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getExternalFilesDir(type: String?): File = tempDir
            override fun getFilesDir(): File = tempDir
        }

        manager = LinuxRuntimeManager(
            context = mockContext,
            fileSystemManager = projectFileSystemManager,
            dispatchers = DefaultDispatcherProvider(),
            scope = this
        )

        assertFalse(manager.isInstalled())
        assertEquals(LinuxRuntimeState.NOT_INSTALLED, manager.runtimeState.value)
    }

    @Test
    fun `resetEnvironment clears rootfs but preserves user projects`() = runTest {
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getExternalFilesDir(type: String?): File = tempDir
            override fun getFilesDir(): File = tempDir
        }

        manager = LinuxRuntimeManager(
            context = mockContext,
            fileSystemManager = projectFileSystemManager,
            dispatchers = DefaultDispatcherProvider(),
            scope = this
        )

        // Create mock project
        val project = projectFileSystemManager.createProject("MyCriticalApp").getOrThrow()
        assertTrue(project.exists())

        // Create mock rootfs
        val rootfs = manager.storagePaths.rootfsDir
        rootfs.mkdirs()
        File(rootfs, "bin/sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh")
        }
        assertTrue(manager.isInstalled())

        // Execute reset
        val result = manager.resetEnvironment(keepHome = true)
        assertTrue(result.isSuccess)

        // Assert rootfs is gone
        assertFalse(manager.isInstalled())
        assertFalse(rootfs.exists())

        // CRITICAL INVARIANT: Project must still exist!
        assertTrue(project.exists())
        assertTrue(File(project, ".devstation/project.json").exists())
    }

    @Test
    fun `uninstallLinux removes linux root but preserves user projects`() = runTest {
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getExternalFilesDir(type: String?): File = tempDir
            override fun getFilesDir(): File = tempDir
        }

        manager = LinuxRuntimeManager(
            context = mockContext,
            fileSystemManager = projectFileSystemManager,
            dispatchers = DefaultDispatcherProvider(),
            scope = this
        )

        // Create mock project
        val project = projectFileSystemManager.createProject("ImportantProject").getOrThrow()
        assertTrue(project.exists())

        // Create mock linux root
        val linuxRoot = manager.storagePaths.linuxRootDir
        linuxRoot.mkdirs()
        File(linuxRoot, "rootfs/bin/sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh")
        }

        // Uninstall
        val result = manager.uninstallLinux()
        assertTrue(result.isSuccess)

        // Assert linux directory is deleted
        assertFalse(linuxRoot.exists())

        // CRITICAL INVARIANT: Project must remain completely intact!
        assertTrue(project.exists())
    }
}
