package com.devstation.android.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllProjectsFlow(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE isPinned = 1 ORDER BY updatedAt DESC")
    fun getPinnedProjectsFlow(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC LIMIT :limit")
    fun getRecentProjectsFlow(limit: Int = 5): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectById(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE localPath = :path LIMIT 1")
    suspend fun getProjectByPath(path: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity)

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("UPDATE projects SET isPinned = :isPinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePinStatus(id: String, isPinned: Boolean, updatedAt: Long)

    @Query("UPDATE projects SET name = :newName, localPath = :newPath, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProjectInfo(id: String, newName: String, newPath: String, updatedAt: Long)

    @Query("UPDATE projects SET sizeBytes = :sizeBytes, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProjectSize(id: String, sizeBytes: Long, updatedAt: Long)

    @Delete
    suspend fun deleteProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: String)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllConversationsFlow(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE isPinned = 1 ORDER BY updatedAt DESC")
    fun getPinnedConversationsFlow(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT :limit")
    fun getRecentConversationsFlow(limit: Int = 5): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun getConversationsByProjectFlow(projectId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET isPinned = :isPinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePinStatus(id: String, isPinned: Boolean, updatedAt: Long)

    @Query("UPDATE conversations SET title = :newTitle, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, newTitle: String, updatedAt: Long)

    @Query("UPDATE conversations SET providerId = :providerId, modelId = :modelId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversationModel(id: String, providerId: String?, modelId: String?, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun getMessagesForConversationFlow(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getMessagesForConversation(conversationId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET errorState = :errorState WHERE id = :messageId")
    suspend fun updateMessageErrorState(messageId: String, errorState: String?)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)
}

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettings(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(settings: AppSettingsEntity)
}

@Dao
interface RecentFileDao {
    @Query("SELECT * FROM recent_files ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun getRecentFilesFlow(limit: Int = 50): Flow<List<RecentFileEntity>>

    @Query("SELECT * FROM recent_files WHERE projectId = :projectId ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun getRecentFilesForProjectFlow(projectId: String, limit: Int = 50): Flow<List<RecentFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(recentFile: RecentFileEntity)

    @Query("DELETE FROM recent_files WHERE filePath = :filePath")
    suspend fun deleteRecentFile(filePath: String)

    @Query("DELETE FROM recent_files WHERE projectId = :projectId")
    suspend fun deleteRecentFilesForProject(projectId: String)
}

@Dao
interface EditorSettingsDao {
    @Query("SELECT * FROM editor_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<EditorSettingsEntity?>

    @Query("SELECT * FROM editor_settings WHERE id = 1")
    suspend fun getSettings(): EditorSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(settings: EditorSettingsEntity)
}

