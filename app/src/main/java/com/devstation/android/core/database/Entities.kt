package com.devstation.android.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.devstation.android.core.model.AppTheme
import com.devstation.android.core.model.Conversation
import com.devstation.android.core.model.Message
import com.devstation.android.core.model.MessageRole
import com.devstation.android.core.model.Project
import com.devstation.android.core.model.AppSettings

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val localPath: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean = false,
    val sizeBytes: Long = 0L
) {
    fun toDomain() = Project(
        id = id,
        name = name,
        localPath = localPath,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isPinned = isPinned,
        sizeBytes = sizeBytes
    )

    companion object {
        fun fromDomain(project: Project) = ProjectEntity(
            id = project.id,
            name = project.name,
            localPath = project.localPath,
            createdAt = project.createdAt,
            updatedAt = project.updatedAt,
            isPinned = project.isPinned,
            sizeBytes = project.sizeBytes
        )
    }
}

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val projectId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean = false
) {
    fun toDomain() = Conversation(
        id = id,
        title = title,
        projectId = projectId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isPinned = isPinned
    )

    companion object {
        fun fromDomain(conversation: Conversation) = ConversationEntity(
            id = conversation.id,
            title = conversation.title,
            projectId = conversation.projectId,
            createdAt = conversation.createdAt,
            updatedAt = conversation.updatedAt,
            isPinned = conversation.isPinned
        )
    }
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Long
) {
    fun toDomain() = Message(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(message: Message) = MessageEntity(
            id = message.id,
            conversationId = message.conversationId,
            role = message.role,
            content = message.content,
            createdAt = message.createdAt
        )
    }
}

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val theme: AppTheme = AppTheme.DARK,
    val defaultProjectId: String? = null,
    val storageRoot: String = ""
) {
    fun toDomain() = AppSettings(
        theme = theme,
        defaultProjectId = defaultProjectId,
        storageRoot = storageRoot
    )

    companion object {
        fun fromDomain(settings: AppSettings) = AppSettingsEntity(
            id = 1,
            theme = settings.theme,
            defaultProjectId = settings.defaultProjectId,
            storageRoot = settings.storageRoot
        )
    }
}

@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey val filePath: String,
    val projectId: String,
    val fileName: String,
    val lastOpenedAt: Long,
    val lastEditedAt: Long
)

@Entity(tableName = "editor_settings")
data class EditorSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val fontSizeSp: Float = 14f,
    val tabSize: Int = 4,
    val insertSpaces: Boolean = true,
    val wordWrap: Boolean = false,
    val showLineNumbers: Boolean = true,
    val autoCloseBrackets: Boolean = true,
    val autoIndent: Boolean = true,
    val enableSyntaxHighlighting: Boolean = true
)

