package com.devstation.android.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Phase 11: DAO for saved SSH host profiles. */
@Dao
interface SshHostDao {

    @Query("SELECT * FROM ssh_hosts ORDER BY name ASC")
    fun getAllHostsFlow(): Flow<List<SshHostEntity>>

    @Query("SELECT * FROM ssh_hosts ORDER BY name ASC")
    suspend fun getAllHosts(): List<SshHostEntity>

    @Query("SELECT * FROM ssh_hosts WHERE id = :id LIMIT 1")
    suspend fun getHostById(id: String): SshHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHost(host: SshHostEntity)

    @Query("DELETE FROM ssh_hosts WHERE id = :id")
    suspend fun deleteHostById(id: String)

    @Query(
        "UPDATE ssh_hosts SET hostKeyFingerprint = :fingerprint, hostKeyType = :keyType, " +
            "updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateHostKey(id: String, fingerprint: String?, keyType: String?, updatedAt: Long)

    @Query("UPDATE ssh_hosts SET lastConnectedAt = :connectedAt, updatedAt = :connectedAt WHERE id = :id")
    suspend fun touchLastConnected(id: String, connectedAt: Long)
}
