package com.devstation.android.core.remote

import java.io.File

/** Progress snapshot while a sync runs. */
data class SyncProgress(
    val totalFiles: Int,
    val completedFiles: Int,
    val currentPath: String?,
    val failedPaths: List<String>
)

/**
 * Phase 11: executes local → remote sync over one SFTP channel.
 *
 * Planning is pure ([SyncPlanner]); this class owns the I/O: recursive
 * listings on both sides and the uploads themselves. Local paths are plain
 * files under the project root the caller resolved through the sandbox;
 * remote paths are normalized and any `..` escape is refused.
 */
class SftpSyncEngine(
    private val sftp: SshSftpChannel
) {

    /** Recursive listing of a local directory as sync-relative file infos. */
    fun listLocal(root: File): List<SyncFileInfo> {
        if (!root.exists()) return emptyList()
        val base = root.canonicalFile
        val out = mutableListOf<SyncFileInfo>()
        root.walkTopDown().forEach { file ->
            if (file == root) return@forEach
            val relative = file.canonicalFile.relativeTo(base).path.replace(File.separatorChar, '/')
            out += SyncFileInfo(
                relativePath = relative,
                sizeBytes = if (file.isDirectory) 0L else file.length(),
                modifiedAtMs = file.lastModified(),
                isDirectory = file.isDirectory
            )
        }
        return out
    }

    /** Recursive listing of a remote directory; empty when it does not exist yet. */
    fun listRemoteRecursive(remoteRoot: String): List<SyncFileInfo> {
        // Normalize "~/…" and friends to the same form uploads will use.
        val startDir = joinRemotePath(remoteRoot, ".")
        if (sftp.stat(startDir) == null) return emptyList()
        val out = mutableListOf<SyncFileInfo>()
        val pending = ArrayDeque<Pair<String, String>>()
        pending.addLast(startDir to "")
        while (pending.isNotEmpty()) {
            val (dir, prefix) = pending.removeLast()
            val entries = runCatching { sftp.list(dir) }.getOrDefault(emptyList())
            for (entry in entries) {
                if (entry.relativePath == "." || entry.relativePath == "..") continue
                val relative = if (prefix.isEmpty()) entry.relativePath else "$prefix/${entry.relativePath}"
                out += entry.copy(relativePath = relative)
                if (entry.isDirectory) {
                    pending.addLast(joinRemotePath(remoteRoot, relative) to relative)
                }
            }
        }
        return out
    }

    /**
     * Uploads every file in [plan] from [localRoot] to [remoteRoot].
     * Returns the list of paths that failed (empty = full success); one bad
     * file never aborts the rest.
     */
    fun execute(
        localRoot: File,
        remoteRoot: String,
        plan: SyncPlan,
        onProgress: (SyncProgress) -> Unit = {}
    ): List<String> {
        val failed = mutableListOf<String>()
        var completed = 0
        onProgress(SyncProgress(plan.items.size, 0, null, emptyList()))
        for (item in plan.items) {
            val target = joinRemotePath(remoteRoot, item.relativePath)
            try {
                val parent = target.substringBeforeLast('/', "")
                if (parent.isNotEmpty()) sftp.mkdirs(parent)
                sftp.upload(File(localRoot, item.relativePath), target)
                completed++
            } catch (t: Throwable) {
                failed += item.relativePath
            }
            onProgress(SyncProgress(plan.items.size, completed, item.relativePath, failed.toList()))
        }
        return failed
    }

    /**
     * Joins a user-supplied remote root (absolute, or `~/`-relative to the
     * server home directory) with a sync-relative path. The planner already
     * rejected `..` in relative paths; normalization here is the second gate.
     */
    internal fun joinRemotePath(remoteRoot: String, relative: String): String {
        val homeRelative = remoteRoot.startsWith("~/")
        val rootPart = remoteRoot.removePrefix("~/").removePrefix("/")
        val joined = SyncPlanner.normalizePath("$rootPart/$relative")
            ?: throw IllegalArgumentException("Invalid remote path: $relative")
        return when {
            homeRelative -> joined
            remoteRoot.startsWith("/") -> "/$joined"
            else -> joined
        }
    }
}
