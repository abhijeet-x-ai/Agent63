package com.devstation.android.core.git

import com.devstation.android.core.agent.SecretRedactor
import com.devstation.android.core.security.policy.NetworkSecurityPolicy
import com.devstation.android.core.security.policy.SensitiveFilePolicy
import java.io.File
import java.net.URI

/**
 * Phase 10 §5/§10/§16/§17/§33/§34/§36: Comprehensive Git Security Policy.
 *
 * Enforces:
 * 1. Project root containment (no ../ traversal, no symlink escape, no Android system paths).
 * 2. Neutralization of dangerous repository hooks and malicious .git/config directives.
 * 3. Strict HTTPS-only remote URL validation and SSRF loopback/private network defense.
 * 4. Identification of sensitive credential files requiring elevated confirmation.
 * 5. Explicit approval gates for destructive Git operations (reset --hard, clean, force push, branch -D).
 */
class GitSecurityPolicy(
    private val networkSecurityPolicy: NetworkSecurityPolicy = NetworkSecurityPolicy(),
    private val sensitiveFilePolicy: SensitiveFilePolicy = SensitiveFilePolicy()
) {

    companion object {
        val SYSTEM_DIR_PREFIXES = listOf(
            "/proc", "/sys", "/dev", "/vendor", "/system", "/apex", "/etc", "/root",
            "/data/misc", "/data/system", "/data/local", "/data/user", "/data/app",
            "/sbin", "/bin", "/usr", "/lib", "/lib64", "/opt", "/boot"
        )

        val SENSITIVE_FILE_NAMES = setOf(
            ".env", "credentials.json", "secrets.json", "google-services.json", "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa"
        )

        val SENSITIVE_EXTENSIONS = setOf(
            "pem", "key", "p12", "pfx", "keystore", "jks", "crt", "cer"
        )

        val DANGEROUS_CONFIG_DIRECTIVES = listOf(
            "sshcommand", "gitproxy", "credential.helper", "hookspath", "fsmonitor"
        )
    }

    // ---- 1. Path Containment & Protection (§5) ----

    /**
     * Verifies that a target repository or file path is strictly contained within [projectRoot].
     * Fails closed on path traversal, symlink escapes, or protected system paths.
     */
    fun validatePathWithinProject(projectRoot: File, targetPath: String): Result<File> {
        val normalizedTarget = targetPath.trim().replace('\\', '/')
        if (normalizedTarget.isBlank()) {
            return Result.failure(SecurityException("Path cannot be empty."))
        }

        // Prevent traversal characters in relative paths
        if (normalizedTarget.contains("../") || normalizedTarget == ".." || normalizedTarget.endsWith("/..")) {
            return Result.failure(SecurityException("Path traversal tokens ('..') are strictly prohibited: $normalizedTarget"))
        }

        // Reject system subtrees
        if (isSystemDirectory(normalizedTarget)) {
            return Result.failure(SecurityException("Access to system directory is strictly prohibited: $normalizedTarget"))
        }

        val resolved = if (File(normalizedTarget).isAbsolute) {
            File(normalizedTarget)
        } else {
            File(projectRoot, normalizedTarget)
        }

        val canonicalRoot = runCatching { projectRoot.canonicalFile }.getOrElse { projectRoot.absoluteFile }
        val canonicalResolved = runCatching { resolved.canonicalFile }.getOrElse { resolved.absoluteFile }

        val rootPath = canonicalRoot.path.replace('\\', '/').trimEnd('/')
        val resolvedPath = canonicalResolved.path.replace('\\', '/')

        if (resolvedPath != rootPath && !resolvedPath.startsWith("$rootPath/")) {
            return Result.failure(
                SecurityException("Path escapes project containment: '${SecretRedactor.redact(targetPath)}' is outside '${SecretRedactor.redact(canonicalRoot.path)}'")
            )
        }

        return Result.success(canonicalResolved)
    }

    /** Returns true if path canonicalizes into a protected Android or Unix system directory. */
    fun isSystemDirectory(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        val canonical = runCatching { File(path).canonicalPath.replace('\\', '/') }.getOrDefault(normalized)
        val withoutDrive = if (canonical.length >= 2 && canonical[1] == ':') canonical.substring(2) else canonical
        return SYSTEM_DIR_PREFIXES.any {
            normalized == it || normalized.startsWith("$it/") ||
            withoutDrive == it || withoutDrive.startsWith("$it/")
        }
    }

    // ---- 2. Sensitive File Detection (§34) ----

    /** Returns true if a file normally contains credentials, private keys, or environment secrets. */
    fun isSensitiveFile(filePath: String): Boolean {
        val name = filePath.replace('\\', '/').substringAfterLast('/').lowercase()
        if (name in SENSITIVE_FILE_NAMES) return true
        if (name.startsWith(".env.") || name.startsWith("service-account") && name.endsWith(".json")) return true
        val ext = name.substringAfterLast('.', "")
        if (ext in SENSITIVE_EXTENSIONS) return true
        return sensitiveFilePolicy.isSensitive(File(name))
    }

    // ---- 3. Remote URL & Network Validation (§16/§20/§36) ----

    /**
     * Validates remote URLs. Only HTTPS remotes are allowed.
     * Rejects insecure schemes (file://, git://, ssh://, http://, javascript://) and SSRF targets.
     */
    fun validateRemoteUrl(rawUrl: String): Result<String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(SecurityException("Remote URL cannot be empty."))
        }

        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return Result.failure(SecurityException("Invalid remote URL format."))

        val scheme = uri.scheme?.lowercase()
            ?: return Result.failure(SecurityException("Remote URL must include an explicit scheme."))

        if (scheme != "https") {
            return Result.failure(
                SecurityException("Only HTTPS remote repositories are permitted in Phase 10. Scheme '$scheme://' is rejected.")
            )
        }

        val host = uri.host?.lowercase()
            ?: return Result.failure(SecurityException("Remote URL has no valid host."))

        // Reject loopback and private/metadata addresses (SSRF defense)
        if (isDisallowedRemoteHost(host)) {
            return Result.failure(
                SecurityException("Remote URL destination '$host' is not allowed (private or loopback network target).")
            )
        }

        return Result.success(trimmed)
    }

    private fun isDisallowedRemoteHost(host: String): Boolean {
        val cleanHost = host.trim('[', ']').lowercase()
        if (cleanHost == "localhost" || cleanHost.endsWith(".localhost")) return true
        if (cleanHost == "127.0.0.1" || cleanHost == "::1" || cleanHost == "0.0.0.0") return true
        if (cleanHost == "169.254.169.254") return true // Cloud metadata
        if (cleanHost.startsWith("10.") || cleanHost.startsWith("192.168.")) return true
        if (cleanHost.startsWith("172.") && isPrivate172(cleanHost)) return true
        // Check for numeric decimal/hex IPv4 representations
        val numericIp = parseNumericIpv4(cleanHost)
        if (numericIp != null) {
            val b0 = numericIp[0].toInt() and 0xFF
            val b1 = numericIp[1].toInt() and 0xFF
            if (b0 == 127 || b0 == 10 || (b0 == 192 && b1 == 168) || (b0 == 172 && b1 in 16..31) || (b0 == 169 && b1 == 254)) {
                return true
            }
        }
        return false
    }

    private fun isPrivate172(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val second = parts[1].toIntOrNull() ?: return false
        return second in 16..31
    }

    private fun parseNumericIpv4(host: String): ByteArray? {
        fun parsePart(part: String): Long? = when {
            part.isEmpty() -> null
            part.startsWith("0x") || part.startsWith("0X") -> part.substring(2).toLongOrNull(16)
            part.length > 1 && part.startsWith("0") -> part.toLongOrNull(8)
            part.all { it.isDigit() } -> part.toLongOrNull(10)
            else -> null
        }?.takeIf { it in 0..255 }

        if (host.none { it == '.' }) {
            val value = when {
                host.startsWith("0x") || host.startsWith("0X") -> host.substring(2).toLongOrNull(16)
                host.all { it.isDigit() } -> host.toLongOrNull(10)
                else -> null
            } ?: return null
            if (value !in 0..0xFFFFFFFFL) return null
            return byteArrayOf(
                ((value ushr 24) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte()
            )
        }
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = parts.map { parsePart(it) ?: return null }
        return byteArrayOf(bytes[0].toByte(), bytes[1].toByte(), bytes[2].toByte(), bytes[3].toByte())
    }

    // ---- 4. Repository Configuration Sanitization (§17) ----

    /**
     * Inspects a repository's `.git/config` file for dangerous external execution directives.
     * Returns a list of identified threats, or empty if clean.
     */
    fun auditRepositoryConfig(repoDir: File): List<String> {
        val configFile = File(repoDir, ".git/config")
        if (!configFile.exists() || !configFile.canRead()) return emptyList()

        val findings = mutableListOf<String>()
        val lines = runCatching { configFile.readLines() }.getOrDefault(emptyList())

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            val lower = trimmed.lowercase()
            for (dangerous in DANGEROUS_CONFIG_DIRECTIVES) {
                if (lower.contains(dangerous)) {
                    findings.add("Dangerous git config directive detected: '$trimmed'")
                }
            }
            if (lower.startsWith("alias.") && (lower.contains("!") || lower.contains("sh") || lower.contains("bash"))) {
                findings.add("Dangerous shell alias detected in git config: '$trimmed'")
            }
        }
        return findings
    }

    /**
     * Returns standard security overrides passed via `-c key=value` to ensure
     * repository configuration cannot hijack execution.
     */
    fun buildSecurityConfigOverrides(allowHooks: Boolean = false): List<String> {
        val overrides = mutableListOf<String>()
        overrides.add("-c")
        overrides.add("core.sshCommand=false")
        overrides.add("-c")
        overrides.add("core.fsmonitor=false")
        overrides.add("-c")
        overrides.add("credential.helper=")
        if (!allowHooks) {
            overrides.add("-c")
            overrides.add("core.hooksPath=/dev/null")
        }
        return overrides
    }

    // ---- 5. Destructive Operations Identification (§33) ----

    /**
     * Returns true if the Git arguments represent a destructive or broad operation
     * that can discard uncommitted changes or rewrite history.
     */
    fun isDestructiveOperation(args: List<String>): Boolean {
        if (args.isEmpty()) return false
        val cmd = args.first().lowercase()
        val allArgs = args.joinToString(" ").lowercase()

        return when (cmd) {
            "reset" -> allArgs.contains("--hard") || allArgs.contains("--merge")
            "clean" -> true
            "checkout", "restore" -> allArgs.contains("-- .") || allArgs.contains(" .") || allArgs.contains("--force") || allArgs.contains("-f")
            "push" -> allArgs.contains("--force") || allArgs.contains("-f") || allArgs.contains("--force-with-lease")
            "branch" -> allArgs.contains("-d") || (allArgs.contains("-d") && allArgs.contains("-r"))
            else -> false
        }
    }
}
