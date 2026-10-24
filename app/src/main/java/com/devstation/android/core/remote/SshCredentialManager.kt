package com.devstation.android.core.remote

import com.devstation.android.core.security.SecureCredentialStore

/** Private-key material loaded from the credential store for one connect. */
data class SshKeyMaterial(
    val privateKeyPem: String,
    val passphrase: String?
)

/**
 * Phase 11: owns every SSH secret.
 *
 * All passwords / private keys / passphrases go through the existing
 * Keystore-backed [SecureCredentialStore] under a fixed alias scheme:
 *
 *   ssh/<hostId>/password       — login password (authType PASSWORD)
 *   ssh/<hostId>/key            — PEM private key (authType PRIVATE_KEY)
 *   ssh/<hostId>/keyPassphrase  — optional passphrase for that key
 *
 * Nothing here logs secret values; callers receive them only at connect time.
 */
class SshCredentialManager(
    private val store: SecureCredentialStore
) {

    fun passwordAlias(hostId: String): String = "ssh/$hostId/password"

    fun privateKeyAlias(hostId: String): String = "ssh/$hostId/key"

    fun keyPassphraseAlias(hostId: String): String = "ssh/$hostId/keyPassphrase"

    fun savePassword(hostId: String, password: String): Result<Unit> =
        store.storeSecret(passwordAlias(hostId), password)

    fun password(hostId: String): Result<String?> =
        store.getSecret(passwordAlias(hostId))

    fun savePrivateKey(hostId: String, privateKeyPem: String, passphrase: String?): Result<Unit> {
        store.storeSecret(privateKeyAlias(hostId), privateKeyPem).getOrElse { return Result.failure(it) }
        return if (passphrase.isNullOrEmpty()) {
            store.removeSecret(keyPassphraseAlias(hostId))
        } else {
            store.storeSecret(keyPassphraseAlias(hostId), passphrase)
        }
    }

    fun keyMaterial(hostId: String): Result<SshKeyMaterial?> {
        val pem = store.getSecret(privateKeyAlias(hostId)).getOrElse { return Result.failure(it) }
            ?: return Result.success(null)
        val passphrase = store.getSecret(keyPassphraseAlias(hostId)).getOrElse { return Result.failure(it) }
        return Result.success(SshKeyMaterial(pem, passphrase))
    }

    /** True when the secret required by [authType] is present for this host. */
    fun hasSecret(hostId: String, authType: SshAuthType): Boolean = when (authType) {
        SshAuthType.PASSWORD -> store.hasSecret(passwordAlias(hostId))
        SshAuthType.PRIVATE_KEY -> store.hasSecret(privateKeyAlias(hostId))
    }

    /** Removes every secret belonging to a host (profile deletion cascade). */
    fun deleteAllSecrets(hostId: String) {
        store.removeSecret(passwordAlias(hostId))
        store.removeSecret(privateKeyAlias(hostId))
        store.removeSecret(keyPassphraseAlias(hostId))
    }
}
