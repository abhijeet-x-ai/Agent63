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
        AISettingsEntity::class
    ],
    version = 3,
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

        @Volatile
        private var instance: DevStationDatabase? = null

        fun getInstance(context: Context): DevStationDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DevStationDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_2_3)
                    .build().also { instance = it }
            }
        }
    }
}
