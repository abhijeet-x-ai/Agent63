package com.devstation.android.feature.editor

import com.devstation.android.feature.editor.model.LineEnding
import com.devstation.android.feature.editor.service.EditorFileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class EditorFileManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var projectDir: File
    private lateinit var fileManager: EditorFileManager

    @Before
    fun setup() {
        projectDir = tempFolder.newFolder("test_project")
        fileManager = EditorFileManager(projectDir)
    }

    @Test
    fun `validatePath succeeds for internal files and blocks path traversal`() {
        val validFile = File(projectDir, "src/main.kt")
        assertEquals(validFile.canonicalPath, fileManager.validatePath(validFile).path)

        val invalidEscapingFile = File(projectDir, "../outside.txt")
        var exceptionThrown = false
        try {
            fileManager.validatePath(invalidEscapingFile)
        } catch (e: SecurityException) {
            exceptionThrown = true
        }
        assertTrue("Expected SecurityException when path escapes project root", exceptionThrown)
    }

    @Test
    fun `saveFile writes content atomically and reads back correctly`() {
        val targetFile = File(projectDir, "hello.txt")
        val content = "Hello from DevStation Code Editor!"

        val savedDoc = fileManager.saveFile(targetFile, content)
        assertEquals(content, savedDoc.content)
        assertTrue(targetFile.exists())

        val readDoc = fileManager.readFile(targetFile)
        assertEquals(content, readDoc.content)
        assertFalse(readDoc.isBinary)
    }

    @Test
    fun `preserves CRLF and LF line endings on save`() {
        val crlfFile = File(projectDir, "windows.txt")
        val crlfContent = "line1\r\nline2\r\nline3"
        fileManager.saveFile(crlfFile, crlfContent, lineEnding = LineEnding.CRLF)

        val readCrlf = fileManager.readFile(crlfFile)
        assertEquals(LineEnding.CRLF, readCrlf.lineEnding)
        assertTrue(readCrlf.content.contains("\r\n"))

        val lfFile = File(projectDir, "unix.txt")
        val lfContent = "line1\nline2\nline3"
        fileManager.saveFile(lfFile, lfContent, lineEnding = LineEnding.LF)

        val readLf = fileManager.readFile(lfFile)
        assertEquals(LineEnding.LF, readLf.lineEnding)
        assertFalse(readLf.content.contains("\r\n"))
    }

    @Test
    fun `detects UTF-8 BOM and preserves content without BOM prefix`() {
        val bomFile = File(projectDir, "bom.txt")
        val rawBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "BOM Content".toByteArray(StandardCharsets.UTF_8)
        bomFile.writeBytes(rawBytes)

        val doc = fileManager.readFile(bomFile)
        assertTrue(doc.hasBom)
        assertEquals("BOM Content", doc.content)
    }

    @Test
    fun `recovery snapshot lifecycle saves reads and clears snapshots`() {
        val sourceFile = File(projectDir, "active.kt")
        sourceFile.writeText("original", StandardCharsets.UTF_8)

        val unsavedEdits = "original + modified edits"
        fileManager.saveRecoverySnapshot(sourceFile, unsavedEdits)

        val recovered = fileManager.getRecoverySnapshot(sourceFile)
        assertNotNull(recovered)
        assertEquals(unsavedEdits, recovered)

        fileManager.clearRecoverySnapshot(sourceFile)
        assertNull(fileManager.getRecoverySnapshot(sourceFile))
    }

    @Test
    fun `detects binary files with null bytes`() {
        val binFile = File(projectDir, "binary.dat")
        binFile.writeBytes(byteArrayOf(0x01, 0x00, 0x03, 0x04)) // Null byte at index 1

        assertTrue(fileManager.isBinaryFile(binFile))
        val doc = fileManager.readFile(binFile)
        assertTrue(doc.isBinary)
    }

    @Test
    fun `detects external file modification when disk content changes`() {
        val file = File(projectDir, "sync.txt")
        val doc1 = fileManager.saveFile(file, "Original version")

        assertFalse(fileManager.hasExternalModification(doc1))

        // Simulate external edit by terminal/Linux
        Thread.sleep(20)
        file.writeText("Externally modified version", StandardCharsets.UTF_8)

        assertTrue(fileManager.hasExternalModification(doc1))
    }
}
