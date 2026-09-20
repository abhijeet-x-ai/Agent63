package com.devstation.android.feature.editor.service

import com.devstation.android.feature.editor.model.EditorDocument
import com.devstation.android.feature.editor.model.LineEnding
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

class EditorFileManager(
    private val projectRootDir: File
) {
    val recoveryDir: File
        get() = File(File(projectRootDir, ".devstation"), "recovery").apply {
            if (!exists()) mkdirs()
        }

    /**
     * Validates that target file is within the project root directory.
     * Throws SecurityException if path traversal is detected.
     */
    fun validatePath(file: File): File {
        val rootCanonical = projectRootDir.canonicalFile
        val fileCanonical = file.canonicalFile

        if (!fileCanonical.path.startsWith(rootCanonical.path)) {
            throw SecurityException("Access denied: path '${file.path}' escapes project root '${projectRootDir.path}'")
        }
        return fileCanonical
    }

    /**
     * Checks if a file is binary based on extension and inspection of first 8KB.
     */
    fun isBinaryFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (KNOWN_BINARY_EXTENSIONS.contains(ext)) {
            return true
        }

        if (!file.exists() || file.length() == 0L) {
            return false
        }

        // Read up to 8KB to detect null bytes
        val buffer = ByteArray(8192)
        return try {
            FileInputStream(file).use { input ->
                val bytesRead = input.read(buffer)
                if (bytesRead <= 0) return false
                for (i in 0 until bytesRead) {
                    if (buffer[i] == 0.toByte()) {
                        return true
                    }
                }
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reads a text file safely into an EditorDocument, detecting encoding, BOM, line endings, and hash.
     */
    fun readFile(file: File): EditorDocument {
        val validFile = validatePath(file)
        val fileSizeBytes = validFile.length()
        val lastModified = validFile.lastModified()

        if (isBinaryFile(validFile)) {
            return EditorDocument(
                filePath = validFile.absolutePath,
                content = "",
                fileSizeBytes = fileSizeBytes,
                lastModified = lastModified,
                isBinary = true
            )
        }

        val rawBytes = validFile.readBytes()
        var hasBom = false
        var textContent = ""

        if (rawBytes.size >= 3 && rawBytes[0] == 0xEF.toByte() && rawBytes[1] == 0xBB.toByte() && rawBytes[2] == 0xBF.toByte()) {
            hasBom = true
            textContent = String(rawBytes, 3, rawBytes.size - 3, StandardCharsets.UTF_8)
        } else {
            textContent = String(rawBytes, StandardCharsets.UTF_8)
        }

        val lineEnding = LineEnding.detect(textContent)
        val contentHash = EditorDocument.computeHash(textContent)

        return EditorDocument(
            filePath = validFile.absolutePath,
            content = textContent,
            encoding = StandardCharsets.UTF_8,
            lineEnding = lineEnding,
            hasBom = hasBom,
            fileSizeBytes = fileSizeBytes,
            lastModified = lastModified,
            contentHash = contentHash,
            isBinary = false
        )
    }

    /**
     * Atomically saves updated content to a file:
     * 1. Writes to temporary file in the same directory
     * 2. Flushes file descriptor
     * 3. Atomically renames/replaces target file
     * 4. Deletes recovery snapshot if present
     */
    fun saveFile(
        file: File,
        content: String,
        lineEnding: LineEnding = LineEnding.LF,
        hasBom: Boolean = false,
        encoding: Charset = StandardCharsets.UTF_8
    ): EditorDocument {
        val validFile = validatePath(file)
        val parent = validFile.parentFile ?: projectRootDir
        if (!parent.exists()) {
            parent.mkdirs()
        }

        // Normalize line endings if needed
        val normalizedContent = when (lineEnding) {
            LineEnding.CRLF -> content.replace("\r\n", "\n").replace("\n", "\r\n")
            LineEnding.CR -> content.replace("\r\n", "\n").replace("\n", "\r")
            LineEnding.LF -> content.replace("\r\n", "\n").replace("\r", "\n")
        }

        val contentBytes = normalizedContent.toByteArray(encoding)
        val totalBytes = if (hasBom) {
            val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            bomBytes + contentBytes
        } else {
            contentBytes
        }

        val tempFile = File(parent, ".${validFile.name}.tmp.${UUID.randomUUID()}")
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(totalBytes)
                fos.flush()
                fos.fd.sync()
            }

            try {
                Files.move(
                    tempFile.toPath(),
                    validFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: Exception) {
                // Fallback standard replace
                if (validFile.exists()) {
                    validFile.delete()
                }
                if (!tempFile.renameTo(validFile)) {
                    throw IllegalStateException("Failed to replace original file with atomic save")
                }
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }

        clearRecoverySnapshot(validFile)

        val updatedLastModified = validFile.lastModified()
        val updatedSize = validFile.length()
        val updatedHash = EditorDocument.computeHash(normalizedContent)

        return EditorDocument(
            filePath = validFile.absolutePath,
            content = normalizedContent,
            encoding = encoding,
            lineEnding = lineEnding,
            hasBom = hasBom,
            fileSizeBytes = updatedSize,
            lastModified = updatedLastModified,
            contentHash = updatedHash,
            isBinary = false
        )
    }

    /**
     * Checks if a file has been modified externally since it was loaded into the document.
     */
    fun hasExternalModification(document: EditorDocument): Boolean {
        val file = File(document.filePath)
        if (!file.exists()) return true
        if (file.lastModified() != document.lastModified) {
            // Timestamp differed; verify if actual content hash changed
            val currentDoc = try { readFile(file) } catch (e: Exception) { return true }
            return currentDoc.contentHash != document.contentHash
        }
        return false
    }

    /**
     * Recovery snapshot management:
     * Saves a snapshot of unsaved edits into `<project>/.devstation/recovery/<hash>.recovery`.
     */
    fun saveRecoverySnapshot(file: File, unsavedContent: String) {
        val validFile = validatePath(file)
        val snapshotFile = getRecoveryFile(validFile)
        val meta = """
            TIMESTAMP=${System.currentTimeMillis()}
            PATH=${validFile.absolutePath}
            CONTENT=
        """.trimIndent() + "\n" + unsavedContent

        snapshotFile.writeText(meta, StandardCharsets.UTF_8)
    }

    fun getRecoverySnapshot(file: File): String? {
        val validFile = validatePath(file)
        val snapshotFile = getRecoveryFile(validFile)
        if (!snapshotFile.exists()) return null

        val text = snapshotFile.readText(StandardCharsets.UTF_8)
        val marker = "CONTENT=\n"
        val idx = text.indexOf(marker)
        return if (idx != -1) text.substring(idx + marker.length) else null
    }

    fun hasRecoverySnapshot(file: File): Boolean {
        val validFile = validatePath(file)
        val recoveryFile = getRecoveryFile(validFile)
        if (!recoveryFile.exists()) return false

        // Check if recovery file is newer than original file
        return recoveryFile.lastModified() > validFile.lastModified()
    }

    fun clearRecoverySnapshot(file: File) {
        val validFile = validatePath(file)
        val snapshotFile = getRecoveryFile(validFile)
        if (snapshotFile.exists()) {
            snapshotFile.delete()
        }
    }

    private fun getRecoveryFile(file: File): File {
        val hash = MessageDigest.getInstance("MD5")
            .digest(file.absolutePath.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(recoveryDir, "$hash.recovery")
    }

    fun createFile(parentDir: File, fileName: String, initialContent: String = ""): File {
        val validParent = validatePath(parentDir)
        val sanitized = fileName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        require(sanitized.isNotBlank()) { "File name cannot be empty" }

        val newFile = File(validParent, sanitized)
        validatePath(newFile)
        if (newFile.exists()) {
            throw IllegalStateException("File '$sanitized' already exists")
        }
        newFile.writeText(initialContent, StandardCharsets.UTF_8)
        return newFile
    }

    fun createFolder(parentDir: File, folderName: String): File {
        val validParent = validatePath(parentDir)
        val sanitized = folderName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        require(sanitized.isNotBlank()) { "Folder name cannot be empty" }

        val newDir = File(validParent, sanitized)
        validatePath(newDir)
        if (newDir.exists()) {
            throw IllegalStateException("Folder '$sanitized' already exists")
        }
        if (!newDir.mkdir()) {
            throw IllegalStateException("Failed to create folder '${newDir.name}'")
        }
        return newDir
    }

    fun rename(file: File, newName: String): File {
        val validFile = validatePath(file)
        val sanitized = newName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        require(sanitized.isNotBlank()) { "Name cannot be empty" }

        val target = File(validFile.parentFile, sanitized)
        validatePath(target)
        if (target.exists()) {
            throw IllegalStateException("Target '$sanitized' already exists")
        }
        if (!validFile.renameTo(target)) {
            throw IllegalStateException("Failed to rename to $sanitized")
        }
        return target
    }

    fun delete(file: File): Pair<Int, Int> {
        val validFile = validatePath(file)
        var filesDeleted = 0
        var foldersDeleted = 0

        if (validFile.isDirectory) {
            val children = validFile.walkBottomUp()
            for (child in children) {
                if (child.isDirectory) {
                    foldersDeleted++
                    child.delete()
                } else {
                    filesDeleted++
                    child.delete()
                }
            }
        } else {
            filesDeleted++
            validFile.delete()
        }
        clearRecoverySnapshot(validFile)
        return Pair(filesDeleted, foldersDeleted)
    }

    companion object {
        private val KNOWN_BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "webp", "gif", "ico", "bmp",
            "apk", "jar", "class", "dex", "zip", "tar", "gz", "7z", "rar",
            "pdf", "doc", "docx", "xls", "xlsx",
            "mp3", "mp4", "wav", "ogg", "flac", "avi", "mov", "mkv",
            "exe", "so", "dll", "dylib", "bin", "dat", "iso"
        )
    }
}
