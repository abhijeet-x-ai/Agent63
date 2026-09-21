package com.devstation.android.core.ai

import com.devstation.android.core.security.SecureCredentialStore

/**
 * Simple in-memory credential store for JVM unit tests (no Android Keystore).
 */
class InMemorySecureCredentialStore : SecureCredentialStore {
    private val secrets = mutableMapOf<String, String>()

    override fun storeSecret(alias: String, secret: String): Result<Unit> {
        secrets[alias] = secret
        return Result.success(Unit)
    }

    override fun getSecret(alias: String): Result<String?> = Result.success(secrets[alias])

    override fun removeSecret(alias: String): Result<Unit> {
        secrets.remove(alias)
        return Result.success(Unit)
    }

    override fun hasSecret(alias: String): Boolean = secrets.containsKey(alias)

    override fun listAliases(): List<String> = secrets.keys.toList()
}

/**
 * Test double mirroring [AiCredentialManager] but backed by [InMemorySecureCredentialStore].
 * Exposes the same convenience `store`/`resolve` API the provider tests were written against.
 */
class TestCredentialManager : AiCredentialManager(InMemorySecureCredentialStore()) {

    /** Store a key and return its credential reference id. */
    fun store(apiKey: String): String = storeCredential(apiKey).getOrThrow()

    /** Resolve the raw key for a credential id (test-only mirror of resolveKey). */
    fun resolve(credentialId: String): String? = resolveKey(credentialId)
}
