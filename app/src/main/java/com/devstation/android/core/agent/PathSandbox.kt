package com.devstation.android.core.agent

import java.io.File
import java.io.IOException

/**
 * Phase 6 security core: every path an AI tool touches must be proven to live inside the
 * current project root.
 *
 * Defends against:
 * - `../../outside-project` traversal
 * - absolute paths (`/etc/passwd`, `/data/data/...`)
 * - symlink escape (a link inside the project pointing outside)
 * - deletion/modification of the project root itself and of DevStation's internal state
 */
object PathSandbox {

    /** DevStation's per-project internal directory (recovery snapshots etc.). Never writable by AI. */
    const val INTERNAL_DIR = ".devstation"

    /** A rejected path. Tools convert this into a structured [ToolResult.Error]. */
    class PathRejected(message: String) : SecurityException(message)

    /**
     * Resolve [input] (relative to [root], or absolute) and assert containment.
     * @throws PathRejected when the resolved path escapes the project root.
     */
    fun resolve(root: File, input: String): File {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw PathRejected("A path is required.")
        if (trimmed.indexOf('\u0000') >= 0) throw PathRejected("Invalid path: contains a null byte.")
        if (trimmed.length > MAX_PATH_LENGTH) throw PathRejected("Invalid path: too long.")

        val rootCanonical = canonicalRoot(root)
        val candidate = if (isAbsolute(trimmed)) File(trimmed) else File(rootCanonical, trimmed)
        val resolved = canonical(candidate)

        if (!isInside(rootCanonical, resolved)) {
            throw PathRejected("Access denied: '$input' is outside the project root.")
        }
        return resolved
    }

    /** Resolve and additionally require that the target is not the root or DevStation-internal. */
    fun resolveModifiable(root: File, input: String): File {
        val rootCanonical = canonicalRoot(root)
        val resolved = resolve(root, input)
        if (resolved.path == rootCanonical.path) {
            throw PathRejected("Access denied: the project root itself cannot be modified or deleted.")
        }
        if (containsInternalSegment(rootCanonical, resolved)) {
            throw PathRejected("Access denied: '$INTERNAL_DIR' is DevStation-internal state.")
        }
        return resolved
    }

    fun isInside(root: File, target: File): Boolean {
        val rootPath = canonical(root).path
        val targetPath = canonical(target).path
        if (targetPath == rootPath) return true
        return targetPath.startsWith(rootPath.trimEnd(File.separatorChar) + File.separator)
    }

    private fun containsInternalSegment(root: File, target: File): Boolean {
        val relative = try {
            target.relativeTo(root).path
        } catch (_: IllegalArgumentException) {
            return true
        }
        return relative.split(File.separatorChar).any { it == INTERNAL_DIR }
    }

    private fun canonicalRoot(root: File): File {
        val canonicalRoot = canonical(root)
        if (!canonicalRoot.exists() || !canonicalRoot.isDirectory) {
            throw PathRejected("Project root is not available.")
        }
        return canonicalRoot
    }

    private fun canonical(file: File): File = try {
        file.canonicalFile
    } catch (_: IOException) {
        file.absoluteFile
    }

    private fun isAbsolute(path: String): Boolean =
        path.startsWith("/") || (path.length > 2 && path[1] == ':' && (path[2] == '\\' || path[2] == '/'))

    private const val MAX_PATH_LENGTH = 4096
}
