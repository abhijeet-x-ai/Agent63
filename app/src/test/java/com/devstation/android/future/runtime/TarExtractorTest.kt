package com.devstation.android.future.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class TarExtractorTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("tar_extractor_test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test(expected = SecurityException::class)
    fun `validatePathSafety rejects parent directory traversal`() {
        TarExtractor.validatePathSafety(tempDir, "../escape.txt")
    }

    @Test(expected = SecurityException::class)
    fun `validatePathSafety rejects absolute paths`() {
        TarExtractor.validatePathSafety(tempDir, "/etc/passwd")
    }

    @Test(expected = SecurityException::class)
    fun `validatePathSafety rejects complex traversal path`() {
        TarExtractor.validatePathSafety(tempDir, "sub/dir/../../../etc/shadow")
    }

    @Test
    fun `safe extraction extracts valid files and directories`() {
        val tarOut = ByteArrayOutputStream()
        val gzOut = GZIPOutputStream(tarOut)

        // Add directory entry: "testdir/"
        val dirHeader = createTarHeader("testdir/", 0L, type = '5')
        gzOut.write(dirHeader)

        // Add file entry: "testdir/hello.txt"
        val content = "Hello DevStation Linux\n".toByteArray(Charsets.UTF_8)
        val fileHeader = createTarHeader("testdir/hello.txt", content.size.toLong(), type = '0')
        gzOut.write(fileHeader)
        gzOut.write(content)

        // Pad file to 512 bytes
        val remainder = content.size % 512
        if (remainder != 0) {
            gzOut.write(ByteArray(512 - remainder))
        }

        // Add two 512-byte zero blocks for EOF
        gzOut.write(ByteArray(1024))
        gzOut.finish()

        val archiveBytes = tarOut.toByteArray()
        TarExtractor.extract(
            archiveStream = ByteArrayInputStream(archiveBytes),
            destinationDir = tempDir,
            isGzipped = true
        )

        val extractedDir = File(tempDir, "testdir")
        assertTrue(extractedDir.exists() && extractedDir.isDirectory)

        val extractedFile = File(extractedDir, "hello.txt")
        assertTrue(extractedFile.exists() && extractedFile.isFile)
        assertEquals("Hello DevStation Linux\n", extractedFile.readText())
    }

    private fun createTarHeader(name: String, size: Long, type: Char): ByteArray {
        val header = ByteArray(512)
        name.toByteArray(Charsets.UTF_8).copyInto(header, 0)
        "0000755\u0000".toByteArray().copyInto(header, 100)
        "0000000\u0000".toByteArray().copyInto(header, 108)
        "0000000\u0000".toByteArray().copyInto(header, 116)

        val octalSize = "%011o\u0000".format(size)
        octalSize.toByteArray().copyInto(header, 124)
        "00000000000\u0000".toByteArray().copyInto(header, 136)
        header[156] = type.code.toByte()
        "ustar\u000000".toByteArray().copyInto(header, 257)

        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var sum = 0
        for (b in header) sum += (b.toInt() and 0xFF)
        val octalSum = "%06o\u0000 ".format(sum)
        octalSum.toByteArray().copyInto(header, 148)

        return header
    }
}
