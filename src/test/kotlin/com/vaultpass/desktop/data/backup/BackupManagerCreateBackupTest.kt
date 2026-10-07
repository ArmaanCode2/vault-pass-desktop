package com.vaultpass.desktop.data.backup

import com.vaultpass.desktop.data.database.SQLiteConnectionManager
import com.vaultpass.desktop.data.database.SQLiteVaultDataSource
import com.vaultpass.desktop.data.database.closeAndWait
import com.vaultpass.desktop.data.models.VaultEntity
import com.vaultpass.desktop.data.repository.VaultRepositoryImpl
import com.vaultpass.desktop.data.sync.FakeCryptoManager
import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.VaultRepository
import com.vaultpass.desktop.domain.backup.BackupRepository
import com.vaultpass.desktop.domain.backup.models.BackupFormat
import com.vaultpass.desktop.domain.backup.models.BackupResult
import com.vaultpass.desktop.domain.backup.models.format.VpbFile
import com.vaultpass.desktop.domain.metadata.MetadataRepository
import com.vaultpass.desktop.domain.metadata.VaultDatabaseFileState
import com.vaultpass.desktop.domain.models.EncryptionConfig
import com.vaultpass.desktop.domain.models.KdfConfig
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.domain.models.VaultMetadata
import com.vaultpass.desktop.domain.security.SecureByteArray
import com.vaultpass.desktop.domain.session.SessionKeyStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * createBackup on the real SQLite vault: what it holds, and that a lock before or during the
 * snapshot, or between the snapshot and the encryption, fails it without writing a file.
 */
class BackupManagerCreateBackupTest {

    private lateinit var dbFile: File
    private lateinit var manager: SQLiteConnectionManager
    private lateinit var dataSource: SQLiteVaultDataSource
    private lateinit var crypto: FakeCryptoManager
    private lateinit var vault: HookedVault
    private lateinit var backups: BackupManagerImpl
    private val saved = CopyOnWriteArrayList<ByteArray>()

    /** The real repository; [beforeRead] and [afterRead] run around each entry list read. */
    private class HookedVault(private val real: VaultRepository) : VaultRepository by real {
        @Volatile var beforeRead: ((isDeleted: Boolean) -> Unit)? = null
        @Volatile var afterRead: ((isDeleted: Boolean) -> Unit)? = null

        override suspend fun getAllEntries(isDeleted: Boolean): RepositoryResult<List<VaultEntry>> {
            beforeRead?.invoke(isDeleted)
            return real.getAllEntries(isDeleted).also { afterRead?.invoke(isDeleted) }
        }

        // The block's reads must go through this wrapper to reach the hooks.
        override suspend fun <T> transaction(block: suspend VaultRepository.() -> T): RepositoryResult<T> =
            real.transaction { this@HookedVault.block() }
    }

    private inner class RecordingBackupRepository : BackupRepository {
        override suspend fun saveBackup(data: ByteArray, destinationIdentifier: String): BackupResult<Unit> {
            saved += data
            return BackupResult.Success(Unit)
        }
        override suspend fun loadBackup(sourceIdentifier: String): BackupResult<ByteArray> = throw UnsupportedOperationException()
    }

    private class Metadata : MetadataRepository {
        override val metadataFile = File("metadata.json")
        override val databaseFile = File("vaultpass.db")
        override suspend fun databaseFileState() = VaultDatabaseFileState.HAS_ENTRIES
        override suspend fun getMetadata() = VaultMetadata(
            initialized = true, vaultVersion = 1, metadataVersion = 1, kdfVersion = 1, encryptionVersion = 1,
            settingsVersion = 1, migrationVersion = 1, createdAt = 1L, lastOpenedAt = 1L,
            createdWithAppVersion = "1.0.0", lastOpenedAppVersion = "1.0.0",
            kdfConfig = KdfConfig("PBKDF2WithHmacSHA256", 1000, Base64.getEncoder().encodeToString(ByteArray(16) { 1 }), "aGFzaA=="),
            encryptionConfig = EncryptionConfig("AES/GCM/NoPadding", "d3JhcHBlZA==", "aXY=")
        )
        override suspend fun saveMetadata(metadata: VaultMetadata) {}
        override suspend fun initializeMetadata(): VaultMetadata = throw UnsupportedOperationException()
        override suspend fun discardCreatedVault() {}
    }

    private class NoSessionKey : SessionKeyStorage {
        override fun hasProtectedSession() = false
        override fun saveProtectedSession(dek: SecureByteArray) {}
        override fun loadProtectedSession(): SecureByteArray? = null
        override fun clearProtectedSession() {}
    }

    @BeforeEach
    fun setUp() {
        dbFile = File.createTempFile("vaultpass_backup_test_", ".db")
        manager = SQLiteConnectionManager(dbFile.absolutePath)
        manager.openConnection()
        dataSource = SQLiteVaultDataSource(manager)
        crypto = FakeCryptoManager()
        vault = HookedVault(VaultRepositoryImpl(dataSource, crypto))
        backups = BackupManagerImpl(
            vault, Metadata(), crypto, RecordingBackupRepository(), BackupSerializerImpl(), BackupValidatorImpl(), NoSessionKey()
        )
        runBlocking {
            vault.createEntry(VaultEntry(id = "active-1", title = "Mail", username = "me", secret = "pw", createdAt = 1L, updatedAt = 1L))
            vault.createEntry(
                VaultEntry(id = "binned-1", title = "Old", username = "me", secret = "old", isDeleted = true, deletedAt = 5L, createdAt = 1L, updatedAt = 5L)
            )
            // A row that can't be decrypted.
            dataSource.insert(
                VaultEntity("broken-1", "PASSWORD", byteArrayOf(9, 9, 9), 1L, 1L, isFavorite = false, isDeleted = false, deletedAt = null, syncVersion = 1)
            )
        }
    }

    @AfterEach
    fun tearDown() {
        manager.closeAndWait()
        for (suffix in listOf("", "-wal", "-shm")) File(dbFile.absolutePath + suffix).delete()
    }

    /** What SessionManagerImpl.lock() does, in its order: close the database, then wipe the key. */
    private fun lockVault() {
        manager.closeConnection()
        crypto.locked = true
    }

    private fun assertLockedFailure(result: BackupResult<*>) {
        assertTrue(result is BackupResult.Failure, "Got $result")
        val message = (result as BackupResult.Failure).error.message.orEmpty()
        assertTrue(message.contains("locked"), message)
        assertTrue(saved.isEmpty(), "No backup file may be written")
    }

    @Test
    fun aBackupHoldsActiveAndRecycledEntries_butNotOnesThatCantBeDecrypted() = runBlocking {
        val result = backups.createBackup("vaultpass_backup.vpb")
        assertTrue(result is BackupResult.Success, "Got $result")
        assertEquals(1, saved.size)

        val file = Json { ignoreUnknownKeys = true }.decodeFromString(VpbFile.serializer(), String(saved.single(), Charsets.UTF_8))
        val plain = crypto.decryptData(Base64.getDecoder().decode(file.encryptedPayloadBase64))
        val payload = (BackupSerializerImpl().deserialize(plain, BackupFormat.JSON_V1) as BackupResult.Success).data
        assertEquals(setOf("active-1", "binned-1"), payload.entries.map { it.id }.toSet())
        assertTrue(payload.entries.single { it.id == "binned-1" }.isDeleted)
    }

    @Test
    fun aBackupWhileLocked_failsWithoutWritingAFile() = runBlocking {
        lockVault()
        assertLockedFailure(backups.createBackup("vaultpass_backup.vpb"))
    }

    @Test
    fun aLockDuringTheSnapshot_failsWithoutWritingAFile() = runBlocking {
        // Locks just as the first list is read: a closed database reads as empty from here.
        vault.beforeRead = { isDeleted -> if (!isDeleted) lockVault() }
        assertLockedFailure(backups.createBackup("vaultpass_backup.vpb"))
    }

    @Test
    fun aLockBetweenTheSnapshotAndTheEncryption_failsWithoutWritingAFile() = runBlocking {
        // Only the close: the encryption still "works", as when it read the key just before the
        // wipe zero-filled it. The transaction's end must still refuse it.
        vault.afterRead = { isDeleted -> if (isDeleted) manager.closeConnection() }
        assertLockedFailure(backups.createBackup("vaultpass_backup.vpb"))
    }

    @Test
    fun aFullLockBetweenTheSnapshotAndTheEncryption_failsWithoutWritingAFile() = runBlocking {
        vault.afterRead = { isDeleted -> if (isDeleted) lockVault() }
        assertLockedFailure(backups.createBackup("vaultpass_backup.vpb"))
    }
}
