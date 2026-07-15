package com.vaultpass.desktop.data.backup

import com.vaultpass.desktop.data.database.SQLiteConnectionManager
import com.vaultpass.desktop.data.database.SQLiteVaultDataSource
import com.vaultpass.desktop.data.repository.VaultRepositoryImpl
import com.vaultpass.desktop.data.crypto.CryptoManagerImpl
import com.vaultpass.desktop.domain.models.*
import com.vaultpass.desktop.domain.RepositoryResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class RestoreTraceTest {
    @Test
    fun testRestore() = runBlocking {
        println("Starting trace...")
        val dbFile = File("test_trace.db")
        if (dbFile.exists()) dbFile.delete()
        
        val connManager = SQLiteConnectionManager(dbFile.absolutePath)
        // connManager.initializeDatabase()
        
        val kdfProvider = com.vaultpass.desktop.data.crypto.PBKDF2KdfProvider()
        val secureRandomProvider = com.vaultpass.desktop.data.crypto.SecureRandomProviderImpl()
        val encryptionProvider = com.vaultpass.desktop.data.crypto.AESGcmEncryptionProvider()
        val keyManager = com.vaultpass.desktop.data.crypto.InMemoryKeyManager()
        val cryptoManager = CryptoManagerImpl(kdfProvider, secureRandomProvider, encryptionProvider, keyManager)
        
        // Setup initial vault
        val masterPassword = "password".toCharArray()
        cryptoManager.setupVault(com.vaultpass.desktop.domain.security.SecureCharArray(masterPassword.clone()))
        
        val localDataSource = SQLiteVaultDataSource(connManager)
        val vaultRepository = VaultRepositoryImpl(localDataSource, cryptoManager)
        
        val entry = VaultEntry(
            id = "test-uuid-123",
            title = "My Test Entry",
            username = "user",
            secret = "pass",
            url = "",
            notes = "",
            category = "cat-1",
            tags = emptyList(),
            history = emptyList(),
            isFavorite = false,
            isDeleted = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        
        vaultRepository.createEntry(entry)
        println("Inserted original entry")
        
        val backupRepo = object : com.vaultpass.desktop.domain.backup.BackupRepository {
            var savedBytes: ByteArray? = null
            override suspend fun saveBackup(fileBytes: ByteArray, destinationIdentifier: String): com.vaultpass.desktop.domain.backup.models.BackupResult<Unit> {
                savedBytes = fileBytes
                return com.vaultpass.desktop.domain.backup.models.BackupResult.Success(Unit)
            }
            override suspend fun loadBackup(sourceIdentifier: String): com.vaultpass.desktop.domain.backup.models.BackupResult<ByteArray> {
                return com.vaultpass.desktop.domain.backup.models.BackupResult.Success(savedBytes!!)
            }
        }
        
        val categoryRepo = object : com.vaultpass.desktop.domain.CategoryRepository {
            override fun getAllCategories() = kotlinx.coroutines.flow.flowOf(RepositoryResult.Success(emptyList<VaultCategory>()))
            override suspend fun getCategoryById(id: String) = RepositoryResult.Success<VaultCategory?>(null)
            override suspend fun insertCategory(category: VaultCategory) = RepositoryResult.Success(Unit)
            override suspend fun updateCategory(category: VaultCategory) = RepositoryResult.Success(Unit)
            override suspend fun deleteCategory(id: String) = RepositoryResult.Success(Unit)
        }
        
        val metadataRepo = object : com.vaultpass.desktop.domain.metadata.MetadataRepository {
            override suspend fun initializeMetadata(): VaultMetadata = getMetadata()
            override suspend fun getMetadata(): VaultMetadata = VaultMetadata(
                initialized = true, vaultVersion = 1, metadataVersion = 1, kdfVersion = 1, encryptionVersion = 1,
                settingsVersion = 1, migrationVersion = 1, createdAt = 0, lastOpenedAt = 0, createdWithAppVersion = "1",
                lastOpenedAppVersion = "1", 
                kdfConfig = KdfConfig("PBKDF2", 100000, "salt", "hash"),
                encryptionConfig = EncryptionConfig("AES-GCM", "dek", "iv")
            )
            override suspend fun saveMetadata(metadata: VaultMetadata) {}
        }
        
        val manager = BackupManagerImpl(
            vaultRepository, categoryRepo, metadataRepo, cryptoManager, 
            backupRepo, BackupSerializerImpl(), BackupValidatorImpl()
        )
        
        val backupRes = manager.createBackup("dummy")
        println("Backup created: \$backupRes")
        
        val valRes = manager.validateBackup("dummy")
        println("Backup validated: \$valRes")
        
        try {
            val res = manager.restoreBackup("dummy", masterPassword)
            println("Restore finished: \$res")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
