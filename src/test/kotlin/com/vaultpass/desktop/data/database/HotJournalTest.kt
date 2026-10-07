package com.vaultpass.desktop.data.database

import com.vaultpass.desktop.domain.db.NewerVaultException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * A database left with a hot rollback journal (a write cut off by a crash or power loss) can't be
 * read by the read-only version check; the version is then read from the file header instead.
 */
class HotJournalTest {

    @TempDir
    lateinit var dir: File

    private fun createDatabase(file: File, userVersion: Int, entries: Int) {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(DatabaseQueries.CREATE_VAULT_ENTRY_TABLE)
                stmt.execute(DatabaseQueries.CREATE_PAIRED_DEVICE_TABLE)
                repeat(entries) { i ->
                    stmt.execute(
                        "INSERT INTO vault_entry (id, type, encrypted_payload, created_at, updated_at) " +
                            "VALUES ('entry-$i', 'PASSWORD', x'0102', $i, $i)"
                    )
                }
                stmt.execute("PRAGMA user_version = $userVersion")
            }
        }
    }

    /**
     * Copies [source] and its journal to [target] in the middle of a large write, after SQLite has
     * already written changed pages into the database file: the copy is what a crash leaves behind.
     */
    private fun crashCopyDuringWrite(source: File, target: File) {
        DriverManager.getConnection("jdbc:sqlite:${source.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode = DELETE")
                // A tiny page cache makes SQLite spill changed pages into the database file before commit.
                stmt.execute("PRAGMA cache_size = 2")
            }
            conn.autoCommit = false
            conn.prepareStatement(
                "INSERT INTO vault_entry (id, type, encrypted_payload, created_at, updated_at) VALUES (?, 'PASSWORD', ?, 0, 0)"
            ).use { insert ->
                repeat(3_000) { i ->
                    insert.setString(1, "crashed-$i")
                    insert.setBytes(2, ByteArray(200) { (i + it).toByte() })
                    insert.executeUpdate()
                }
            }
            val journal = File(source.path + "-journal")
            assertTrue(journal.length() > 0, "The write has a journal")
            Files.copy(source.toPath(), target.toPath())
            Files.copy(journal.toPath(), File(target.path + "-journal").toPath())
            conn.rollback()
        }
    }

    private fun readOnlyUserVersion(file: File): Int {
        val readOnly = org.sqlite.SQLiteConfig().apply { setReadOnly(true) }.toProperties()
        return DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", readOnly).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) }
            }
        }
    }

    private fun count(conn: Connection, sql: String): Long =
        conn.createStatement().use { stmt -> stmt.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }

    @Test
    fun databaseWithAHotJournalOpensAndIsRolledBack() {
        val original = File(dir, "original.db")
        createDatabase(original, DatabaseQueries.SCHEMA_VERSION, entries = 2)
        val crashed = File(dir, "vaultpass.db")
        crashCopyDuringWrite(original, crashed)
        val journal = File(crashed.path + "-journal")
        assertTrue(journal.isFile)

        // The read-only check can't roll the journal back...
        assertThrows(Exception::class.java) { readOnlyUserVersion(crashed) }
        // ...but the header still says which version the database is.
        assertEquals(DatabaseQueries.SCHEMA_VERSION, SQLiteConnectionManager.readUserVersionFromHeader(crashed))

        val manager = SQLiteConnectionManager(crashed.absolutePath)
        manager.openConnection()
        try {
            val conn = manager.getConnection()
            assertEquals(2, count(conn, "SELECT COUNT(*) FROM vault_entry"), "The cut-off write was rolled back")
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA quick_check").use { rs -> rs.next(); assertEquals("ok", rs.getString(1)) }
            }
        } finally {
            manager.closeAndWait()
        }
        assertFalse(journal.exists(), "The rollback removed the journal")
    }

    @Test
    fun newerDatabaseWithAHotJournalIsStillRefused() {
        val original = File(dir, "original.db")
        createDatabase(original, DatabaseQueries.SCHEMA_VERSION + 1, entries = 2)
        val crashed = File(dir, "vaultpass.db")
        crashCopyDuringWrite(original, crashed)

        assertThrows(NewerVaultException::class.java) { SQLiteConnectionManager(crashed.absolutePath).openConnection() }
    }

    @Test
    fun headerReaderReadsUserVersion() {
        for (version in listOf(0, 1, 2, 300, 70_000)) {
            val file = File(dir, "v$version.db")
            createDatabase(file, version, entries = 1)
            assertEquals(version, SQLiteConnectionManager.readUserVersionFromHeader(file), "user_version $version")
        }
    }

    @Test
    fun headerReaderRejectsFilesThatAreNotDatabases() {
        val notADatabase = File(dir, "garbage.db").apply { writeText("this is not a SQLite database".repeat(200)) }
        assertThrows(IOException::class.java) { SQLiteConnectionManager.readUserVersionFromHeader(notADatabase) }

        val tooShort = File(dir, "short.db").apply { writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)) }
        assertThrows(IOException::class.java) { SQLiteConnectionManager.readUserVersionFromHeader(tooShort) }

        val empty = File(dir, "empty.db").apply { writeBytes(ByteArray(0)) }
        assertThrows(IOException::class.java) { SQLiteConnectionManager.readUserVersionFromHeader(empty) }
    }

    @Test
    fun damagedDatabaseStillReportsTheOriginalError() {
        val damaged = File(dir, "vaultpass.db").apply { writeText("this is not a SQLite database".repeat(200)) }

        val error = assertThrows(java.sql.SQLException::class.java) { SQLiteConnectionManager(damaged.absolutePath).openConnection() }

        assertFalse(error.message.orEmpty().contains("too short"), error.message)
        assertFalse(error.message.orEmpty().contains("isn't an SQLite database"), error.message)
    }
}
