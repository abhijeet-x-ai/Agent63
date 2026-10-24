package com.devstation.android.core.remote

import java.security.MessageDigest

/**
 * Phase 11: SSH host-key trust (TOFU — trust on first use).
 *
 * Pure JVM logic: fingerprints are computed from the SSH wire encoding of
 * the server's public key (the byte blob sshj hands to its host-key
 * verifier), formatted exactly the way OpenSSH displays them:
 * `SHA256:<base64 without padding>`.
 *
 * The trust decision mirrors the Phase 11 security boundary:
 *  - no stored fingerprint  -> UNKNOWN (user must explicitly trust)
 *  - stored == presented    -> TRUSTED (connect proceeds)
 *  - stored != presented    -> CHANGED (connection must fail closed)
 */
object HostKeyFingerprints {

    private const val PREFIX = "SHA256:"

    /** OpenSSH-style SHA-256 fingerprint of an SSH public-key blob. */
    fun sha256Fingerprint(sshPublicKeyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sshPublicKeyBlob)
        return PREFIX + base64NoPadding(digest)
    }

    /** Canonical form used for storage and comparison. */
    fun normalize(raw: String): String = raw.trim()

    /** Constant-time comparison so fingerprint checks do not leak timing. */
    fun matches(stored: String?, presented: String): Boolean {
        if (stored == null) return false
        val a = normalize(stored).toByteArray(Charsets.UTF_8)
        val b = normalize(presented).toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(a, b)
    }

    /**
     * Standard-alphabet Base64 without padding and without line breaks,
     * implemented manually so this file stays pure JVM (java.util.Base64 is
     * unavailable below API 26 and android.util.Base64 is unavailable in
     * local unit tests).
     */
    internal fun base64NoPadding(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(alphabet[(n shr 18) and 0x3F])
            out.append(alphabet[(n shr 12) and 0x3F])
            out.append(alphabet[(n shr 6) and 0x3F])
            out.append(alphabet[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                out.append(alphabet[(n shr 18) and 0x3F])
                out.append(alphabet[(n shr 12) and 0x3F])
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(alphabet[(n shr 18) and 0x3F])
                out.append(alphabet[(n shr 12) and 0x3F])
                out.append(alphabet[(n shr 6) and 0x3F])
            }
        }
        return out.toString()
    }
}

/** Trust state of a presented host key against what is stored for the host. */
enum class HostKeyTrustState {
    UNKNOWN,
    TRUSTED,
    CHANGED
}

/** Classifies a presented fingerprint against the stored one (TOFU rules). */
fun hostKeyTrustState(storedFingerprint: String?, presentedFingerprint: String): HostKeyTrustState {
    if (storedFingerprint.isNullOrBlank()) return HostKeyTrustState.UNKNOWN
    return if (HostKeyFingerprints.matches(storedFingerprint, presentedFingerprint)) {
        HostKeyTrustState.TRUSTED
    } else {
        HostKeyTrustState.CHANGED
    }
}
