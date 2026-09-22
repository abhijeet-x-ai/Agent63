package com.devstation.android.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GitHubAccountDao {

    @Query("SELECT * FROM github_accounts WHERE isActive = 1 LIMIT 1")
    fun getActiveAccountFlow(): Flow<GitHubAccountEntity?>

    @Query("SELECT * FROM github_accounts WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveAccount(): GitHubAccountEntity?

    @Query("SELECT * FROM github_accounts WHERE id = :id LIMIT 1")
    suspend fun getAccountById(id: String): GitHubAccountEntity?

    @Query("SELECT * FROM github_accounts ORDER BY createdAt DESC")
    fun getAllAccountsFlow(): Flow<List<GitHubAccountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: GitHubAccountEntity)

    @Update
    suspend fun updateAccount(account: GitHubAccountEntity)

    @Query("UPDATE github_accounts SET isActive = 0")
    suspend fun deactivateAllAccounts()

    @Query("UPDATE github_accounts SET isActive = 1 WHERE id = :id")
    suspend fun setActiveAccount(id: String)

    @Query("DELETE FROM github_accounts WHERE id = :id")
    suspend fun deleteAccountById(id: String)

    @Query("DELETE FROM github_accounts")
    suspend fun deleteAllAccounts()
}
