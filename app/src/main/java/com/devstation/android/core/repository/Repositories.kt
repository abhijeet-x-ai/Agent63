package com.devstation.android.core.repository

import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.database.AppSettingsDao
import com.devstation.android.core.database.AppSettingsEntity
import com.devstation.android.core.database.ConversationDao
import com.devstation.android.core.database.ConversationEntity
import com.devstation.android.core.database.MessageDao
import com.devstation.android.core.database.MessageEntity
import com.devstation.android.core.database.ProjectDao
import com.devstation.android.core.database.ProjectEntity
import com.devstation.android.core.filesystem.ProjectFileSystemManager
import com.devstation.android.core.model.AppSettings
import com.devstation.android.core.model.AppTheme
import com.devstation.android.core.model.Conversation
import com.devstation.android.core.model.Message
import com.devstation.android.core.model.MessageRole
import com.devstation.android.core.model.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ProjectRepository(
    private val projectDao: ProjectDao,
    private val fileSystemManager: ProjectFileSystemManager,
    private val dispatchers: DispatcherProvider
) {
    fun getAllProjects(): Flow<List<Project>> =
        projectDao.getAllProjectsFlow().map { list -> list.map { it.toDomain() } }

    fun getPinnedProjects(): Flow<List<Project>> =
        projectDao.getPinnedProjectsFlow().map { list -> list.map { it.toDomain() } }

    fun getRecentProjects(limit: Int = 5): Flow<List<Project>> =
        projectDao.getRecentProjectsFlow(limit).map { list -> list.map { it.toDomain() } }

    suspend fun getProjectById(id: String): Project? = withContext(dispatchers.io) {
        projectDao.getProjectById(id)?.toDomain()
    }

    suspend fun createProject(name: String, parentDir: File? = null): Result<Project> = withContext(dispatchers.io) {
        fileSystemManager.createProject(name, parentDir).mapCatching { directory ->
            val now = System.currentTimeMillis()
            val project = Project(
                id = UUID.randomUUID().toString(),
                name = directory.name,
                localPath = directory.absolutePath,
                createdAt = now,
                updatedAt = now,
                isPinned = false,
                sizeBytes = fileSystemManager.calculateDirectorySize(directory)
            )
            projectDao.insertProject(ProjectEntity.fromDomain(project))
            project
        }
    }

    suspend fun importExistingFolder(name: String, folderPath: String): Result<Project> = withContext(dispatchers.io) {
        runCatching {
            val folder = File(folderPath)
            if (!folder.exists() || !folder.isDirectory) {
                throw IllegalArgumentException("Target path is not a valid directory: $folderPath")
            }
            val existing = projectDao.getProjectByPath(folder.absolutePath)
            if (existing != null) {
                return@runCatching existing.toDomain()
            }
            val now = System.currentTimeMillis()
            val project = Project(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { folder.name },
                localPath = folder.absolutePath,
                createdAt = now,
                updatedAt = now,
                isPinned = false,
                sizeBytes = fileSystemManager.calculateDirectorySize(folder)
            )
            projectDao.insertProject(ProjectEntity.fromDomain(project))
            project
        }
    }

    suspend fun renameProject(id: String, newName: String): Result<Project> = withContext(dispatchers.io) {
        val existing = projectDao.getProjectById(id) ?: return@withContext Result.failure(
            IllegalArgumentException("Project not found")
        )
        fileSystemManager.renameProject(existing.localPath, newName).mapCatching { renamedDir ->
            val now = System.currentTimeMillis()
            projectDao.updateProjectInfo(id, renamedDir.name, renamedDir.absolutePath, now)
            existing.copy(
                name = renamedDir.name,
                localPath = renamedDir.absolutePath,
                updatedAt = now
            ).toDomain()
        }
    }

    suspend fun deleteProject(id: String, deleteFilesFromDisk: Boolean = true): Result<Unit> = withContext(dispatchers.io) {
        val existing = projectDao.getProjectById(id) ?: return@withContext Result.failure(
            IllegalArgumentException("Project not found")
        )
        if (deleteFilesFromDisk) {
            fileSystemManager.deleteProject(existing.localPath)
        }
        projectDao.deleteProjectById(id)
        Result.success(Unit)
    }

    suspend fun togglePin(id: String, isPinned: Boolean) = withContext(dispatchers.io) {
        projectDao.updatePinStatus(id, isPinned, System.currentTimeMillis())
    }

    suspend fun refreshProjectSizes() = withContext(dispatchers.io) {
        val projects = projectDao.getAllProjectsFlow().firstOrNull() ?: emptyList()
        val now = System.currentTimeMillis()
        for (entity in projects) {
            val file = File(entity.localPath)
            if (file.exists()) {
                val size = fileSystemManager.calculateDirectorySize(file)
                projectDao.updateProjectSize(entity.id, size, now)
            }
        }
    }
}

class ConversationRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val dispatchers: DispatcherProvider
) {
    fun getAllConversations(): Flow<List<Conversation>> =
        conversationDao.getAllConversationsFlow().map { list -> list.map { it.toDomain() } }

    fun getPinnedConversations(): Flow<List<Conversation>> =
        conversationDao.getPinnedConversationsFlow().map { list -> list.map { it.toDomain() } }

    fun getRecentConversations(limit: Int = 5): Flow<List<Conversation>> =
        conversationDao.getRecentConversationsFlow(limit).map { list -> list.map { it.toDomain() } }

    fun getConversationsByProject(projectId: String): Flow<List<Conversation>> =
        conversationDao.getConversationsByProjectFlow(projectId).map { list -> list.map { it.toDomain() } }

    suspend fun getConversationById(id: String): Conversation? = withContext(dispatchers.io) {
        conversationDao.getConversationById(id)?.toDomain()
    }

    suspend fun createConversation(title: String, projectId: String? = null): Result<Conversation> = withContext(dispatchers.io) {
        runCatching {
            val now = System.currentTimeMillis()
            val conversation = Conversation(
                id = UUID.randomUUID().toString(),
                title = title.ifBlank { "New Workspace Session" },
                projectId = projectId,
                createdAt = now,
                updatedAt = now,
                isPinned = false
            )
            conversationDao.insertConversation(ConversationEntity.fromDomain(conversation))
            conversation
        }
    }

    suspend fun updateTitle(id: String, newTitle: String) = withContext(dispatchers.io) {
        conversationDao.updateTitle(id, newTitle, System.currentTimeMillis())
    }

    suspend fun togglePin(id: String, isPinned: Boolean) = withContext(dispatchers.io) {
        conversationDao.updatePinStatus(id, isPinned, System.currentTimeMillis())
    }

    suspend fun deleteConversation(id: String) = withContext(dispatchers.io) {
        messageDao.deleteMessagesForConversation(id)
        conversationDao.deleteConversationById(id)
    }

    fun getMessages(conversationId: String): Flow<List<Message>> =
        messageDao.getMessagesForConversationFlow(conversationId).map { list -> list.map { it.toDomain() } }

    suspend fun sendMessage(
        conversationId: String,
        content: String,
        role: MessageRole = MessageRole.USER
    ): Result<Message> = withContext(dispatchers.io) {
        runCatching {
            require(content.isNotBlank()) { "Message cannot be empty" }
            val now = System.currentTimeMillis()
            val message = Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = role,
                content = content.trim(),
                createdAt = now
            )
            messageDao.insertMessage(MessageEntity.fromDomain(message))

            // Update conversation updated timestamp
            val conv = conversationDao.getConversationById(conversationId)
            if (conv != null) {
                conversationDao.updateConversation(conv.copy(updatedAt = now))
            }
            message
        }
    }
}

class SettingsRepository(
    private val appSettingsDao: AppSettingsDao,
    private val dispatchers: DispatcherProvider
) {
    fun getSettings(): Flow<AppSettings> =
        appSettingsDao.getSettingsFlow().map { it?.toDomain() ?: AppSettings() }

    suspend fun updateTheme(theme: AppTheme) = withContext(dispatchers.io) {
        val current = appSettingsDao.getSettings()?.toDomain() ?: AppSettings()
        appSettingsDao.insertOrUpdate(AppSettingsEntity.fromDomain(current.copy(theme = theme)))
    }

    suspend fun updateDefaultProject(projectId: String?) = withContext(dispatchers.io) {
        val current = appSettingsDao.getSettings()?.toDomain() ?: AppSettings()
        appSettingsDao.insertOrUpdate(AppSettingsEntity.fromDomain(current.copy(defaultProjectId = projectId)))
    }
}
