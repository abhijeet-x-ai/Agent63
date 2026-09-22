package com.devstation.android.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 10: Non-secret metadata for connected GitHub accounts.
 *
 * CRITICAL SECURITY INVARIANT:
 * Access tokens (PAT or OAuth tokens) are NEVER stored in Room, SQLite, or SharedPreferences.
 * The token itself is stored exclusively in [com.devstation.android.core.security.SecureCredentialStore]
 * (backed by Android Keystore AES-256-GCM). This entity only references the credential by alias.
 */
@Entity(
    tableName = "github_accounts",
    indices = [
        Index(value = ["credentialAlias"], unique = true),
        Index(value = ["username"])
    ]
)
data class GitHubAccountEntity(
    @PrimaryKey
    val id: String,
    val username: String,
    val displayName: String?,
    val avatarUrl: String?,
    /** Reference to Keystore secret alias: e.g. "github_token_<id>". Never the token itself. */
    val credentialAlias: String,
    val tokenType: String = "PAT",
    val scopesCsv: String = "",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
