package com.devstation.android.core.remote

/**
 * Phase 11: pure sync planning for `syncLocalToRemote`.
 *
 * Given listings of the local project and the remote directory, computes the
 * minimal set of files to upload. No I/O happens here — the SFTP engine feeds
 * listings in and executes the plan — which keeps every rule unit-testable:
 *
 *  - a local file is uploaded when it is NEW, its SIZE_CHANGED, or it is
 *    NEWER than the remote copy (with a 2 s tolerance, because SFTP stores
 *    mtimes at one-second granularity)
 *  - paths with excluded segments (`.git`, build output, …) are skipped
 *  - files over the size cap, absolute paths, and `..` escapes are rejected
 *    and reported in [SyncPlan.rejectedPaths] instead of being uploaded
 */
object SyncPlanner {

    const val DEFAULT_MAX_FILE_BYTES: Long = 50L * 1024 * 1024

    const val MTIME_TOLERANCE_MS: Long = 2_000L

    val DEFAULT_EXCLUDED_SEGMENTS: Set<String> = setOf(
        ".git", ".gradle", "build", "node_modules", ".idea"
    )

    fun plan(
        localFiles: List<SyncFileInfo>,
        remoteFiles: List<SyncFileInfo>,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
        excludedSegments: Set<String> = DEFAULT_EXCLUDED_SEGMENTS
    ): SyncPlan {
        val remoteByPath = remoteFiles
            .filter { !it.isDirectory }
            .associateBy { it.relativePath }
        val items = mutableListOf<SyncItem>()
        val rejected = mutableListOf<String>()
        var skipped = 0

        for (local in localFiles) {
            if (local.isDirectory) continue
            val path = local.relativePath
            val segments = path.split('/')
            when {
                path.isBlank() || path.startsWith('/') || segments.any { it == ".." || it.isEmpty() } ->
                    rejected += path
                segments.any { it in excludedSegments } -> skipped++
                local.sizeBytes > maxFileBytes -> rejected += path
                else -> {
                    val remote = remoteByPath[path]
                    when {
                        remote == null ->
                            items += SyncItem(path, local.sizeBytes, SyncItem.Reason.NEW)
                        remote.sizeBytes != local.sizeBytes ->
                            items += SyncItem(path, local.sizeBytes, SyncItem.Reason.SIZE_CHANGED)
                        local.modifiedAtMs > remote.modifiedAtMs + MTIME_TOLERANCE_MS ->
                            items += SyncItem(path, local.sizeBytes, SyncItem.Reason.NEWER)
                        else -> skipped++
                    }
                }
            }
        }

        items.sortBy { it.relativePath }
        return SyncPlan(
            items = items,
            totalBytes = items.sumOf { it.sizeBytes },
            skippedCount = skipped,
            rejectedPaths = rejected
        )
    }

    /**
     * Normalizes a `/`-separated path: resolves `.` and `..` segments.
     * Returns null when the path would escape above its root or is blank —
     * callers must treat null as "refuse this path".
     */
    fun normalizePath(path: String): String? {
        if (path.isBlank()) return null
        val out = mutableListOf<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (out.isEmpty()) return null
                    out.removeAt(out.lastIndex)
                }
                else -> out += segment
            }
        }
        return if (out.isEmpty()) null else out.joinToString("/")
    }

    /**
     * Joins a remote root and a relative path into one normalized absolute
     * remote path, or null when the relative part escapes the root.
     */
    fun joinRemote(root: String, relative: String): String? {
        val cleanRoot = normalizePath(root.removePrefix("/")) ?: return null
        val cleanRelative = normalizePath(relative) ?: return null
        if (relative.split('/').any { it == ".." }) return null
        return "/$cleanRoot/$cleanRelative"
    }
}
