package com.devstation.android.core.security.policy

import java.io.File

/**
 * Phase 7 §11: conservative detection of files that usually hold secrets.
 *
 * Filenames are *not* the only source of secrets, so this policy:
 * - also matches well-known sensitive directories and DevStation's own internal state,
 * - is intentionally over-inclusive (a false positive only costs one approval),
 * - never decides on content, only flags that extra scrutiny is required.
 */
class SensitiveFilePolicy {

    /** True when [file] (by name, extension or location) may hold credentials. */
    fun isSensitive(file: File): Boolean = isSensitivePath(file.name) || isSensitiveDirectory(file)

    fun isSensitivePath(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.isBlank()) return false
        if (lower in EXACT_NAMES) return true
        if (lower.startsWith(".env")) return true
        if (lower.startsWith("id_rsa") || lower.startsWith("id_ed25519") || lower.startsWith("id_dsa")) return true
        if (lower.startsWith("service-account")) return true
        return SENSITIVE_EXTENSIONS.any { lower.endsWith(it) }
    }

    /** A directory that holds secret material, or DevStation's own protected state. */
    fun isSensitiveDirectory(file: File): Boolean {
        val parts = file.absolutePath.split(File.separatorChar).map { it.lowercase() }
        return parts.any { it in SENSITIVE_DIRECTORIES }
    }

    /**
     * Files whose contents must never be persisted into history, logs or audit events (§52),
     * even when the user approves reading them.
     */
    fun isTransientOnly(file: File): Boolean = isSensitive(file)

    companion object {
        /** DevStation's own internal per-project directory — always protected. */
        const val INTERNAL_DIR = ".devstation"

        private val EXACT_NAMES = setOf(
            ".env",
            ".env.local",
            ".env.production",
            ".env.development",
            ".npmrc",
            ".netrc",
            ".pgpass",
            ".git-credentials",
            "credentials",
            "credentials.json",
            "credentials.xml",
            "secrets.json",
            "secrets.yaml",
            "secrets.yml",
            "keystore",
            "keystore.jks",
            "truststore.jks",
            "known_hosts",
            "id_rsa",
            "id_ed25519",
            "devstation_secure_prefs"
        )

        private val SENSITIVE_EXTENSIONS = listOf(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".jceks", ".pkcs12", ".asc", ".gpg"
        )

        private val SENSITIVE_DIRECTORIES = setOf(
            ".devstation",
            ".ssh",
            ".gnupg",
            ".aws",
            ".kube"
        )
    }
}
