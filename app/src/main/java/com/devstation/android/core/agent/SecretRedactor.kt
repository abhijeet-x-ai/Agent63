package com.devstation.android.core.agent

/**
 * Redacts obvious secret material from tool output.
 *
 * Best-effort by design — the system prompt still instructs the model to treat tool output as
 * untrusted data. It reduces accidental credential leakage; it cannot be perfect, and it is
 * never the only defense (tools also have no credential-store reference at all).
 */
object SecretRedactor {

    const val MASK = "[REDACTED]"

    private val patterns: List<Regex> = listOf(
        // Authorization headers / bearer tokens
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{4,}"""),
        // Well-known API key shapes
        Regex("""\bsk-ant-[A-Za-z0-9_\-]{8,}"""),
        Regex("""\bsk-[A-Za-z0-9_\-]{12,}"""),
        Regex("""\bAIza[0-9A-Za-z_\-]{20,}"""),
        Regex("""\bghp_[A-Za-z0-9]{16,}"""),
        Regex("""\bgithub_pat_[A-Za-z0-9_]{16,}"""),
        Regex("""\bxox[baprs]-[A-Za-z0-9-]{8,}"""),
        Regex("""\bAKIA[0-9A-Z]{12,}"""),
        // key = value / key: value assignments for sensitive names
        Regex(
            """(?i)\b(api[_-]?key|apikey|secret|client[_-]?secret|password|passwd|access[_-]?token|refresh[_-]?token|auth[_-]?token|authorization|x-api-key|x-goog-api-key)(\s*[:=]\s*)("?)([^\s"',;]{4,})\3"""
        ),
        // PEM private key blocks
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----""")
    )

    /** Redact all recognized secrets in [text]. */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        for (pattern in patterns) {
            result = pattern.replace(result) { match ->
                when (match.groups.size) {
                    // key/value form: keep the key name, mask only the value
                    5 -> "${match.groupValues[1]}${match.groupValues[2]}$MASK"
                    // everything else: mask the whole match
                    else -> MASK
                }
            }
        }
        return result
    }

    /** True when [text] appears to still contain secret-looking material (used in tests). */
    fun containsSuspectedSecret(text: String): Boolean = patterns.any { it.containsMatchIn(text) }
}
