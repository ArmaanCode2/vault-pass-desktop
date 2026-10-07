package com.vaultpass.desktop.data.database

import com.vaultpass.desktop.domain.db.DatabaseBackupException
import com.vaultpass.desktop.domain.db.NewerVaultException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

/** The backup before a schema upgrade and the newer-vault guard in SQLiteConnectionManager. */
class SchemaBackupTest {

    @TempDir
    lateinit var dir: File

    private val dbFile get() = File(dir, "vaultpass.db")
    private val backupFile get() = File(dir, "vaultpass.db.before-schema-v${DatabaseQueries.SCHEMA_VERSION}.bak")
    private val tempFile get() = File(dir, "vaultpass.db.backup-tmp")

    /** A version 1 vault (rollback journal, not WAL) with [entries] rows, written with plain sqlite-jdbc. */
    private fun createV1Database(file: File, entries: Int = 3) {
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
                stmt.execute("PRAGMA user_version = 1")
            }
        }
    }

    /** A vault from a newer version: user_version 3 and a table this version doesn't know. */
    private fun createV3Database(file: File) {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(DatabaseQueries.CREATE_VAULT_ENTRY_TABLE)
                stmt.execute(DatabaseQueries.CREATE_PAIRED_DEVICE_TABLE)
                stmt.execute(DatabaseQueries.CREATE_SYNC_TOMBSTONE_TABLE)
                stmt.execute("CREATE TABLE future_table (id TEXT PRIMARY KEY)")
                stmt.execute(
                    "INSERT INTO vault_entry (id, type, encrypted_payload, created_at, updated_at) " +
                        "VALUES ('entry-1', 'PASSWORD', x'0102', 1, 1)"
                )
                stmt.execute("PRAGMA user_version = 3")
            }
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun <T> withDatabase(file: File, block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use(block)

    private fun userVersion(conn: Connection): Int =
        conn.createStatement().use { stmt -> stmt.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }

    private fun entryCount(conn: Connection): Int =
        conn.createStatement().use { stmt -> stmt.executeQuery("SELECT COUNT(*) FROM vault_entry").use { rs -> rs.next(); rs.getInt(1) } }

    private fun quickCheck(conn: Connection): String =
        conn.createStatement().use { stmt -> stmt.executeQuery("PRAGMA quick_check").use { rs -> rs.next(); rs.getString(1) } }

    @Test
    fun v1Database_isBackedUpThenUpgraded() {
        createV1Database(dbFile, entries = 3)

        val manager = SQLiteConnectionManager(dbFile.absolutePath)
        manager.openConnection()
        try {
            assertEquals(DatabaseQueries.SCHEMA_VERSION, userVersion(manager.getConnection()))
            assertEquals(3, entryCount(manager.getConnection()))
        } finally {
            manager.closeAndWait()
        }

        assertTrue(backupFile.isFile)
        assertFalse(tempFile.exists())
        withDatabase(backupFile) { backup ->
            assertEquals("ok", quickCheck(backup))
            assertEquals(3, entryCount(backup))
            assertEquals(1, userVersion(backup))
        }
    }

    @Test
    fun upgradedDatabase_isNotBackedUpAgain() {
        createV1Database(dbFile)
        repeat(2) {
            val manager = SQLiteConnectionManager(dbFile.absolutePath)
            manager.openConnection()
            manager.closeAndWait()
        }
        val firstBackup = sha256(backupFile)
        val manager = SQLiteConnectionManager(dbFile.absolutePath)
        manager.openConnection()
        manager.closeAndWait()
        assertEquals(firstBackup, sha256(backupFile))
    }

    @Test
    fun newerDatabase_throwsAndIsNotChanged() {
        createV3Database(dbFile)
        val before = sha256(dbFile)
        val filesBefore = dir.list()!!.sorted()

        val manager = SQLiteConnectionManager(dbFile.absolutePath)
        val error = assertThrows(NewerVaultException::class.java) { manager.openConnection() }
        assertEquals(3, error.foundVersion)
        assertEquals(DatabaseQueries.SCHEMA_VERSION, error.supportedVersion)
        assertFalse(manager.connectionState.value)
        assertThrows(IllegalStateException::class.java) { manager.getConnection() }

        // The checks run again; nothing was cached from the failed open.
        assertThrows(NewerVaultException::class.java) { manager.openConnection() }

        assertEquals(before, sha256(dbFile))
        assertEquals(filesBefore, dir.list()!!.sorted())
    }

    @Test
    fun freshDatabase_isNotBackedUp() {
        val manager = SQLiteConnectionManager(dbFile.absolutePath)
        manager.openConnection()
        try {
            assertEquals(DatabaseQueries.SCHEMA_VERSION, userVersion(manager.getConnection()))
        } finally {
            manager.closeAndWait()
        }
        assertFalse(dir.list()!!.any { it.endsWith(".bak") || it.endsWith(".backup-tmp") }, dir.list()!!.joinToString())
    }

    @Test
    fun failingBackup_stopsTheUpgrade() {
        createV1Database(dbFile)
        val before = sha256(dbFile)
        // A folder with a file in it where the temporary copy goes can't be deleted or written.
        tempFile.mkdirs()
        File(tempFile, "blocker.txt").writeText("x")

        val manager = SQLiteConnectionManager(dbFile.absolutePath)
        val error = assertThrows(DatabaseBackupException::class.java) { manager.openConnection() }
        assertTrue(error.message!!.startsWith("Couldn't back up the vault before upgrading it: "), error.message)
        assertFalse(manager.connectionState.value)

        assertEquals(before, sha256(dbFile))
        withDatabase(dbFile) { conn -> assertEquals(1, userVersion(conn)) }
        assertFalse(backupFile.exists())

        // Once the obstacle is gone the next open backs up and upgrades.
        tempFile.deleteRecursively()
        manager.openConnection()
        try {
            assertEquals(DatabaseQueries.SCHEMA_VERSION, userVersion(manager.getConnection()))
        } finally {
            manager.closeAndWait()
        }
        assertTrue(backupFile.isFile)
    }

    @Test
    fun leftoverTempFile_isReplaced() {
        createV1Database(dbFile, entries = 2)
        tempFile.writeText("left over from an interrupted backup")

        val manager = SQLiteConnectionManager(dbFile.absolutePath)
        manager.openConnection()
        manager.closeAndWait()

        assertFalse(tempFile.exists())
        withDatabase(backupFile) { backup -> assertEquals(2, entryCount(backup)) }
    }

    @Test
    fun onlyTheNewestBackupIsKept() {
        createV1Database(dbFile, entries = 4)
        val older = File(dir, "vaultpass.db.before-schema-v1.bak").apply { writeText("old backup") }
        val sameVersion = backupFile.apply { writeText("stale backup of the same version") }
        val unrelated = File(dir, "notes.txt").apply { writeText("keep me") }
        val otherDatabase = File(dir, "other.db.before-schema-v1.bak").apply { writeText("not ours") }

        val manager = SQLiteConnectionManager(dbFile.absolutePath)
        manager.openConnection()
        manager.closeAndWait()

        assertFalse(older.exists())
        withDatabase(sameVersion) { backup -> assertEquals(4, entryCount(backup)) }
        assertTrue(unrelated.exists())
        assertTrue(otherDatabase.exists())
    }

    @Test
    fun pathWithApostrophe_works() {
        val folder = File(dir, "Bob's vault").apply { mkdirs() }
        val db = File(folder, "vaultpass.db")
        createV1Database(db, entries = 2)

        val manager = SQLiteConnectionManager(db.absolutePath)
        manager.openConnection()
        try {
            assertEquals(DatabaseQueries.SCHEMA_VERSION, userVersion(manager.getConnection()))
        } finally {
            manager.closeAndWait()
        }

        val backup = File(folder, "vaultpass.db.before-schema-v${DatabaseQueries.SCHEMA_VERSION}.bak")
        assertTrue(backup.isFile)
        withDatabase(backup) { conn ->
            assertEquals("ok", quickCheck(conn))
            assertEquals(2, entryCount(conn))
        }
    }

    /**
     * Builds a WAL database in a separate folder with [writes], then copies vaultpass.db and its -wal
     * into [dir] while the writer is still open, so the -wal still holds frames that were never
     * checkpointed into the main file. Returns the copied -wal.
     */
    private fun createWalDatabaseWithUncheckpointedFrames(writes: (java.sql.Statement) -> Unit): File {
        val source = File(dir, "source").apply { mkdirs() }
        val sourceDb = File(source, "vaultpass.db")
        DriverManager.getConnection("jdbc:sqlite:${sourceDb.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode = WAL")
                stmt.execute("PRAGMA wal_autocheckpoint = 0")
                writes(stmt)
            }
            java.nio.file.Files.copy(sourceDb.toPath(), dbFile.toPath())
            java.nio.file.Files.copy(File(source, "vaultpass.db-wal").toPath(), File(dir, "vaultpass.db-wal").toPath())
        }
        val wal = File(dir, "vaultpass.db-wal")
        assertTrue(wal.length() > 0, "The copied -wal holds the frames")
        return wal
    }

    @Test
    fun newerWalDatabase_throwsAndTheMainFileIsNotChanged() {
        val wal = createWalDatabaseWithUncheckpointedFrames { stmt ->
            stmt.execute(DatabaseQueries.CREATE_VAULT_ENTRY_TABLE)
            stmt.execute("CREATE TABLE future_table (id TEXT PRIMARY KEY)")
            stmt.execute(
                "INSERT INTO vault_entry (id, type, encrypted_payload, created_at, updated_at) " +
                    "VALUES ('entry-1', 'PASSWORD', x'0102', 1, 1)"
            )
            stmt.execute("PRAGMA user_version = 3")
        }
        val before = sha256(dbFile)

        val manager = SQLiteConnectionManager(dbFile.absolutePath)
        val error = assertThrows(NewerVaultException::class.java) { manager.openConnection() }
        assertEquals(3, error.foundVersion)
        assertThrows(NewerVaultException::class.java) { manager.openConnection() }

        // A read-write connection would have checkpointed the -wal into the main file on close.
        assertEquals(before, sha256(dbFile))
        assertTrue(wal.length() > 0, "The -wal was not checkpointed")
    }

    @Test
    fun v1WalDatabase_backupHoldsTheUncheckpointedRows() {
        createWalDatabaseWithUncheckpointedFrames { stmt ->
            stmt.execute(DatabaseQueries.CREATE_VAULT_ENTRY_TABLE)
            stmt.execute(DatabaseQueries.CREATE_PAIRED_DEVICE_TABLE)
            repeat(5) { i ->
                stmt.execute(
                    "INSERT INTO vault_entry (id, type, encrypted_payload, created_at, updated_at) " +
                        "VALUES ('entry-$i', 'PASSWORD', x'0102', $i, $i)"
                )
            }
            stmt.execute("PRAGMA user_version = 1")
        }

        val manager = SQLiteConnectionManager(dbFile.absolutePath)
        manager.openConnection()
        try {
            assertEquals(DatabaseQueries.SCHEMA_VERSION, userVersion(manager.getConnection()))
            assertEquals(5, entryCount(manager.getConnection()))
        } finally {
            manager.closeAndWait()
        }

        assertTrue(backupFile.isFile)
        assertFalse(File(dir, "vaultpass.db.backup-tmp-wal").exists())
        assertFalse(File(dir, "vaultpass.db.backup-tmp-shm").exists())
        val readOnly = org.sqlite.SQLiteConfig().apply { setReadOnly(true) }.toProperties()
        DriverManager.getConnection("jdbc:sqlite:${backupFile.absolutePath}", readOnly).use { backup ->
            assertEquals("ok", quickCheck(backup))
            assertEquals(5, entryCount(backup))
            assertEquals(1, userVersion(backup))
            val journalMode = backup.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA journal_mode").use { rs -> rs.next(); rs.getString(1) }
            }
            assertEquals("delete", journalMode.lowercase(), "The backup is one self-contained file")
        }
        assertFalse(File(dir, "${backupFile.name}-wal").exists())
        assertFalse(File(dir, "${backupFile.name}-shm").exists())
    }

    @Test
    fun vacuumIntoAcceptsABoundPath() {
        // sqlite-jdbc 3.45.1.0 binds the target of VACUUM INTO as a parameter.
        createV1Database(dbFile, entries = 1)
        val target = File(dir, "it's a copy.db")
        withDatabase(dbFile) { conn ->
            conn.prepareStatement("VACUUM INTO ?").use { stmt ->
                stmt.setString(1, target.absolutePath)
                stmt.execute()
            }
        }
        assertTrue(target.isFile)
        withDatabase(target) { conn -> assertEquals(1, entryCount(conn)) }
    }
}
