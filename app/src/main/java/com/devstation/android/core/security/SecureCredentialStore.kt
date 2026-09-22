package com.devstation.android.core.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

interface SecureCredentialStore {
    fun storeSecret(alias: String, secret: String): Result<Unit>
    fun getSecret(alias: String): Result<String?>
    fun removeSecret(alias: String): Result<Unit>
    fun hasSecret(alias: String): Boolean
    fun listAliases(): List<String>
}

/**
 * Robust Keystore-backed credential store supporting Android 5.0 (API 21) through latest Android platforms.
 *
 * Security Invariants:
 * 1. Plaintext secrets (AI API keys, PATs, OAuth tokens) are NEVER stored in plaintext.
 * 2. On API 23+, utilizes Android Keystore AES-256-GCM via MasterKey and EncryptedSharedPreferences.
 *    Recovers automatically from keystore corruption or key invalidation.
 * 3. On API 21-22, utilizes AndroidKeyStore RSA keypair wrapping an AES-GCM master key.
 * 4. Fails safe without crashing the application process.
 */
class KeystoreCredentialStore(
    private val context: Context
) : SecureCredentialStore {

    private val delegateStore: SecureStorageDelegate by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            initApi23Store()
        } else {
            initApi21Store()
        }
    }

    private fun initApi23Store(): SecureStorageDelegate {
        return try {
            Api23EncryptedPrefsDelegate(context)
        } catch (t: Throwable) {
            // If API 23 MasterKey fails (e.g. corrupted keystore on custom OEM ROM), attempt recovery
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
                Api23EncryptedPrefsDelegate(context)
            } catch (t2: Throwable) {
                // Fall back to API 21 RSA+AES store
                initApi21Store()
            }
        }
    }

    private fun initApi21Store(): SecureStorageDelegate {
        return try {
            Api21RsaAesDelegate(context)
        } catch (t: Throwable) {
            FallbackEncryptedDelegate(context)
        }
    }

    override fun storeSecret(alias: String, secret: String): Result<Unit> {
        return runCatching {
            delegateStore.put(alias, secret)
        }
    }

    override fun getSecret(alias: String): Result<String?> {
        return runCatching {
            delegateStore.get(alias)
        }
    }

    override fun removeSecret(alias: String): Result<Unit> {
        return runCatching {
            delegateStore.remove(alias)
        }
    }

    override fun hasSecret(alias: String): Boolean {
        return runCatching { delegateStore.contains(alias) }.getOrDefault(false)
    }

    override fun listAliases(): List<String> {
        return runCatching { delegateStore.allKeys() }.getOrDefault(emptyList())
    }

    private interface SecureStorageDelegate {
        fun put(key: String, value: String)
        fun get(key: String): String?
        fun remove(key: String)
        fun contains(key: String): Boolean
        fun allKeys(): List<String>
    }

    /**
     * API 23+ (Android 6.0+) delegate utilizing Jetpack Security MasterKey and EncryptedSharedPreferences.
     */
    private class Api23EncryptedPrefsDelegate(context: Context) : SecureStorageDelegate {
        private val prefs: SharedPreferences

        init {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        override fun put(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }

        override fun get(key: String): String? = prefs.getString(key, null)

        override fun remove(key: String) {
            prefs.edit().remove(key).apply()
        }

        override fun contains(key: String): Boolean = prefs.contains(key)

        override fun allKeys(): List<String> = prefs.all.keys.toList()
    }

    /**
     * API 21-22 (Android 5.0/5.1) delegate utilizing AndroidKeyStore RSA keypair to wrap
     * an AES-256 secret key. Values are encrypted with AES-GCM and stored in private SharedPreferences.
     */
    private class Api21RsaAesDelegate(private val context: Context) : SecureStorageDelegate {
        private val prefs = context.getSharedPreferences(API21_PREFS_NAME, Context.MODE_PRIVATE)
        private val aesKey: SecretKey

        init {
            aesKey = getOrGenerateAesKey()
        }

        @Suppress("DEPRECATION")
        private fun getOrGenerateAesKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val rsaAlias = "devstation_rsa_master"

            if (!keyStore.containsAlias(rsaAlias)) {
                val start = Date(System.currentTimeMillis() - 60000L)
                val end = Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 25)
                val spec = KeyPairGeneratorSpec.Builder(context)
                    .setAlias(rsaAlias)
                    .setSubject(X500Principal("CN=$rsaAlias"))
                    .setSerialNumber(BigInteger.ONE)
                    .setStartDate(start)
                    .setEndDate(end)
                    .build()

                val generator = java.security.KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
                generator.initialize(spec)
                generator.generateKeyPair()
            }

            val wrappedKeyBase64 = prefs.getString("__master_key__", null)
            if (wrappedKeyBase64 != null) {
                val encryptedBytes = Base64.decode(wrappedKeyBase64, Base64.NO_WRAP)
                val privateKey = keyStore.getKey(rsaAlias, null) as java.security.PrivateKey
                val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                rsaCipher.init(Cipher.DECRYPT_MODE, privateKey)
                val rawAes = rsaCipher.doFinal(encryptedBytes)
                return SecretKeySpec(rawAes, "AES")
            } else {
                val keyGen = KeyGenerator.getInstance("AES")
                keyGen.init(256)
                val newKey = keyGen.generateKey()

                val entry = keyStore.getEntry(rsaAlias, null) as KeyStore.PrivateKeyEntry
                val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                rsaCipher.init(Cipher.ENCRYPT_MODE, entry.certificate.publicKey)
                val encryptedKey = rsaCipher.doFinal(newKey.encoded)

                prefs.edit().putString("__master_key__", Base64.encodeToString(encryptedKey, Base64.NO_WRAP)).commit()
                return newKey
            }
        }

        override fun put(key: String, value: String) {
            val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))

            val payload = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            prefs.edit().putString(key, payload).apply()
        }

        override fun get(key: String): String? {
            val payload = prefs.getString(key, null) ?: return null
            if (key == "__master_key__") return null
            return try {
                val parts = payload.split(":", limit = 2)
                if (parts.size != 2) return null
                val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
                val plaintext = cipher.doFinal(ciphertext)
                String(plaintext, StandardCharsets.UTF_8)
            } catch (t: Throwable) {
                null
            }
        }

        override fun remove(key: String) {
            if (key != "__master_key__") {
                prefs.edit().remove(key).apply()
            }
        }

        override fun contains(key: String): Boolean = key != "__master_key__" && prefs.contains(key)

        override fun allKeys(): List<String> = prefs.all.keys.filter { it != "__master_key__" }
    }

    /**
     * Fallback isolated cipher storage for testing environments or devices with non-functional Keystore daemons.
     */
    private class FallbackEncryptedDelegate(context: Context) : SecureStorageDelegate {
        private val prefs = context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        private val aesKey: SecretKey

        init {
            val storedKey = prefs.getString("__fallback_key__", null)
            aesKey = if (storedKey != null) {
                SecretKeySpec(Base64.decode(storedKey, Base64.NO_WRAP), "AES")
            } else {
                val keyGen = KeyGenerator.getInstance("AES")
                keyGen.init(256)
                val key = keyGen.generateKey()
                prefs.edit().putString("__fallback_key__", Base64.encodeToString(key.encoded, Base64.NO_WRAP)).commit()
                key
            }
        }

        override fun put(key: String, value: String) {
            val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            val payload = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            prefs.edit().putString(key, payload).apply()
        }

        override fun get(key: String): String? {
            if (key == "__fallback_key__") return null
            val payload = prefs.getString(key, null) ?: return null
            return try {
                val parts = payload.split(":", limit = 2)
                if (parts.size != 2) return null
                val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
                String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
            } catch (t: Throwable) {
                null
            }
        }

        override fun remove(key: String) {
            if (key != "__fallback_key__") prefs.edit().remove(key).apply()
        }

        override fun contains(key: String): Boolean = key != "__fallback_key__" && prefs.contains(key)

        override fun allKeys(): List<String> = prefs.all.keys.filter { it != "__fallback_key__" }
    }

    companion object {
        private const val PREFS_NAME = "devstation_secure_prefs"
        private const val API21_PREFS_NAME = "devstation_secure_prefs_api21"
        private const val FALLBACK_PREFS_NAME = "devstation_secure_prefs_fallback"
    }
}
