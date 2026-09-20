package com.devstation.android.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ProjectEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        AppSettingsEntity::class,
        RecentFileEntity::class,
        EditorSettingsEntity::class
    ],
    version = 2,
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

    companion object {
        private const val DATABASE_NAME = "devstation_db"

        @Volatile
        private var instance: DevStationDatabase? = null

        fun getInstance(context: Context): DevStationDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DevStationDatabase::class.java,
                    DATABASE_NAME
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}
