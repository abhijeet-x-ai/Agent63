package com.devstation.android.core.remote

import com.devstation.android.core.database.SshHostDao
import com.devstation.android.core.database.SshHostEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Phase 11: profile persistence for SSH hosts.
 *
 * Profiles live in Room ([SshHostDao]); secrets never pass through here —
 * they are owned by [SshCredentialManager]. Deleting a profile cascades to
 * its secrets so no orphaned credentials remain in the Keystore store.
 */
class SshHostRepository(
    private val dao: SshHostDao,
    private val credentials: SshCredentialManager
) {

    fun observeProfiles(): Flow<List<SshHostProfile>> =
        dao.getAllHostsFlow().map { entities -> entities.map { it.toProfile() } }

    suspend fun profiles(): List<SshHostProfile> = dao.getAllHosts().map { it.toProfile() }

    suspend fun profile(id: String): SshHostProfile? = dao.getHostById(id)?.toProfile()

    suspend fun save(profile: SshHostProfile) {
        dao.insertHost(profile.toEntity())
    }

    /** Deletes the profile and every secret stored under its id. */
    suspend fun delete(id: String) {
        dao.deleteHostById(id)
        credentials.deleteAllSecrets(id)
    }

    /** Records the trusted host key after an explicit user trust decision. */
    suspend fun trustHostKey(id: String, fingerprint: String, keyType: String?) {
        dao.updateHostKey(id, fingerprint, keyType, System.currentTimeMillis())
    }

    /** Clears the trusted host key (forces a fresh trust decision next connect). */
    suspend fun clearHostKey(id: String) {
        dao.updateHostKey(id, null, null, System.currentTimeMillis())
    }

    suspend fun markConnected(id: String) {
        dao.touchLastConnected(id, System.currentTimeMillis())
    }
}

fun SshHostEntity.toProfile(): SshHostProfile = SshHostProfile(
    id = id,
    name = name,
    host = host,
    port = port,
    username = username,
    authType = runCatching { SshAuthType.valueOf(authType) }.getOrDefault(SshAuthType.PASSWORD),
    hostKeyFingerprint = hostKeyFingerprint,
    hostKeyType = hostKeyType,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastConnectedAt = lastConnectedAt
)

fun SshHostProfile.toEntity(): SshHostEntity = SshHostEntity(
    id = id,
    name = name,
    host = host,
    port = port,
    username = username,
    authType = authType.name,
    hostKeyFingerprint = hostKeyFingerprint,
    hostKeyType = hostKeyType,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastConnectedAt = lastConnectedAt
)
