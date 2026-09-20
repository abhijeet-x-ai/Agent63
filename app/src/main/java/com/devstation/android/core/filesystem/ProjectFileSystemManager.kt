package com.devstation.android.core.filesystem

import android.content.Context
import com.devstation.android.core.model.FileItem
import java.io.File

class ProjectFileSystemManager(
    private val baseDirProvider: () -> File
) {
    constructor(context: Context) : this({
        val externalDir = context.getExternalFilesDir(null)
        externalDir ?: context.filesDir
    })

    constructor(baseDir: File) : this({ baseDir })

    val defaultWorkspaceDir: File
        get() {
            val baseDir = baseDirProvider()
            val workspace = File(baseDir, "projects")
            if (!workspace.exists()) {
                workspace.mkdirs()
            }
            return workspace
        }

    fun createProject(projectName: String, parentDir: File? = null): Result<File> {
        return runCatching {
            val sanitized = sanitizeFileName(projectName)
            require(sanitized.isNotBlank()) { "Project name cannot be empty or invalid" }

            val targetParent = parentDir ?: defaultWorkspaceDir
            if (!targetParent.exists()) {
                targetParent.mkdirs()
            }

            val projectFolder = File(targetParent, sanitized)
            if (projectFolder.exists()) {
                throw IllegalStateException("Project with name '$sanitized' already exists at ${projectFolder.absolutePath}")
            }

            val created = projectFolder.mkdirs()
            if (!created && !projectFolder.exists()) {
                throw IllegalStateException("Failed to create directory at ${projectFolder.absolutePath}")
            }

            // Initialize DevStation project metadata directory and README
            val devStationMetaDir = File(projectFolder, ".devstation")
            devStationMetaDir.mkdirs()

            val configJson = File(devStationMetaDir, "project.json")
            configJson.writeText(
                """
                {
                    "name": "$sanitized",
                    "created": ${System.currentTimeMillis()},
                    "version": "1.0",
                    "generator": "DevStation"
                }
                """.trimIndent()
            )

            val readme = File(projectFolder, "README.md")
            readme.writeText(
                """
                # $sanitized
                
                Created with DevStation on Android.
                
                This project is stored locally on device storage.
                """.trimIndent()
            )

            projectFolder
        }
    }

    fun renameProject(currentPath: String, newName: String): Result<File> {
        return runCatching {
            val currentDir = File(currentPath)
            if (!currentDir.exists() || !currentDir.isDirectory) {
                throw IllegalArgumentException("Project directory does not exist at $currentPath")
            }

            val sanitized = sanitizeFileName(newName)
            require(sanitized.isNotBlank()) { "New project name cannot be empty" }

            val parent = currentDir.parentFile ?: defaultWorkspaceDir
            val targetDir = File(parent, sanitized)
            if (targetDir.exists() && targetDir.absolutePath != currentDir.absolutePath) {
                throw IllegalStateException("Target directory '${targetDir.name}' already exists")
            }

            val renamed = currentDir.renameTo(targetDir)
            if (!renamed) {
                throw IllegalStateException("Could not rename project directory to $newName")
            }

            // Update internal metadata if present
            val configFile = File(File(targetDir, ".devstation"), "project.json")
            if (configFile.exists()) {
                configFile.writeText(
                    """
                    {
                        "name": "$sanitized",
                        "updated": ${System.currentTimeMillis()},
                        "version": "1.0",
                        "generator": "DevStation"
                    }
                    """.trimIndent()
                )
            }

            targetDir
        }
    }

    fun deleteProject(path: String): Result<Boolean> {
        return runCatching {
            val dir = File(path)
            if (!dir.exists()) {
                return@runCatching true
            }
            dir.deleteRecursively()
        }
    }

    fun listFiles(directoryPath: String): Result<List<FileItem>> {
        return runCatching {
            val dir = File(directoryPath)
            if (!dir.exists() || !dir.isDirectory) {
                throw IllegalArgumentException("Path does not exist or is not a directory: $directoryPath")
            }

            val files = dir.listFiles() ?: emptyArray()
            files.map { file ->
                FileItem(
                    name = file.name,
                    path = file.absolutePath,
                    isDirectory = file.isDirectory,
                    sizeBytes = if (file.isDirectory) calculateDirectorySize(file) else file.length(),
                    lastModified = file.lastModified(),
                    extension = file.extension.lowercase()
                )
            }.sortedWith(
                compareBy<FileItem> { !it.isDirectory }
                    .thenBy { it.name.lowercase() }
            )
        }
    }

    fun createFolder(parentPath: String, folderName: String): Result<File> {
        return runCatching {
            val parent = File(parentPath)
            if (!parent.exists() || !parent.isDirectory) {
                throw IllegalArgumentException("Parent directory does not exist: $parentPath")
            }
            val sanitized = sanitizeFileName(folderName)
            val newDir = File(parent, sanitized)
            if (newDir.exists()) {
                throw IllegalStateException("Folder '$sanitized' already exists")
            }
            if (!newDir.mkdir()) {
                throw IllegalStateException("Failed to create directory '${newDir.absolutePath}'")
            }
            newDir
        }
    }

    fun createFile(parentPath: String, fileName: String, initialContent: String = ""): Result<File> {
        return runCatching {
            val parent = File(parentPath)
            if (!parent.exists() || !parent.isDirectory) {
                throw IllegalArgumentException("Parent directory does not exist: $parentPath")
            }
            val sanitized = sanitizeFileName(fileName)
            val newFile = File(parent, sanitized)
            if (newFile.exists()) {
                throw IllegalStateException("File '$sanitized' already exists")
            }
            newFile.writeText(initialContent)
            newFile
        }
    }

    fun renameFile(path: String, newName: String): Result<File> {
        return runCatching {
            val file = File(path)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: $path")
            }
            val sanitized = sanitizeFileName(newName)
            val target = File(file.parentFile, sanitized)
            if (target.exists()) {
                throw IllegalStateException("Target '$sanitized' already exists")
            }
            if (!file.renameTo(target)) {
                throw IllegalStateException("Failed to rename to $newName")
            }
            target
        }
    }

    fun deleteFile(path: String): Result<Boolean> {
        return runCatching {
            val file = File(path)
            if (!file.exists()) return@runCatching true
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        }
    }

    fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (!dir.isDirectory) return dir.length()
        var size = 0L
        val children = dir.listFiles() ?: return 0L
        for (child in children) {
            size += if (child.isDirectory) calculateDirectorySize(child) else child.length()
        }
        return size
    }

    private fun sanitizeFileName(name: String): String {
        return name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
}
