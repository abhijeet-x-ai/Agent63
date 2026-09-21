package com.devstation.android.core.ai

import com.devstation.android.core.security.SecureCredentialStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges AI provider API keys to the Phase 1 Android Keystore-backed
 * [SecureCredentialStore]. Room/config stores only the returned credential id.
 *
 * Security invariants:
 * - The raw key is never returned to UI layers; only [hasCredential] / metadata.
 * - Keys are stored under unguessable aliases (`ai_credential_<uuid>`).
 * - [deleteCredential] removes the keystore entry BEFORE config metadata deletion
 *   so no orphan secrets are left behind.
 */
// `open` so pure-JVM unit tests can subclass with an in-memory secure store.
open class AiCredentialManager(private val secureStore: SecureCredentialStore) {

    private val cache = ConcurrentHashMap<String, String>()

    /**
     * Store a new API key and return its credential reference id.
     */
    fun storeCredential(apiKey: String): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalArgumentException("API key must not be empty"))
        }
        val credentialId = "ai_credential_${UUID.randomUUID()}"
        return secureStore.storeSecret(credentialId, apiKey).map { credentialId }
    }

    /**
     * Replace the credential behind [existingCredentialId]. Returns the new credential id.
     * The old secret is deleted first so no orphan remains.
     */
    fun replaceCredential(existingCredentialId: String, newApiKey: String): Result<String> {
        val created = storeCredential(newApiKey)
        return created.mapCatching { newId ->
            secureStore.removeSecret(existingCredentialId).getOrThrow()
            cache.remove(existingCredentialId)
            newId
        }
    }

    /**
     * INTERNAL + ADAPTER-ONLY: resolve the raw API key at request time.
     * Must never be called from UI/ViewModel state.
     */
    fun resolveKey(credentialId: String): String? {
        cache[credentialId]?.let { return it }
        val secret = secureStore.getSecret(credentialId).getOrNull() ?: return null
        cache[credentialId] = secret
        return secret
    }

    fun hasCredential(credentialId: String): Boolean {
        if (cache.containsKey(credentialId)) return true
        return secureStore.hasSecret(credentialId)
    }

    /**
     * Delete the keystore secret. Returns true when the secret existed and is now gone.
     */
    fun deleteCredential(credentialId: String): Boolean {
        val existed = secureStore.hasSecret(credentialId)
        secureStore.removeSecret(credentialId).getOrNull()
        cache.remove(credentialId)
        return existed
    }

    /**
     * Phase 1 compatibility helper: purge any keystore entries with the given prefix.
     * Used when a provider configuration is removed to guarantee no orphan credentials.
     */
    fun purgeCredentialsWithPrefix(prefix: String): Int {
        var removed = 0
        secureStore.listAliases()
            .filter { it.startsWith(prefix) }
            .forEach { alias ->
                secureStore.removeSecret(alias).onSuccess { removed++ }
                cache.remove(alias)
            }
        return removed
    }

    companion object {
        const val ALIAS_PREFIX = "ai_credential_"
    }
}
