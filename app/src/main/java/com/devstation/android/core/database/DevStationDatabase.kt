package com.devstation.android.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProjectEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        AppSettingsEntity::class,
        RecentFileEntity::class,
        EditorSettingsEntity::class,
        AIProviderConfigEntity::class,
        AIModelCacheEntity::class,
        AIUsageRecordEntity::class,
        AISettingsEntity::class,
        AgentTaskEntity::class,
        AgentEventEntity::class,
        AgentActionHistoryEntity::class,
        AgentTaskPermissionEntity::class,
        SecuritySettingsEntity::class,
        ProjectSecuritySettingsEntity::class,
        SecurityEventEntity::class,
        PermissionGrantEntity::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class DevStationDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun recentFileDao(): RecentFileDao
    abstract fun editorSettingsDao(): EditorSettingsDao
    abstract fun aiProviderConfigDao(): AIProviderConfigDao
    abstract fun aiModelCacheDao(): AIModelCacheDao
    abstract fun aiUsageRecordDao(): AIUsageRecordDao
    abstract fun aiSettingsDao(): AISettingsDao

    // Phase 6: agent + tool execution system
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun agentEventDao(): AgentEventDao
    abstract fun agentActionHistoryDao(): AgentActionHistoryDao
    abstract fun agentTaskPermissionDao(): AgentTaskPermissionDao

    // Phase 7: permissions, sandbox + security hardening
    abstract fun securitySettingsDao(): SecuritySettingsDao
    abstract fun projectSecuritySettingsDao(): ProjectSecuritySettingsDao
    abstract fun securityEventDao(): SecurityEventDao
    abstract fun permissionGrantDao(): PermissionGrantDao

    companion object {
        private const val DATABASE_NAME = "devstation_db"

        /** v2 -> v3: add Phase 5 AI provider tables and per-conversation model columns. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `ai_provider_configs` (
                        `providerId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `credentialId` TEXT,
                        `baseUrlOverride` TEXT,
                        `defaultModelId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `lastConnectionCheckAt` INTEGER,
                        `lastConnectionStatus` TEXT,
                        PRIMARY KEY(`providerId`)
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_provider_configs_credentialId` ON `ai_provider_configs` (`credentialId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `ai_model_cache` (
                        `providerId` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `contextWindow` INTEGER,
                        `capabilitiesCsv` TEXT NOT NULL DEFAULT '',
                        `inputPricing` REAL,
                        `outputPricing` REAL,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `lastUpdated` INTEGER NOT NULL,
                        PRIMARY KEY(`providerId`, `modelId`)
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `ai_usage_records` (
                        `id` TEXT NOT NULL,
                        `conversationId` TEXT,
                        `messageId` TEXT,
                        `providerId` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `inputTokens` INTEGER,
                        `outputTokens` INTEGER,
                        `totalTokens` INTEGER,
                        `cachedTokens` INTEGER,
                        `reasoningTokens` INTEGER,
                        `estimatedCostUsd` REAL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_usage_records_conversationId` ON `ai_usage_records` (`conversationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_usage_records_createdAt` ON `ai_usage_records` (`createdAt`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `ai_settings` (
                        `id` INTEGER NOT NULL,
                        `defaultProviderId` TEXT,
                        `defaultModelId` TEXT,
                        `streamingEnabled` INTEGER NOT NULL DEFAULT 1,
                        `showUsage` INTEGER NOT NULL DEFAULT 1,
                        `showEstimatedCost` INTEGER NOT NULL DEFAULT 1,
                        `saveFailedRequests` INTEGER NOT NULL DEFAULT 1,
                        `connectTimeoutSeconds` INTEGER NOT NULL DEFAULT 15,
                        `readTimeoutSeconds` INTEGER NOT NULL DEFAULT 120,
                        `retryCount` INTEGER NOT NULL DEFAULT 2,
                        `maxPayloadChars` INTEGER NOT NULL DEFAULT 128000,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `providerId` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `modelId` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `errorState` TEXT DEFAULT NULL")
            }
        }

        /** v3 -> v4: add Phase 6 agent/tool tables and agent settings columns. Purely additive. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `agent_tasks` (
                        `taskId` TEXT NOT NULL,
                        `projectId` TEXT NOT NULL,
                        `conversationId` TEXT,
                        `goal` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `providerId` TEXT,
                        `modelId` TEXT,
                        `iterationCount` INTEGER NOT NULL DEFAULT 0,
                        `toolCallCount` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `errorMessage` TEXT,
                        PRIMARY KEY(`taskId`)
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_tasks_projectId` ON `agent_tasks` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_tasks_conversationId` ON `agent_tasks` (`conversationId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `agent_events` (
                        `id` TEXT NOT NULL,
                        `taskId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `detail` TEXT,
                        `status` TEXT NOT NULL DEFAULT 'RUNNING',
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_events_taskId` ON `agent_events` (`taskId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `agent_action_history` (
                        `id` TEXT NOT NULL,
                        `taskId` TEXT NOT NULL,
                        `projectId` TEXT NOT NULL,
                        `toolName` TEXT NOT NULL,
                        `actionSummary` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_action_history_taskId` ON `agent_action_history` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_action_history_projectId` ON `agent_action_history` (`projectId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `agent_task_permissions` (
                        `taskId` TEXT NOT NULL,
                        `toolName` TEXT NOT NULL,
                        `scope` TEXT NOT NULL,
                        `grantedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`taskId`, `toolName`)
                    )"""
                )
                db.execSQL("ALTER TABLE `ai_settings` ADD COLUMN `agentToolsEnabled` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `ai_settings` ADD COLUMN `agentMaxIterations` INTEGER NOT NULL DEFAULT 25")
                db.execSQL("ALTER TABLE `ai_settings` ADD COLUMN `agentMaxToolCalls` INTEGER NOT NULL DEFAULT 50")
                db.execSQL("ALTER TABLE `ai_settings` ADD COLUMN `agentMaxTaskSeconds` INTEGER NOT NULL DEFAULT 600")
                db.execSQL("ALTER TABLE `ai_settings` ADD COLUMN `agentMaxToolOutputChars` INTEGER NOT NULL DEFAULT 24000")
                db.execSQL("ALTER TABLE `ai_settings` ADD COLUMN `agentAllowAndroidShell` INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v4 -> v5: add Phase 7 security tables (policy, per-project settings, audit log, scoped
         * grants). Purely additive; Phase 1–6 data and Phase 6 task grants are untouched.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `security_settings` (
                        `id` INTEGER NOT NULL,
                        `policyJson` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `project_security_settings` (
                        `projectId` TEXT NOT NULL,
                        `allowFileModification` INTEGER NOT NULL DEFAULT 1,
                        `allowTerminal` INTEGER NOT NULL DEFAULT 1,
                        `allowNetwork` INTEGER NOT NULL DEFAULT 1,
                        `allowPackageInstallation` INTEGER NOT NULL DEFAULT 1,
                        `allowSensitiveFileAccess` INTEGER NOT NULL DEFAULT 1,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`projectId`)
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_security_settings_projectId` ON `project_security_settings` (`projectId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `security_events` (
                        `id` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `decision` TEXT NOT NULL,
                        `risk` TEXT NOT NULL,
                        `projectId` TEXT,
                        `taskId` TEXT,
                        `sessionId` TEXT,
                        `agentId` TEXT,
                        `toolName` TEXT,
                        `action` TEXT NOT NULL,
                        `resourceType` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_security_events_timestamp` ON `security_events` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_security_events_projectId` ON `security_events` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_security_events_taskId` ON `security_events` (`taskId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `permission_grants` (
                        `id` TEXT NOT NULL,
                        `scope` TEXT NOT NULL,
                        `projectId` TEXT,
                        `taskId` TEXT,
                        `sessionId` TEXT,
                        `toolName` TEXT NOT NULL,
                        `grantedAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_permission_grants_scope` ON `permission_grants` (`scope`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_permission_grants_taskId` ON `permission_grants` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_permission_grants_sessionId` ON `permission_grants` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_permission_grants_projectId` ON `permission_grants` (`projectId`)")
            }
        }

        @Volatile
        private var instance: DevStationDatabase? = null

        fun getInstance(context: Context): DevStationDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DevStationDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { instance = it }
            }
        }
    }
}
