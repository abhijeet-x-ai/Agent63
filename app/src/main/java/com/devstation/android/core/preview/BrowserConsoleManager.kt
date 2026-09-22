package com.devstation.android.core.preview

import com.devstation.android.core.agent.SecretRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 9 §41: bounded, redacted browser console.
 *
 * Entries are size-capped, count-capped, and redacted before storage. Cookies and
 * authorization headers are never accepted into the store.
 */
class BrowserConsoleManager(
    private val maxEntries: Int = 500,
    private val maxMessageChars: Int = 2_000
) {
    private val _entries = MutableStateFlow<List<BrowserConsoleEntry>>(emptyList())
    val entries: StateFlow<List<BrowserConsoleEntry>> = _entries.asStateFlow()

    fun append(level: BrowserConsoleEntry.Level, message: String) {
        var text = SecretRedactor.redact(message.take(maxMessageChars))
        // Defensive: never persist credential-shaped text. Scheme redaction runs first so a
        // header replacement cannot consume "Bearer" and strand the token value.
        if (BEARER_TOKEN_REGEX.containsMatchIn(text)) {
            text = BEARER_TOKEN_REGEX.replace(text) { m ->
                "${m.groupValues[1]} [REDACTED]"
            }
        }
        if (COOKIE_HEADER_REGEX.containsMatchIn(text)) {
            text = COOKIE_HEADER_REGEX.replace(text, "[REDACTED]")
        }
        _entries.value = (_entries.value + BrowserConsoleEntry(level = level, message = text)).takeLast(maxEntries)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    companion object {
        val COOKIE_HEADER_REGEX = Regex(
            """(?i)(set-cookie|cookie|authorization)\s*[:=]\s*\S+"""
        )

        /**
         * §50: scheme-shaped credentials. The cookie/authorization regex only covers the
         * header form; a bare `Bearer <token>` after redaction would otherwise leak the
         * credential value itself into the console store. Applied BEFORE the header regex
         * so the header replacement cannot consume the scheme word and strand the token.
         */
        val BEARER_TOKEN_REGEX = Regex(
            """(?i)\b(bearer|basic|token|digest)\s+[A-Za-z0-9._~+/=~-]{6,}"""
        )
    }
}
