package com.devstation.android.core.model

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

enum class AppTheme {
    SYSTEM,
    DARK,
    LIGHT
}

data class Project(
    val id: String,
    val name: String,
    val localPath: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean = false,
    val sizeBytes: Long = 0L
)

data class Conversation(
    val id: String,
    val title: String,
    val projectId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean = false,
    /** Phase 5: per-conversation provider/model selection (null = use AI settings default). */
    val providerId: String? = null,
    val modelId: String? = null
)

data class Message(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
    /** Phase 5: optional error marker for failed AI assistant messages. */
    val errorState: String? = null
)

data class AppSettings(
    val theme: AppTheme = AppTheme.DARK,
    val defaultProjectId: String? = null,
    val storageRoot: String = ""
)

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val extension: String
)

data class StorageStats(
    val totalBytes: Long,
    val freeBytes: Long,
    val usedBytes: Long,
    val projectsBytes: Long,
    val cacheBytes: Long,
    val appDataBytes: Long,
    val linuxRootfsBytes: Long = 0L,
    val linuxHomeBytes: Long = 0L,
    val linuxTotalBytes: Long = 0L
)
