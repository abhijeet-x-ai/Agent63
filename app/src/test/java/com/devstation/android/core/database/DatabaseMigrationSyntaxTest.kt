package com.devstation.android.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class DatabaseMigrationSyntaxTest {

    private fun createCaptureDatabase(executedSql: MutableList<String>): SupportSQLiteDatabase {
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                val sql = args[0] as? String
                if (sql != null) {
                    executedSql.add(sql)
                }
            }
            null
        }
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler
        ) as SupportSQLiteDatabase
    }

    @Test
    fun testMigration1To2CreatesRecentFilesAndEditorSettings() {
        val executedSql = mutableListOf<String>()
        val db = createCaptureDatabase(executedSql)

        DevStationDatabase.MIGRATION_1_2.migrate(db)

        assertEquals(1, DevStationDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, DevStationDatabase.MIGRATION_1_2.endVersion)
        assertTrue(executedSql.any { it.contains("CREATE TABLE IF NOT EXISTS `recent_files`") })
        assertTrue(executedSql.any { it.contains("CREATE TABLE IF NOT EXISTS `editor_settings`") })
    }

    @Test
    fun testMigration6To7CreatesGitHubAccountsMatchingRoomSchema() {
        val executedSql = mutableListOf<String>()
        val db = createCaptureDatabase(executedSql)

        DevStationDatabase.MIGRATION_6_7.migrate(db)

        assertEquals(6, DevStationDatabase.MIGRATION_6_7.startVersion)
        assertEquals(7, DevStationDatabase.MIGRATION_6_7.endVersion)

        // Find the CREATE TABLE for github_accounts
        val createSql = executedSql.firstOrNull { it.contains("CREATE TABLE IF NOT EXISTS `github_accounts`") }
        assertTrue("github_accounts table must be created", createSql != null)

        // Critical Room Schema requirement: Room expects NO default value specified in DDL
        // because entity columns have no defaultValue annotation.
        assertFalse(
            "github_accounts must not contain 'DEFAULT NULL' or 'DEFAULT 0' which breaks Room schema validation",
            createSql!!.contains("DEFAULT", ignoreCase = true)
        )

        // Required columns
        assertTrue(createSql.contains("`id` TEXT NOT NULL"))
        assertTrue(createSql.contains("`username` TEXT NOT NULL"))
        assertTrue(createSql.contains("`displayName` TEXT"))
        assertTrue(createSql.contains("`avatarUrl` TEXT"))
        assertTrue(createSql.contains("`credentialAlias` TEXT NOT NULL"))
        assertTrue(createSql.contains("`tokenType` TEXT NOT NULL"))
        assertTrue(createSql.contains("`scopesCsv` TEXT NOT NULL"))
        assertTrue(createSql.contains("`isActive` INTEGER NOT NULL"))
        assertTrue(createSql.contains("`createdAt` INTEGER NOT NULL"))
        assertTrue(createSql.contains("`updatedAt` INTEGER NOT NULL"))
        assertTrue(createSql.contains("PRIMARY KEY(`id`)"))

        // Required indices
        assertTrue(executedSql.any { it.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_github_accounts_credentialAlias`") })
        assertTrue(executedSql.any { it.contains("CREATE INDEX IF NOT EXISTS `index_github_accounts_username`") })
    }

    @Test
    fun testAllMigrationsExecuteWithoutThrowing() {
        val executedSql = mutableListOf<String>()
        val db = createCaptureDatabase(executedSql)

        DevStationDatabase.MIGRATION_1_2.migrate(db)
        DevStationDatabase.MIGRATION_2_3.migrate(db)
        DevStationDatabase.MIGRATION_3_4.migrate(db)
        DevStationDatabase.MIGRATION_4_5.migrate(db)
        DevStationDatabase.MIGRATION_5_6.migrate(db)
        DevStationDatabase.MIGRATION_6_7.migrate(db)

        assertTrue(executedSql.size >= 15)
    }
}
