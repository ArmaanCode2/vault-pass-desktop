package com.vaultpass.desktop.data.repository

import com.vaultpass.desktop.data.database.SQLiteConnectionManager
import com.vaultpass.desktop.data.database.SQLiteVaultDataSource
import com.vaultpass.desktop.data.models.VaultEntity
import com.vaultpass.desktop.data.sync.FakeCryptoManager
import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.backup.models.format.VaultEntryBackup
import com.vaultpass.desktop.domain.models.CustomField
import com.vaultpass.desktop.domain.models.SyncTombstone
import com.vaultpass.desktop.domain.models.VaultEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/** Tombstones left by permanent deletes, id renames, custom fields and corrupted rows. */
class SyncTombstoneAndCustomFieldTest {

    private lateinit var tempDbFile: File
    private lateinit var connectionManager: SQLiteConnectionManager
    private lateinit var dataSource: SQLiteVaultDataSource
    private lateinit var crypto: FakeCryptoManager
    private lateinit var repository: VaultRepositoryImpl

    @BeforeEach
    fun setUp() {
        tempDbFile = File.createTempFile("vaultpass_tombstone_test_", ".db")
        connectionManager = SQLiteConnectionManager(tempDbFile.absolutePath)
        connectionManager.openConnection()
        dataSource = SQLiteVaultDataSource(connectionManager)
        crypto = FakeCryptoManager()
        repository = VaultRepositoryImpl(dataSource, crypto)
    }

    @AfterEach
    fun tearDown() {
        connectionManager.closeConnection()
        tempDbFile.delete()
    }

    private fun entry(id: String, deletedAt: Long? = null) = VaultEntry(
        id = id,
        title = "Title $id",
        username = "user",
        secret = "secret-$id",
        isDeleted = deletedAt != null,
        deletedAt = deletedAt,
        createdAt = 10L,
        updatedAt = 20L
    )

    private suspend fun tombstones(): Set<SyncTombstone> = (repository.getTombstones() as RepositoryResult.Success).data.toSet()

    private suspend fun active(): List<VaultEntry> = (repository.observeAllEntries(false).first() as RepositoryResult.Success).data

    @Test
    fun hardDelete_leavesATombstoneWithTheDeletionTime() = runBlocking {
        repository.createEntry(entry("a", deletedAt = 500L))
        repository.createEntry(entry("b"))

        val before = System.currentTimeMillis()
        repository.hardDeleteEntry("a")
        repository.hardDeleteEntry("b") // never in the recycle bin: deleted "now"

        val byId = tombstones().associateBy { it.syncId }
        assertEquals(500L, byId.getValue("a").deletedAt)
        assertTrue(byId.getValue("b").deletedAt >= before)
    }

    @Test
    fun batchDeleteEmptyAndPurge_allLeaveTombstones() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        repository.createEntry(entry("batch-1", deletedAt = 100L))
        repository.createEntry(entry("batch-2", deletedAt = 200L))
        repository.createEntry(entry("old", deletedAt = now - 40 * day))
        repository.createEntry(entry("recent", deletedAt = now - day))
        repository.createEntry(entry("bin", deletedAt = now - 2 * day))
        repository.createEntry(entry("active"))

        repository.hardDeleteEntries(listOf("batch-1", "batch-2"))
        assertEquals(setOf(SyncTombstone("batch-1", 100L), SyncTombstone("batch-2", 200L)), tombstones())

        assertEquals(1, (repository.purgeRecycleBinOlderThan(now - 30 * day) as RepositoryResult.Success).data)
        assertTrue(SyncTombstone("old", now - 40 * day) in tombstones())
        assertFalse(tombstones().any { it.syncId == "recent" }, "Only purged entries get a tombstone")

        repository.emptyRecycleBin()
        val ids = tombstones().map { it.syncId }.toSet()
        assertEquals(setOf("batch-1", "batch-2", "old", "recent", "bin"), ids)
        assertEquals(listOf("active"), active().map { it.id })
    }

    @Test
    fun creatingTheEntryAgain_removesItsTombstone() = runBlocking {
        repository.createEntry(entry("a"))
        repository.hardDeleteEntry("a")
        assertEquals(1, tombstones().size)

        repository.createEntry(entry("a"))
        assertTrue(tombstones().isEmpty())
    }

    @Test
    fun failedTransaction_rollsBackTheTombstoneToo() = runBlocking {
        repository.createEntry(entry("a"))
        val result = repository.transaction {
            hardDeleteEntry("a")
            throw IllegalStateException("boom")
        }
        assertTrue(result is RepositoryResult.Error)
        assertTrue(tombstones().isEmpty())
        assertEquals(listOf("a"), active().map { it.id })
    }

    @Test
    fun changeEntryId_movesTheRowAndRefreshesTheCache() = runBlocking {
        repository.createEntry(entry("old-id"))
        assertEquals(listOf("old-id"), active().map { it.id }) // cached under the old id

        assertTrue(repository.changeEntryId("old-id", "new-id") is RepositoryResult.Success)

        val renamed = active().single()
        assertEquals("new-id", renamed.id)
        assertEquals("secret-old-id", renamed.secret)
        assertNull((repository.getEntryById("old-id") as RepositoryResult.Success).data)
        assertTrue(repository.changeEntryId("missing", "other") is RepositoryResult.Error)
    }

    @Test
    fun customFields_areStoredEncryptedAndSurviveEveryEdit() = runBlocking {
        val fields = listOf(CustomField("PIN", "1234"), CustomField("Security answer", "blue"))
        repository.createEntry(entry("a").copy(customFields = fields))
        repository.clearCache()

        val stored = (repository.getEntryById("a") as RepositoryResult.Success).data!!
        assertEquals(fields, stored.customFields)

        // Favorite toggles, soft delete and restore all go through copy() + updateEntry.
        repository.updateEntry(stored.copy(isFavorite = true, updatedAt = 30L))
        repository.softDeleteEntry("a")
        repository.restoreEntry("a")
        repository.clearCache()
        assertEquals(fields, (repository.getEntryById("a") as RepositoryResult.Success).data!!.customFields)
    }

    @Test
    fun payloadsAndBackupsWithoutCustomFields_stillLoad() = runBlocking {
        // An entry written before custom fields existed.
        val oldPayload = """{"type":"PASSWORD","title":"Old","username":"u","secret":"s","url":"","notes":"","category":null,"tags":[],"history":[]}"""
        dataSource.insert(
            VaultEntity("legacy", "PASSWORD", crypto.encryptData(oldPayload.toByteArray()), 1L, 2L, false, false, null, 1)
        )
        val loaded = (repository.getEntryById("legacy") as RepositoryResult.Success).data!!
        assertEquals("Old", loaded.title)
        assertTrue(loaded.customFields.isEmpty())

        val oldBackup = """{"id":"b","title":"T","username":"u","secret":"s","url":"","notes":"","isFavorite":false,"categoryId":null,"tagIds":[],"createdAt":1,"updatedAt":2}"""
        assertTrue(Json { ignoreUnknownKeys = true }.decodeFromString(VaultEntryBackup.serializer(), oldBackup).customFields.isEmpty())
    }

    @Test
    fun undecryptableRows_becomeFlaggedPlaceholders() = runBlocking {
        dataSource.insert(VaultEntity("broken", "PASSWORD", byteArrayOf(1, 2, 3), 1L, 2L, false, false, null, 1))
        dataSource.insert(VaultEntity("broken-bin", "PASSWORD", byteArrayOf(4, 5, 6), 1L, 2L, false, true, 7L, 1))

        val placeholder = active().single()
        assertTrue(placeholder.isCorrupted)
        assertEquals("broken", placeholder.id)

        val binned = (repository.observeAllEntries(true).first() as RepositoryResult.Success).data.single()
        assertTrue(binned.isCorrupted)
        assertTrue(binned.isDeleted)
        assertEquals(7L, binned.deletedAt)
    }
}
