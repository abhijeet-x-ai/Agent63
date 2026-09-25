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
        PermissionGrantEntity::class,
        // Phase 8: MCP, Skills, Agent Profiles
        McpServerEntity::class,
        McpCapabilityEntity::class,
        SkillEntity::class,
        AgentProfileEntity::class,
        // Phase 10: Git & GitHub Integration
        GitHubAccountEntity::class
    ],
    version = 8,
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

    // Phase 8: MCP, Skills, Agent Profiles
    abstract fun mcpServerDao(): McpServerDao
    abstract fun mcpCapabilityDao(): McpCapabilityDao
    abstract fun skillDao(): SkillDao
    abstract fun agentProfileDao(): AgentProfileDao

    // Phase 10: Git & GitHub Integration
    abstract fun gitHubAccountDao(): GitHubAccountDao

    companion object {
        private const val DATABASE_NAME = "devstation_db"

        /** v1 -> v2: add Phase 4 recent files and editor settings tables. */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `recent_files` (
                        `filePath` TEXT NOT NULL,
                        `projectId` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `lastOpenedAt` INTEGER NOT NULL,
                        `lastEditedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`filePath`)
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `editor_settings` (
                        `id` INTEGER NOT NULL,
                        `fontSizeSp` REAL NOT NULL,
                        `tabSize` INTEGER NOT NULL,
                        `insertSpaces` INTEGER NOT NULL,
                        `wordWrap` INTEGER NOT NULL,
                        `showLineNumbers` INTEGER NOT NULL,
                        `autoCloseBrackets` INTEGER NOT NULL,
                        `autoIndent` INTEGER NOT NULL,
                        `enableSyntaxHighlighting` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
            }
        }

        /** v2 -> v3: add Phase 5 AI provider tables and per-conversation model columns. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `ai_provider_configs` (
                        `providerId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
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
                        `capabilitiesCsv` TEXT NOT NULL,
                        `inputPricing` REAL,
                        `outputPricing` REAL,
                        `enabled` INTEGER NOT NULL,
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
                        `streamingEnabled` INTEGER NOT NULL,
                        `showUsage` INTEGER NOT NULL,
                        `showEstimatedCost` INTEGER NOT NULL,
                        `saveFailedRequests` INTEGER NOT NULL,
                        `connectTimeoutSeconds` INTEGER NOT NULL,
                        `readTimeoutSeconds` INTEGER NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `maxPayloadChars` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `providerId` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `modelId` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `errorState` TEXT DEFAULT NULL")
            }
        }

        /** v3 -> v4: add Phase 6 agent/tool tables and agent settings columns. Purely additive. */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
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
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
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
                        `allowFileModification` INTEGER NOT NULL,
                        `allowTerminal` INTEGER NOT NULL,
                        `allowNetwork` INTEGER NOT NULL,
                        `allowPackageInstallation` INTEGER NOT NULL,
                        `allowSensitiveFileAccess` INTEGER NOT NULL,
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

        /**
         * v5 -> v6: Phase 8 — MCP servers, MCP capabilities, Skills, Agent Profiles.
         * Purely additive; all Phase 1–7 data untouched.
         */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `mcp_servers` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `transportType` TEXT NOT NULL,
                        `command` TEXT NOT NULL,
                        `argumentsJson` TEXT NOT NULL,
                        `environmentJson` TEXT NOT NULL,
                        `credentialReferenceId` TEXT,
                        `endpoint` TEXT,
                        `enabled` INTEGER NOT NULL,
                        `autoConnect` INTEGER NOT NULL,
                        `securityMode` TEXT NOT NULL,
                        `projectScope` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mcp_servers_enabled` ON `mcp_servers` (`enabled`)")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `mcp_capabilities` (
                        `id` TEXT NOT NULL,
                        `serverId` TEXT NOT NULL,
                        `capabilityType` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `inputSchemaJson` TEXT NOT NULL,
                        `outputMetadataJson` TEXT NOT NULL,
                        `discoveredAt` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `securityClassification` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mcp_capabilities_serverId` ON `mcp_capabilities` (`serverId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mcp_capabilities_capabilityType` ON `mcp_capabilities` (`capabilityType`)")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `skills` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `version` TEXT NOT NULL,
                        `author` TEXT NOT NULL,
                        `instructions` TEXT NOT NULL,
                        `requiredToolsJson` TEXT NOT NULL,
                        `requestedCapabilitiesJson` TEXT NOT NULL,
                        `securityProfileJson` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `lastRunAt` INTEGER,
                        `runCount` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_skills_source` ON `skills` (`source`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_skills_enabled` ON `skills` (`enabled`)")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `agent_profiles` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `systemInstructions` TEXT NOT NULL,
                        `providerId` TEXT,
                        `modelId` TEXT,
                        `enabledToolsJson` TEXT NOT NULL,
                        `enabledSkillsJson` TEXT NOT NULL,
                        `enabledMcpServersJson` TEXT NOT NULL,
                        `permissionProfileJson` TEXT NOT NULL,
                        `securityScope` TEXT NOT NULL,
                        `projectScope` TEXT,
                        `maxIterations` INTEGER NOT NULL,
                        `maxToolCalls` INTEGER NOT NULL,
                        `maxTaskDurationMs` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_profiles_enabled` ON `agent_profiles` (`enabled`)")
            }
        }

        /** v6 -> v7: add Phase 10 GitHub account table. */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `github_accounts` (
                        `id` TEXT NOT NULL,
                        `username` TEXT NOT NULL,
                        `displayName` TEXT,
                        `avatarUrl` TEXT,
                        `credentialAlias` TEXT NOT NULL,
                        `tokenType` TEXT NOT NULL,
                        `scopesCsv` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_github_accounts_credentialAlias` ON `github_accounts` (`credentialAlias`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_github_accounts_username` ON `github_accounts` (`username`)")
            }
        }

        /** v7 -> v8: self-healing migration to drop and recreate github_accounts matching exact Room schema. */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `github_accounts`")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `github_accounts` (
                        `id` TEXT NOT NULL,
                        `username` TEXT NOT NULL,
                        `displayName` TEXT,
                        `avatarUrl` TEXT,
                        `credentialAlias` TEXT NOT NULL,
                        `tokenType` TEXT NOT NULL,
                        `scopesCsv` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_github_accounts_credentialAlias` ON `github_accounts` (`credentialAlias`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_github_accounts_username` ON `github_accounts` (`username`)")
            }
        }

        @Volatile
        private var instance: DevStationDatabase? = null

        private fun buildDatabase(context: Context): DevStationDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                DevStationDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8
                )
                .fallbackToDestructiveMigration()
                .fallbackToDestructiveMigrationOnDowngrade()
                .allowMainThreadQueries()
                .build()
        }

        fun clearInstance() {
            synchronized(this) {
                runCatching { instance?.close() }
                instance = null
            }
        }

        fun getInstance(context: Context): DevStationDatabase {
            return instance ?: synchronized(this) {
                instance ?: try {
                    val db = buildDatabase(context)
                    // v1.1.3: never block the main thread on open/migrations.
                    // On main, return the built handle and let the IO prewarm
                    // validate it. On background, validate eagerly with self-heal.
                    if (android.os.Looper.getMainLooper().isCurrentThread) {
                        db.also { instance = it }
                    } else {
                        // Touch database eagerly inside self-healing block to validate schema
                        db.openHelper.writableDatabase
                        db.also { instance = it }
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("DevStationDatabase", "Database corruption detected, self-healing...", t)
                    val onMain = android.os.Looper.getMainLooper().isCurrentThread
                    try {
                        if (!onMain) {
                            context.deleteDatabase(DATABASE_NAME)
                        }
                    } catch (_: Throwable) {}
                    try {
                        val db = buildDatabase(context)
                        // v1.1.3: don't block main on recovery either; IO prewarm validates.
                        if (!onMain) {
                            db.openHelper.writableDatabase
                        }
                        db.also { instance = it }
                    } catch (t2: Throwable) {
                        android.util.Log.e("DevStationDatabase", "Database rebuild failed, using in-memory store", t2)
                        val mem = Room.inMemoryDatabaseBuilder(
                            context.applicationContext,
                            DevStationDatabase::class.java
                        )
                            .allowMainThreadQueries()
                            .build()
                        if (!onMain) {
                            runCatching { mem.openHelper.writableDatabase }
                        }
                        mem.also { instance = it }
                    }
                }
            }
        }
    }
}
