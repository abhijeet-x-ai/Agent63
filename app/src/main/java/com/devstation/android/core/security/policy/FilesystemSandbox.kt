package com.devstation.android.core.security.policy

import com.devstation.android.core.agent.PathSandbox
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Phase 7 §6/§7/§8/§9/§10: one sandbox for every agent filesystem operation.
 *
 * It does NOT re-implement path validation — it wraps [PathSandbox] (the Phase 6 rule set) and adds
 * Android-private path rejection, sensitive-file detection, cross-project isolation and a
 * fingerprint that is re-checked immediately before the operation runs (§50 TOCTOU).
 */
class FilesystemSandbox(
    private val sensitiveFiles: SensitiveFilePolicy = SensitiveFilePolicy()
) {

    sealed class Resolution {
        /** Path is inside the project root; [token] must be revalidated before the operation. */
        data class Allowed(
            val file: File,
            val token: SandboxToken,
            val sensitive: Boolean
        ) : Resolution()

        data class Rejected(val reason: String) : Resolution()
    }

    /** Resolve [raw] for [operation] inside [root]. Never throws; failures are structured. */
    fun resolve(root: File?, raw: String?, operation: SandboxOperation): Resolution {
        if (root == null) return Resolution.Rejected("No project is selected for this action.")
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return Resolution.Rejected("A path is required.")

        val file = try {
            when (operation) {
                SandboxOperation.DELETE, SandboxOperation.WRITE, SandboxOperation.RENAME, SandboxOperation.CREATE ->
                    PathSandbox.resolveModifiable(root, value)
                else -> PathSandbox.resolve(root, value)
            }
        } catch (e: PathSandbox.PathRejected) {
            // A path that is inside the project is accepted even when the project itself lives in
            // app storage; an absolute path anywhere else gets an explicit, specific refusal.
            return Resolution.Rejected(privatePathReason(value) ?: e.message ?: "Path rejected.")
        } catch (e: SecurityException) {
            return Resolution.Rejected(privatePathReason(value) ?: e.message ?: "Path rejected.")
        } catch (e: Exception) {
            return Resolution.Rejected("Path could not be validated: ${e.message ?: "unknown error"}")
        }

        // Cross-project containment (§9): the canonical target must be inside THIS root.
        if (!PathSandbox.isInside(root, file)) {
            return Resolution.Rejected("Access denied: '$value' belongs to another project.")
        }

        val sensitive = sensitiveFiles.isSensitive(file)
        return Resolution.Allowed(
            file = file,
            token = SandboxToken(
                canonicalPath = file.path,
                fingerprint = fingerprint(file),
                operation = operation,
                sensitive = sensitive
            ),
            sensitive = sensitive
        )
    }

    /**
     * §50: re-check the token right before executing. Returns null when the resource is unchanged,
     * otherwise a human-readable reason the operation must be blocked.
     *
     * The strength of the check matches the operation's risk:
     * - CREATE must still not exist (something else may have taken the name);
     * - WRITE/DELETE/RENAME must match the full fingerprint — existence, size, mtime and hash —
     *   because overwriting or removing a file that changed underneath the agent destroys work;
     * - read-only operations only require that the resource's existence has not changed.
     */
    fun revalidate(token: SandboxToken): String? {
        val current = fingerprint(File(token.canonicalPath))
        val expected = token.fingerprint

        if (expected.exists != current.exists) {
            return if (current.exists) {
                "Blocked: '${token.canonicalPath}' was created by something else while the agent was working."
            } else {
                "Blocked: '${token.canonicalPath}' no longer exists."
            }
        }
        if (!expected.exists) return null

        if (!isMutating(token.operation)) {
            // Reads cannot clobber anything, so a content change must not fail them; a *missing*
            // file is already caught above.
            return null
        }

        if (expected.size != current.size) {
            return "Blocked: '${token.canonicalPath}' changed size after the security check."
        }
        if (expected.lastModified != current.lastModified) {
            return "Blocked: '${token.canonicalPath}' was modified after the security check."
        }
        if (expected.contentHash != null && current.contentHash != null &&
            expected.contentHash != current.contentHash
        ) {
            return "Blocked: '${token.canonicalPath}' content changed after the security check."
        }
        return null
    }

    /** True for absolute paths that belong to Android, the system or the Keystore, not a project. */
    fun isAndroidPrivatePath(path: String): Boolean = privatePathReason(path) != null

    private fun privatePathReason(path: String): String? {
        val normalized = normalize(path) ?: return null
        val hit = PRIVATE_PREFIXES.firstOrNull { normalized == it || normalized.startsWith("$it/") }
            ?: return null
        return "Access denied: '$hit' is part of the Android system or app-private storage. " +
            "The agent may only access files inside the selected project."
    }

    /** Normalizes to a lowercase absolute path, or null when [path] is not absolute. */
    private fun normalize(path: String): String? {
        val trimmed = path.trim().replace('\\', '/')
        if (trimmed.isEmpty()) return null
        val withoutScheme = trimmed.removePrefix("file://")
        if (!withoutScheme.startsWith("/")) return null
        val collapsed = withoutScheme.split('/').filter { it.isNotEmpty() && it != "." }
        val resolved = mutableListOf<String>()
        collapsed.forEach { segment -> if (segment == "..") resolved.removeLastOrNull() else resolved.add(segment) }
        return "/" + resolved.joinToString("/").lowercase()
    }

    fun fingerprint(file: File): FileFingerprint {
        val exists = file.exists()
        val size = if (exists) runCatching { file.length() }.getOrDefault(0L) else 0L
        val modified = if (exists) runCatching { file.lastModified() }.getOrDefault(0L) else 0L
        val hash = if (exists && file.isFile && size in 0..MAX_HASH_BYTES) hashOf(file) else null
        return FileFingerprint(
            canonicalPath = canonicalPath(file),
            exists = exists,
            size = size,
            lastModified = modified,
            contentHash = hash
        )
    }

    private fun canonicalPath(file: File): String = runCatching { file.canonicalPath }
        .getOrElse { file.absolutePath }

    private fun hashOf(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: IOException) {
        null
    }

    /** Operations that can destroy or replace existing content need the full fingerprint check. */
    private fun isMutating(operation: SandboxOperation): Boolean =
        operation == SandboxOperation.WRITE ||
            operation == SandboxOperation.DELETE ||
            operation == SandboxOperation.RENAME

    private companion object {
        const val MAX_HASH_BYTES = 4L * 1024 * 1024

        /** §7: Android/system locations the agent must never reach. */
        val PRIVATE_PREFIXES = listOf(
            "/data",
            "/proc",
            "/sys",
            "/dev",
            "/vendor",
            "/system",
            "/apex",
            "/etc",
            "/root",
            "/sdcard",
            "/storage"
        )
    }
}
