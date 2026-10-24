package com.devstation.android.core.remote

import com.devstation.android.core.security.SecureCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshCredentialManagerTest {

    private class FakeSecureStore : SecureCredentialStore {
        val values = mutableMapOf<String, String>()
        override fun storeSecret(alias: String, secret: String): Result<Unit> {
            values[alias] = secret
            return Result.success(Unit)
        }
        override fun getSecret(alias: String): Result<String?> = Result.success(values[alias])
        override fun removeSecret(alias: String): Result<Unit> {
            values.remove(alias)
            return Result.success(Unit)
        }
        override fun hasSecret(alias: String): Boolean = values.containsKey(alias)
        override fun listAliases(): List<String> = values.keys.toList()
    }

    private val store = FakeSecureStore()
    private val manager = SshCredentialManager(store)

    @Test
    fun `alias scheme is stable`() {
        assertEquals("ssh/h1/password", manager.passwordAlias("h1"))
        assertEquals("ssh/h1/key", manager.privateKeyAlias("h1"))
        assertEquals("ssh/h1/keyPassphrase", manager.keyPassphraseAlias("h1"))
    }

    @Test
    fun `password round trips through the store`() {
        manager.savePassword("h1", "s3cret")
        assertEquals("s3cret", manager.password("h1").getOrNull())
        assertTrue(manager.hasSecret("h1", SshAuthType.PASSWORD))
        assertFalse(manager.hasSecret("h1", SshAuthType.PRIVATE_KEY))
    }

    @Test
    fun `private key with passphrase round trips`() {
        manager.savePrivateKey("h1", "-----BEGIN OPENSSH PRIVATE KEY-----\nxyz", "pass")
        val material = manager.keyMaterial("h1").getOrNull()
        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----\nxyz", material?.privateKeyPem)
        assertEquals("pass", material?.passphrase)
        assertTrue(manager.hasSecret("h1", SshAuthType.PRIVATE_KEY))
    }

    @Test
    fun `saving a key without passphrase clears a previous passphrase`() {
        manager.savePrivateKey("h1", "pem", "old-pass")
        manager.savePrivateKey("h1", "pem", null)
        assertNull(manager.keyMaterial("h1").getOrNull()?.passphrase)
    }

    @Test
    fun `delete removes every alias for the host only`() {
        manager.savePassword("h1", "pw")
        manager.savePrivateKey("h1", "pem", "pass")
        manager.savePassword("h2", "other")
        manager.deleteAllSecrets("h1")
        assertEquals(listOf("ssh/h2/password"), store.listAliases())
    }

    @Test
    fun `missing secrets read as null`() {
        assertNull(manager.password("nope").getOrNull())
        assertNull(manager.keyMaterial("nope").getOrNull())
    }
}
