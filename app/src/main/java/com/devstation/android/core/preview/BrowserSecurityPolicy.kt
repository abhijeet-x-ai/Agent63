package com.devstation.android.core.preview

import com.devstation.android.core.agent.SecretRedactor
import com.devstation.android.core.security.policy.NetworkIntent
import com.devstation.android.core.security.policy.NetworkSecurityPolicy
import java.net.InetAddress
import java.net.URI

/**
 * Phase 9 §6/§7/§9/§10/§37/§39/§40/§46/§63/§64: browser URL + download security policy.
 *
 * Host handling never relies on string prefixes: URLs are parsed with [URI] and hosts are
 * resolved to [InetAddress] so that decimal/octal/hex IPv4 spellings, IPv6 forms and
 * `localhost.evil.example`-style spoofs cannot bypass classification. `NetworkSecurityPolicy`
 * remains authoritative for host intent.
 */
class BrowserSecurityPolicy(
    private val networkPolicy: NetworkSecurityPolicy = NetworkSecurityPolicy(),
    /** Allow plain HTTP for non-loopback hosts (ordinary browsing) — security state stays visible. */
    private val allowPlainHttp: Boolean = true
) {

    /** How a navigation request was classified. */
    enum class NavigationKind { EXTERNAL_HTTPS, EXTERNAL_HTTP, LOCAL_PREVIEW, BLOCKED }

    data class NavigationDecision(
        val kind: NavigationKind,
        val securityState: BrowserSecurityState,
        /** Safe-to-display URL (query/user info stripped). */
        val displayUrl: String,
        val reason: String? = null
    )

    /** Classify a top-level navigation. Fail closed on anything unparseable or unclassified. */
    fun evaluateNavigation(rawUrl: String): NavigationDecision {
        val display = networkPolicy.redact(rawUrl)
        val uri = try {
            URI(rawUrl.trim())
        } catch (e: Exception) {
            return NavigationDecision(NavigationKind.BLOCKED, BrowserSecurityState.BLOCKED, display, "Invalid URL.")
        }
        val scheme = uri.scheme?.lowercase()
            ?: return NavigationDecision(NavigationKind.BLOCKED, BrowserSecurityState.BLOCKED, display, "URL has no scheme.")
        if (scheme != "http" && scheme != "https") {
            return NavigationDecision(
                NavigationKind.BLOCKED, BrowserSecurityState.BLOCKED, display,
                "Scheme '$scheme:' is not allowed in the browser."
            )
        }
        val host = uri.host?.lowercase()
            ?: return NavigationDecision(NavigationKind.BLOCKED, BrowserSecurityState.BLOCKED, display, "URL has no host.")

        val intent = classifyHost(host)

        return when {
            intent == NetworkIntent.LOCAL_NETWORK && isLoopbackHost(host) -> {
                // §9: local preview is its own category — never "publicly trusted".
                NavigationDecision(
                    NavigationKind.LOCAL_PREVIEW, BrowserSecurityState.LOCAL_PREVIEW, display,
                    "Local development preview."
                )
            }
            intent == NetworkIntent.LOCAL_NETWORK -> {
                // §10: LAN/private targets are a separate, visible class.
                NavigationDecision(
                    if (scheme == "https") NavigationKind.EXTERNAL_HTTPS else NavigationKind.EXTERNAL_HTTP,
                    BrowserSecurityState.WARNING, display,
                    "Private/LAN address — treat with caution."
                )
            }
            scheme == "https" -> NavigationDecision(NavigationKind.EXTERNAL_HTTPS, BrowserSecurityState.SECURE, display)
            allowPlainHttp -> NavigationDecision(NavigationKind.EXTERNAL_HTTP, BrowserSecurityState.HTTP, display)
            else -> NavigationDecision(
                NavigationKind.BLOCKED, BrowserSecurityState.BLOCKED, display, "Plaintext HTTP is not permitted."
            )
        }
    }

    /**
     * §37/§16: re-evaluate a redirect destination independently. The original decision never
     * carries over.
     */
    fun evaluateRedirect(originalUrl: String, locationHeader: String): NavigationDecision {
        val resolved = try {
            URI(originalUrl).resolve(locationHeader)
        } catch (e: Exception) {
            return NavigationDecision(
                NavigationKind.BLOCKED, BrowserSecurityState.BLOCKED,
                networkPolicy.redact(locationHeader), "Invalid redirect target."
            )
        }
        return evaluateNavigation(resolved.toString())
    }

    /** Security state for the currently displayed URL (§46). */
    fun securityStateFor(rawUrl: String): BrowserSecurityState =
        evaluateNavigation(rawUrl).securityState

    // ---- host classification (§39/§40) ----

    /**
     * True only when the parsed host IS loopback. Proper parsing defeats:
     * `localhost.evil.example`, `127.1`, decimal `2130706433`, octal `0177.0.0.1`,
     * hex `0x7f000001`, IPv6-mapped `[::ffff:127.0.0.1]`.
     */
    fun isLoopbackHost(host: String): Boolean {
        val address = resolveHost(host) ?: return false
        return address.isLoopbackAddress
    }

    /**
     * Classify a host through the existing [NetworkSecurityPolicy], but harden with real IP
     * resolution so unusual representations cannot slip past string-prefix classification.
     */
    fun classifyHost(host: String): NetworkIntent {
        val address = resolveHost(host)
        if (address != null) {
            return when {
                address.isLoopbackAddress -> NetworkIntent.LOCAL_NETWORK
                address.isAnyLocalAddress -> NetworkIntent.LOCAL_NETWORK
                address.isLinkLocalAddress -> NetworkIntent.LOCAL_NETWORK
                address.isSiteLocalAddress -> NetworkIntent.LOCAL_NETWORK
                // Everything else (public unicast) is INTERNET.
                else -> NetworkIntent.INTERNET
            }
        }
        // Unresolvable host: fall back to the string classifier (covers .local names etc.).
        return networkPolicy.classifyHost(host)
    }

    private fun resolveHost(host: String): InetAddress? = runCatching {
        val normalized = host.trim().trim('[', ']')
        if (normalized.isEmpty()) return null
        // SSRF hardening (§60): Java's InetAddress does NOT parse decimal/octal/hex IPv4
        // spellings — resolve them ourselves so `2130706433`, `0177.0.0.1`, `0x7f000001`
        // are correctly recognised as loopback instead of falling through to DNS/internet.
        parseNumericIpv4(normalized)?.let { return InetAddress.getByAddress(it) }
        // Literal IP first (no DNS): covers dotted-quad IPv4 and standard IPv6 forms.
        InetAddress.getByName(normalized)
    }.getOrNull()

    /** Parse non-standard IPv4 forms (decimal u32, hex u32, mixed-radix dotted quad). */
    private fun parseNumericIpv4(host: String): ByteArray? {
        fun parsePart(part: String, max: Long = 255): Long? = when {
            part.isEmpty() -> null
            part.startsWith("0x") || part.startsWith("0X") -> part.substring(2).toLongOrNull(16)
            part.length > 1 && part.startsWith("0") -> part.toLongOrNull(8)
            part.all { it.isDigit() } -> part.toLongOrNull(10)
            else -> null
        }?.takeIf { it in 0..max }

        // Whole-address forms: decimal or hex 32-bit value.
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
        // Dotted forms with mixed radix parts (e.g. 0177.0.0.1).
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = parts.mapIndexed { i, p -> parsePart(p) ?: return null }
        return byteArrayOf(bytes[0].toByte(), bytes[1].toByte(), bytes[2].toByte(), bytes[3].toByte())
    }

    // ---- download policy (§36/§64) ----

    private val dangerousExtensions = setOf(
        "exe", "bat", "cmd", "sh", "apk", "jar", "dex", "so", "bin", "msi", "vbs", "ps1",
        "com", "scr", "hta"
    )

    /** Max accepted download size (50 MB) — oversized payloads are rejected, not buffered. */
    val maxDownloadBytes: Long = 50L * 1024 * 1024

    fun evaluateDownload(request: DownloadRequest): DownloadDecision {
        if (request.contentLength > maxDownloadBytes) {
            return DownloadDecision.Rejected("Download exceeds the ${maxDownloadBytes / (1024 * 1024)} MB limit.")
        }
        val rawName = request.contentDisposition?.substringAfter("filename=", "")?.trim('"', ' ')
            ?: request.url.substringAfterLast('/').substringBefore('?')
        // §64: path components, traversal and scheme-like prefixes are rejected on the RAW
        // filename — never "sanitized away". A server-supplied `../../.env` or `/etc/passwd`
        // signals a hostile attachment, not a name to clean up.
        if (rawName.contains('/') || rawName.contains('\\') || rawName.contains("..") ||
            rawName.startsWith(".") || rawName.contains(':')
        ) {
            return DownloadDecision.Rejected("Unsafe download filename.")
        }
        val name = sanitizeFilename(rawName)
        if (name.isBlank()) {
            return DownloadDecision.Rejected("Download has no usable filename.")
        }
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension in dangerousExtensions) {
            return DownloadDecision.Rejected("Executable downloads are not permitted.")
        }
        return DownloadDecision.Allowed(name)
    }

    /** Strip path components and control characters from a server-supplied filename. */
    fun sanitizeFilename(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._ ()-]"), "_").take(120).trim()

    /** Redact a URL for console/log storage (§41/§50). */
    fun redactForLogs(url: String): String = SecretRedactor.redact(networkPolicy.redact(url))
}
