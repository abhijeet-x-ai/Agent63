package com.devstation.android.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 11: Non-secret metadata for saved SSH hosts (Remote Workspace).
 *
 * CRITICAL SECURITY INVARIANT:
 * Passwords, private keys, and key passphrases are NEVER stored in Room,
 * SQLite, or SharedPreferences. Secrets live exclusively in
 * [com.devstation.android.core.security.SecureCredentialStore] (Android
 * Keystore AES-256-GCM) under aliases `ssh/<id>/password`,
 * `ssh/<id>/key`, `ssh/<id>/keyPassphrase`. This entity stores only the
 * connection profile and the server's *public* host-key fingerprint
 * (TOFU trust anchor — public information, safe to persist).
 */
@Entity(
    tableName = "ssh_hosts",
    indices = [
        Index(value = ["host"]),
        Index(value = ["name"])
    ]
)
data class SshHostEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    /** "PASSWORD" or "PRIVATE_KEY" — see SshAuthType. */
    val authType: String = "PASSWORD",
    /** OpenSSH-style SHA256 fingerprint of the trusted server host key, or null before first trust. */
    val hostKeyFingerprint: String? = null,
    /** SSH host key algorithm name (e.g. "ssh-ed25519"), informational. */
    val hostKeyType: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null
)
