package com.devstation.android.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface SecureCredentialStore {
    fun storeSecret(alias: String, secret: String): Result<Unit>
    fun getSecret(alias: String): Result<String?>
    fun removeSecret(alias: String): Result<Unit>
    fun hasSecret(alias: String): Boolean
    fun listAliases(): List<String>
}

class KeystoreCredentialStore(
    private val context: Context
) : SecureCredentialStore {

    private val sharedPreferences: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "devstation_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun storeSecret(alias: String, secret: String): Result<Unit> {
        return runCatching {
            val prefs = sharedPreferences ?: throw IllegalStateException("Secure keystore not available")
            prefs.edit().putString(alias, secret).apply()
        }
    }

    override fun getSecret(alias: String): Result<String?> {
        return runCatching {
            val prefs = sharedPreferences ?: throw IllegalStateException("Secure keystore not available")
            prefs.getString(alias, null)
        }
    }

    override fun removeSecret(alias: String): Result<Unit> {
        return runCatching {
            val prefs = sharedPreferences ?: throw IllegalStateException("Secure keystore not available")
            prefs.edit().remove(alias).apply()
        }
    }

    override fun hasSecret(alias: String): Boolean {
        return sharedPreferences?.contains(alias) == true
    }

    override fun listAliases(): List<String> {
        return sharedPreferences?.all?.keys?.toList() ?: emptyList()
    }
}
