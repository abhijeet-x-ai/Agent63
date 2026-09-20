package com.devstation.android.core.filesystem

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectFileSystemManagerTest {

    private lateinit var tempDir: File
    private lateinit var manager: ProjectFileSystemManager

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("devstation_test").toFile()
        manager = ProjectFileSystemManager(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `createProject creates project directory and metadata files`() {
        val result = manager.createProject("MyNewProject")
        assertTrue(result.isSuccess)
        val projectDir = result.getOrThrow()

        assertTrue(projectDir.exists())
        assertTrue(projectDir.isDirectory)
        assertEquals("MyNewProject", projectDir.name)

        val metaDir = File(projectDir, ".devstation")
        assertTrue(metaDir.exists())
        val configFile = File(metaDir, "project.json")
        assertTrue(configFile.exists())
        assertTrue(configFile.readText().contains("MyNewProject"))

        val readme = File(projectDir, "README.md")
        assertTrue(readme.exists())
    }

    @Test
    fun `createProject fails when name already exists`() {
        manager.createProject("DuplicateProject")
        val duplicate = manager.createProject("DuplicateProject")
        assertTrue(duplicate.isFailure)
    }

    @Test
    fun `listFiles sorts directories first then alphabetical`() {
        val proj = manager.createProject("SortTest").getOrThrow()

        manager.createFile(proj.absolutePath, "b_file.txt", "content")
        manager.createFile(proj.absolutePath, "a_file.txt", "content")
        manager.createFolder(proj.absolutePath, "z_folder")
        manager.createFolder(proj.absolutePath, "a_folder")

        val files = manager.listFiles(proj.absolutePath).getOrThrow()

        val directories = files.filter { it.isDirectory && !it.name.startsWith(".") }
        val normalFiles = files.filter { !it.isDirectory }

        assertTrue(files.first().isDirectory)
        assertEquals("a_folder", directories[0].name)
        assertEquals("z_folder", directories[1].name)
        assertTrue(normalFiles.any { it.name == "a_file.txt" })
    }

    @Test
    fun `renameProject renames directory successfully`() {
        val proj = manager.createProject("OldName").getOrThrow()
        val renamed = manager.renameProject(proj.absolutePath, "RenamedProject")

        assertTrue(renamed.isSuccess)
        val newDir = renamed.getOrThrow()
        assertEquals("RenamedProject", newDir.name)
        assertTrue(newDir.exists())
        assertFalse(proj.exists())
    }

    @Test
    fun `deleteProject recursively deletes directory`() {
        val proj = manager.createProject("ToDelete").getOrThrow()
        manager.createFile(proj.absolutePath, "nested.txt", "data")
        manager.createFolder(proj.absolutePath, "sub")

        val deleted = manager.deleteProject(proj.absolutePath)
        assertTrue(deleted.isSuccess)
        assertFalse(proj.exists())
    }

    @Test
    fun `calculateDirectorySize calculates total bytes`() {
        val proj = manager.createProject("SizeTest").getOrThrow()
        val file1 = manager.createFile(proj.absolutePath, "test.txt", "12345").getOrThrow()

        val size = manager.calculateDirectorySize(proj)
        assertTrue(size >= 5)
    }
}
