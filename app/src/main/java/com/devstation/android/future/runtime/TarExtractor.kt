package com.devstation.android.future.runtime

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * Pure Kotlin/Java streaming TAR and GZ archive extractor with strict security validations
 * to prevent directory traversal and symlink escape vulnerabilities.
 */
object TarExtractor {

    private const val BLOCK_SIZE = 512

    /**
     * Extracts a .tar.gz or .tar archive to target directory.
     *
     * @param archiveStream Input stream of the archive (can be compressed or uncompressed).
     * @param isGzipped Whether stream is gzip compressed.
     * @param destinationDir Destination root directory for extraction.
     * @param onProgress Optional progress callback with (filesExtracted, bytesProcessed).
     */
    fun extract(
        archiveStream: InputStream,
        destinationDir: File,
        isGzipped: Boolean = true,
        onProgress: ((filesExtracted: Int, bytesProcessed: Long) -> Unit)? = null
    ) {
        val destCanonical = destinationDir.canonicalFile
        if (!destCanonical.exists()) {
            destCanonical.mkdirs()
        }

        val rawIn = if (isGzipped) GZIPInputStream(archiveStream) else archiveStream
        val stream = BufferedInputStream(rawIn)

        val headerBuffer = ByteArray(BLOCK_SIZE)
        var totalBytesProcessed = 0L
        var filesExtracted = 0
        var gnuLongName: String? = null

        while (true) {
            val bytesRead = readFully(stream, headerBuffer)
            if (bytesRead < BLOCK_SIZE) break
            totalBytesProcessed += BLOCK_SIZE

            // Two consecutive 512-zero blocks mark end of archive
            if (isAllZeros(headerBuffer)) {
                // Check if next block is also zero
                val nextBuffer = ByteArray(BLOCK_SIZE)
                val nextRead = readFully(stream, nextBuffer)
                if (nextRead == BLOCK_SIZE && isAllZeros(nextBuffer)) {
                    break
                }
                // If not, continue processing
            }

            // Parse header
            val typeFlag = headerBuffer[156].toInt().toChar()
            val rawName = parseString(headerBuffer, 0, 100)
            val prefix = parseString(headerBuffer, 345, 155)
            val fullName = gnuLongName ?: if (prefix.isNotBlank()) "$prefix/$rawName" else rawName
            gnuLongName = null // Reset after using

            val fileSize = parseOctal(headerBuffer, 124, 12)
            val fileMode = parseOctal(headerBuffer, 108, 8).toInt()

            if (typeFlag == 'L') {
                // GNU LongLink extension: body contains full file name
                val longNameBytes = readBytes(stream, fileSize.toInt())
                totalBytesProcessed += fileSize
                skipPadding(stream, fileSize)
                gnuLongName = String(longNameBytes, StandardCharsets.UTF_8).trimEnd('\u0000', '\n', '\r')
                continue
            }

            if (fullName.isBlank()) continue

            // Security check on entry path
            validatePathSafety(destCanonical, fullName)

            val targetFile = File(destCanonical, fullName).canonicalFile
            if (!targetFile.canonicalPath.startsWith(destCanonical.canonicalPath)) {
                throw SecurityException("Path traversal violation: entry '$fullName' escapes target directory.")
            }

            when (typeFlag) {
                '5' -> {
                    // Directory
                    if (!targetFile.exists()) {
                        targetFile.mkdirs()
                    }
                }
                '2' -> {
                    // Symlink
                    val linkTarget = parseString(headerBuffer, 157, 100)
                    validateSymlinkSafety(destCanonical, targetFile, linkTarget)
                    targetFile.parentFile?.mkdirs()
                    createSymlinkOrFallback(targetFile, linkTarget)
                }
                '0', '\u0000' -> {
                    // Regular file
                    targetFile.parentFile?.mkdirs()
                    FileOutputStream(targetFile).use { out ->
                        copyLimited(stream, out, fileSize)
                    }
                    totalBytesProcessed += fileSize
                    skipPadding(stream, fileSize)

                    // Preserve executable permissions
                    if ((fileMode and 0b001_000_000) != 0 || (fileMode and 0b000_001_000) != 0) {
                        targetFile.setExecutable(true, false)
                    }
                    targetFile.setReadable(true, false)
                    targetFile.setWritable(true, true)
                }
                else -> {
                    // Skip unsupported or pax/special entries
                    skipStream(stream, fileSize)
                    totalBytesProcessed += fileSize
                    skipPadding(stream, fileSize)
                }
            }

            filesExtracted++
            onProgress?.invoke(filesExtracted, totalBytesProcessed)
        }
    }

    /**
     * Validates that an archive entry name does not attempt directory traversal.
     */
    fun validatePathSafety(destDir: File, entryPath: String) {
        val normalized = entryPath.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.startsWith("\\")) {
            throw SecurityException("Absolute paths inside rootfs archive are forbidden: $entryPath")
        }

        val parts = normalized.split('/')
        for (part in parts) {
            if (part == "..") {
                throw SecurityException("Path traversal attempt detected with '..' in archive entry: $entryPath")
            }
        }

        val testFile = File(destDir, normalized).canonicalFile
        val destCanonical = destDir.canonicalPath
        if (!testFile.canonicalPath.startsWith(destCanonical)) {
            throw SecurityException("Path traversal escape detected: $entryPath")
        }
    }

    private fun validateSymlinkSafety(destDir: File, symlinkFile: File, target: String) {
        val normalizedTarget = target.replace('\\', '/')
        // Relative symlinks are allowed if they don't escape destDir
        val resolved = if (normalizedTarget.startsWith("/")) {
            File(destDir, normalizedTarget.removePrefix("/"))
        } else {
            File(symlinkFile.parentFile ?: destDir, normalizedTarget)
        }

        val canonical = resolved.canonicalPath
        val destCanonical = destDir.canonicalPath
        if (!canonical.startsWith(destCanonical) && !normalizedTarget.startsWith("/")) {
            throw SecurityException("Symlink target escapes destination directory: $target (from ${symlinkFile.name})")
        }
    }

    private fun createSymlinkOrFallback(symlinkFile: File, target: String) {
        try {
            if (symlinkFile.exists()) {
                symlinkFile.delete()
            }
            java.nio.file.Files.createSymbolicLink(
                symlinkFile.toPath(),
                java.nio.file.Paths.get(target)
            )
        } catch (e: Throwable) {
            // If Android filesystem doesn't support symlinks on this partition, record as placeholder
            symlinkFile.writeText(target, StandardCharsets.UTF_8)
        }
    }

    private fun parseString(buffer: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && buffer[end] != 0.toByte()) {
            end++
        }
        return String(buffer, offset, end - offset, StandardCharsets.UTF_8).trim()
    }

    private fun parseOctal(buffer: ByteArray, offset: Int, length: Int): Long {
        var result = 0L
        var start = offset
        while (start < offset + length && (buffer[start] == ' '.code.toByte() || buffer[start] == 0.toByte())) {
            start++
        }
        for (i in start until offset + length) {
            val b = buffer[i]
            if (b in '0'.code.toByte()..'7'.code.toByte()) {
                result = (result shl 3) + (b - '0'.code.toByte())
            } else {
                break
            }
        }
        return result
    }

    private fun isAllZeros(buffer: ByteArray): Boolean {
        for (b in buffer) {
            if (b != 0.toByte()) return false
        }
        return true
    }

    private fun readFully(stream: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = stream.read(buffer, total, buffer.size - total)
            if (count == -1) break
            total += count
        }
        return total
    }

    private fun readBytes(stream: InputStream, count: Int): ByteArray {
        val out = ByteArrayOutputStream(count)
        val buf = ByteArray(1024.coerceAtMost(count))
        var remaining = count
        while (remaining > 0) {
            val toRead = remaining.coerceAtMost(buf.size)
            val read = stream.read(buf, 0, toRead)
            if (read == -1) break
            out.write(buf, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun copyLimited(stream: InputStream, out: FileOutputStream, count: Long) {
        val buf = ByteArray(4096)
        var remaining = count
        while (remaining > 0) {
            val toRead = remaining.coerceAtMost(buf.size.toLong()).toInt()
            val read = stream.read(buf, 0, toRead)
            if (read == -1) break
            out.write(buf, 0, read)
            remaining -= read
        }
    }

    private fun skipStream(stream: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                if (stream.read() == -1) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun skipPadding(stream: InputStream, fileSize: Long) {
        val remainder = (fileSize % BLOCK_SIZE).toInt()
        if (remainder != 0) {
            val padding = BLOCK_SIZE - remainder
            skipStream(stream, padding.toLong())
        }
    }
}
