package com.vaultpass.desktop.data.repository

import com.vaultpass.desktop.data.database.SQLiteConnectionManager
import com.vaultpass.desktop.data.database.SQLiteVaultDataSource
import com.vaultpass.desktop.data.models.VaultEntity
import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.crypto.CryptoManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.io.File

class RecycleBinAutoCleanupTest {

    private lateinit var tempDbFile: File
    private lateinit var connectionManager: SQLiteConnectionManager
    private lateinit var dataSource: SQLiteVaultDataSource
    private lateinit var repository: VaultRepositoryImpl
    private val mockCryptoManager = mock<CryptoManager>()

    @BeforeEach
    fun setUp() {
        tempDbFile = File.createTempFile("vaultpass_recycle_test_", ".db")
        connectionManager = SQLiteConnectionManager(tempDbFile.absolutePath)
        connectionManager.openConnection()
        dataSource = SQLiteVaultDataSource(connectionManager)
        repository = VaultRepositoryImpl(dataSource, mockCryptoManager)
    }

    @AfterEach
    fun tearDown() {
        connectionManager.closeConnection()
        if (tempDbFile.exists()) {
            tempDbFile.delete()
        }
    }

    @Test
    fun testPurgeDeletesOnlyExpiredRecycledEntries() = runBlocking {
        val now = System.currentTimeMillis()
        val dayMillis = 24L * 60L * 60L * 1000L

        val entry1 = VaultEntity(
            id = "entry1",
            type = "password",
            encryptedPayload = byteArrayOf(1, 2, 3),
            createdAt = now - (40L * dayMillis),
            updatedAt = now - (35L * dayMillis),
            isFavorite = false,
            isDeleted = true,
            deletedAt = now - (35L * dayMillis),
            syncVersion = 1
        )
        val entry2 = VaultEntity(
            id = "entry2",
            type = "password",
            encryptedPayload = byteArrayOf(1, 2, 3),
            createdAt = now - (10L * dayMillis),
            updatedAt = now - (5L * dayMillis),
            isFavorite = false,
            isDeleted = true,
            deletedAt = now - (5L * dayMillis),
            syncVersion = 1
        )
        val entry3 = VaultEntity(
            id = "entry3",
            type = "password",
            encryptedPayload = byteArrayOf(1, 2, 3),
            createdAt = now - (100L * dayMillis),
            updatedAt = now - (100L * dayMillis),
            isFavorite = false,
            isDeleted = false,
            deletedAt = null,
            syncVersion = 1
        )
        val entry4 = VaultEntity(
            id = "entry4",
            type = "password",
            encryptedPayload = byteArrayOf(1, 2, 3),
            createdAt = now - (20L * dayMillis),
            updatedAt = now - (20L * dayMillis),
            isFavorite = false,
            isDeleted = true,
            deletedAt = null,
            syncVersion = 1
        )

        dataSource.insert(entry1)
        dataSource.insert(entry2)
        dataSource.insert(entry3)
        dataSource.insert(entry4)

        val threshold = now - (30L * dayMillis)
        val purgeResult = repository.purgeRecycleBinOlderThan(threshold)

        assertTrue(purgeResult is RepositoryResult.Success)
        assertEquals(1, (purgeResult as RepositoryResult.Success).data)

        // Verify: Only Entry 1 is purged. Entry 2, 3, 4 remain.
        assertNull(dataSource.getById("entry1"))
        assertNotNull(dataSource.getById("entry2"))
        assertNotNull(dataSource.getById("entry3"))
        assertNotNull(dataSource.getById("entry4"))
    }

    @Test
    fun testBatchRestoreRestoresAllSelectedIds() = runBlocking {
        val now = System.currentTimeMillis()
        val e1 = VaultEntity(
            id = "id1",
            type = "password",
            encryptedPayload = byteArrayOf(1),
            createdAt = now,
            updatedAt = now,
            isFavorite = false,
            isDeleted = true,
            deletedAt = now,
            syncVersion = 1
        )
        val e2 = VaultEntity(
            id = "id2",
            type = "password",
            encryptedPayload = byteArrayOf(2),
            createdAt = now,
            updatedAt = now,
            isFavorite = false,
            isDeleted = true,
            deletedAt = now,
            syncVersion = 1
        )
        val e3 = VaultEntity(
            id = "id3",
            type = "password",
            encryptedPayload = byteArrayOf(3),
            createdAt = now,
            updatedAt = now,
            isFavorite = false,
            isDeleted = true,
            deletedAt = now,
            syncVersion = 1
        )

        dataSource.insert(e1)
        dataSource.insert(e2)
        dataSource.insert(e3)

        val restoreResult = repository.restoreEntries(listOf("id1", "id2"))
        assertTrue(restoreResult is RepositoryResult.Success)

        val restored1 = dataSource.getById("id1")
        assertNotNull(restored1)
        assertFalse(restored1!!.isDeleted)
        assertNull(restored1.deletedAt)

        val restored2 = dataSource.getById("id2")
        assertNotNull(restored2)
        assertFalse(restored2!!.isDeleted)
        assertNull(restored2.deletedAt)

        val unchanged3 = dataSource.getById("id3")
        assertNotNull(unchanged3)
        assertTrue(unchanged3!!.isDeleted)
        assertNotNull(unchanged3.deletedAt)
    }

    @Test
    fun testBatchHardDeletePermanentlyRemovesOnlySelectedIds() = runBlocking {
        val now = System.currentTimeMillis()
        val e1 = VaultEntity(
            id = "id1",
            type = "password",
            encryptedPayload = byteArrayOf(1),
            createdAt = now,
            updatedAt = now,
            isFavorite = false,
            isDeleted = true,
            deletedAt = now,
            syncVersion = 1
        )
        val e2 = VaultEntity(
            id = "id2",
            type = "password",
            encryptedPayload = byteArrayOf(2),
            createdAt = now,
            updatedAt = now,
            isFavorite = false,
            isDeleted = true,
            deletedAt = now,
            syncVersion = 1
        )
        val e3 = VaultEntity(
            id = "id3",
            type = "password",
            encryptedPayload = byteArrayOf(3),
            createdAt = now,
            updatedAt = now,
            isFavorite = false,
            isDeleted = true,
            deletedAt = now,
            syncVersion = 1
        )

        dataSource.insert(e1)
        dataSource.insert(e2)
        dataSource.insert(e3)

        val hardDeleteResult = repository.hardDeleteEntries(listOf("id1", "id2"))
        assertTrue(hardDeleteResult is RepositoryResult.Success)

        assertNull(dataSource.getById("id1"))
        assertNull(dataSource.getById("id2"))
        assertNotNull(dataSource.getById("id3"))
    }

    @Test
    fun testEmptyRecycleBinRemovesAllDeletedEntriesWithoutTouchingActive() = runBlocking {
        val now = System.currentTimeMillis()
        val active1 = VaultEntity(
            id = "act1",
            type = "password",
            encryptedPayload = byteArrayOf(1),
            createdAt = now,
            updatedAt = now,
            isFavorite = false,
            isDeleted = false,
            deletedAt = null,
            syncVersion = 1
        )
        val active2 = VaultEntity(
            id = "act2",
            type = "password",
            encryptedPayload = byteArrayOf(2),
            createdAt = now,
            updatedAt = now,
            isFavorite = false,
            isDeleted = false,
            deletedAt = null,
            syncVersion = 1
        )
        val deleted1 = VaultEntity(
            id = "del1",
            type = "password",
            encryptedPayload = byteArrayOf(3),
            createdAt = now,
            updatedAt = now,
            isFavorite = false,
            isDeleted = true,
            deletedAt = now,
            syncVersion = 1
        )
        val deleted2 = VaultEntity(
            id = "del2",
            type = "password",
            encryptedPayload = byteArrayOf(4),
            createdAt = now,
            updatedAt = now,
            isFavorite = false,
            isDeleted = true,
            deletedAt = now,
            syncVersion = 1
        )

        dataSource.insert(active1)
        dataSource.insert(active2)
        dataSource.insert(deleted1)
        dataSource.insert(deleted2)

        val emptyResult = repository.emptyRecycleBin()
        assertTrue(emptyResult is RepositoryResult.Success)

        assertNotNull(dataSource.getById("act1"))
        assertNotNull(dataSource.getById("act2"))
        assertNull(dataSource.getById("del1"))
        assertNull(dataSource.getById("del2"))
    }
}
