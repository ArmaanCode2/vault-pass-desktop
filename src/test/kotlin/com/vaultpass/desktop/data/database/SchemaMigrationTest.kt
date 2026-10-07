package com.vaultpass.desktop.data.database

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** The PRAGMA user_version migrations run by SQLiteConnectionManager. */
class SchemaMigrationTest {

    private val dbFile: File = File.createTempFile("vaultpass_migration_test_", ".db")

    @AfterEach
    fun tearDown() {
        listOf("", "-wal", "-shm", ".before-schema-v${DatabaseQueries.SCHEMA_VERSION}.bak")
            .forEach { File(dbFile.absolutePath + it).delete() }
    }

    @Test
    fun freshDatabase_getsTheCurrentSchema() {
        val manager = SQLiteConnectionManager(dbFile.absolutePath)
        manager.openConnection()
        try {
            val conn = manager.getConnection()
            assertEquals(DatabaseQueries.SCHEMA_VERSION, userVersion(conn))
            assertEquals(setOf("vault_entry", "paired_device", "sync_tombstone"), tables(conn))
        } finally {
            manager.closeAndWait()
        }
    }

    @Test
    fun unversionedVault_isUpgradedWithItsDataIntact() {
        // A vault from before schema versioning: the two original tables, user_version 0.
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(DatabaseQueries.CREATE_VAULT_ENTRY_TABLE)
                stmt.execute(DatabaseQueries.CREATE_PAIRED_DEVICE_TABLE)
                stmt.execute(
                    "INSERT INTO vault_entry (id, type, encrypted_payload, created_at, updated_at, is_deleted, deleted_at) " +
                        "VALUES ('entry-1', 'PASSWORD', x'0102', 10, 20, 1, 15)"
                )
                stmt.execute("INSERT INTO paired_device (device_id, device_name, shared_secret, paired_at, last_sync_at) VALUES ('phone', 'Pixel', 's', 1, 99)")
            }
            assertEquals(0, userVersion(conn))
        }

        repeat(2) { // opening again must be a no-op
            val manager = SQLiteConnectionManager(dbFile.absolutePath)
            manager.openConnection()
            try {
                val conn = manager.getConnection()
                assertEquals(DatabaseQueries.SCHEMA_VERSION, userVersion(conn))
                assertTrue("sync_tombstone" in tables(conn))
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT id, updated_at, deleted_at FROM vault_entry").use { rs ->
                        assertTrue(rs.next())
                        assertEquals("entry-1", rs.getString(1))
                        assertEquals(20L, rs.getLong(2))
                        assertEquals(15L, rs.getLong(3))
                        assertFalse(rs.next())
                    }
                    stmt.executeQuery("SELECT last_sync_at FROM paired_device WHERE device_id = 'phone'").use { rs ->
                        assertTrue(rs.next())
                        assertEquals(99L, rs.getLong(1))
                    }
                    stmt.executeQuery("SELECT COUNT(*) FROM sync_tombstone").use { rs ->
                        rs.next()
                        assertEquals(0, rs.getInt(1))
                    }
                }
            } finally {
                manager.closeAndWait()
            }
        }
    }

    @Test
    fun partlyMigratedVault_resumesAtTheNextVersion() {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(DatabaseQueries.CREATE_VAULT_ENTRY_TABLE)
                stmt.execute(DatabaseQueries.CREATE_PAIRED_DEVICE_TABLE)
                stmt.execute("PRAGMA user_version = 1")
            }
        }
        val manager = SQLiteConnectionManager(dbFile.absolutePath)
        manager.openConnection()
        try {
            assertEquals(DatabaseQueries.SCHEMA_VERSION, userVersion(manager.getConnection()))
            assertTrue("sync_tombstone" in tables(manager.getConnection()))
        } finally {
            manager.closeAndWait()
        }
    }

    private fun userVersion(conn: Connection): Int =
        conn.createStatement().use { stmt -> stmt.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }

    private fun tables(conn: Connection): Set<String> =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }
}
