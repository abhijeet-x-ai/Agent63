package com.devstation.android.core.filesystem

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.devstation.android.core.model.StorageStats
import java.io.File

class StorageStatsCalculator(
    private val context: Context,
    private val fileSystemManager: ProjectFileSystemManager
) {
    fun calculateStorageStats(): StorageStats {
        val dataDir = context.filesDir
        val statFs = try {
            StatFs(dataDir.absolutePath)
        } catch (e: Exception) {
            null
        }

        val totalBytes = statFs?.let { it.blockCountLong * it.blockSizeLong } ?: 0L
        val freeBytes = statFs?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L
        val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)

        val projectsBytes = fileSystemManager.calculateDirectorySize(fileSystemManager.defaultWorkspaceDir)

        val internalCache = fileSystemManager.calculateDirectorySize(context.cacheDir)
        val externalCache = context.externalCacheDir?.let { fileSystemManager.calculateDirectorySize(it) } ?: 0L
        val cacheBytes = internalCache + externalCache

        val internalData = fileSystemManager.calculateDirectorySize(context.filesDir)
        val externalData = context.getExternalFilesDir(null)?.let { fileSystemManager.calculateDirectorySize(it) } ?: 0L
        val totalAppFiles = internalData + externalData

        // Linux Userspace storage
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val linuxDir = File(baseDir, "linux")
        val linuxRootfsBytes = fileSystemManager.calculateDirectorySize(File(linuxDir, "rootfs"))
        val linuxHomeBytes = fileSystemManager.calculateDirectorySize(File(linuxDir, "home"))
        val linuxTotalBytes = fileSystemManager.calculateDirectorySize(linuxDir)

        val appDataBytes = (totalAppFiles - projectsBytes - linuxTotalBytes).coerceAtLeast(0L)

        return StorageStats(
            totalBytes = totalBytes,
            freeBytes = freeBytes,
            usedBytes = usedBytes,
            projectsBytes = projectsBytes,
            cacheBytes = cacheBytes,
            appDataBytes = appDataBytes,
            linuxRootfsBytes = linuxRootfsBytes,
            linuxHomeBytes = linuxHomeBytes,
            linuxTotalBytes = linuxTotalBytes
        )
    }

    fun clearAppCache(): Boolean {
        return runCatching {
            context.cacheDir.deleteRecursively()
            context.externalCacheDir?.deleteRecursively()
            true
        }.getOrDefault(false)
    }
}
