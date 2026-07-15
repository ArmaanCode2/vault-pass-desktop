package com.vaultpass.desktop.data.backup

import com.vaultpass.desktop.domain.VaultRepository
import com.vaultpass.desktop.domain.CategoryRepository
import com.vaultpass.desktop.domain.metadata.MetadataRepository
import com.vaultpass.desktop.domain.backup.BackupManager
import com.vaultpass.desktop.domain.backup.BackupRepository
import com.vaultpass.desktop.domain.backup.BackupSerializer
import com.vaultpass.desktop.domain.backup.BackupValidator
import com.vaultpass.desktop.domain.backup.errors.BackupException
import com.vaultpass.desktop.domain.backup.models.BackupFormat
import com.vaultpass.desktop.domain.backup.models.BackupMetadata
import com.vaultpass.desktop.domain.backup.models.BackupResult
import com.vaultpass.desktop.domain.backup.models.BackupVersion
import com.vaultpass.desktop.domain.backup.models.format.BackupHeader
import com.vaultpass.desktop.domain.backup.models.format.BackupPayload
import com.vaultpass.desktop.domain.backup.models.format.CategoryBackup
import com.vaultpass.desktop.domain.backup.models.format.TagBackup
import com.vaultpass.desktop.domain.backup.models.format.VaultEntryBackup
import com.vaultpass.desktop.domain.backup.models.format.VaultMetadataBackup
import com.vaultpass.desktop.domain.backup.models.format.VpbFile
import com.vaultpass.desktop.domain.crypto.CryptoManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import com.vaultpass.desktop.domain.models.KdfConfig
import com.vaultpass.desktop.domain.models.EncryptionConfig
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.domain.models.VaultCategory
import com.vaultpass.desktop.domain.security.SecureCharArray


class BackupManagerImpl(
    private val vaultRepository: VaultRepository,
    private val categoryRepository: CategoryRepository,
    private val metadataRepository: MetadataRepository,
    private val cryptoManager: CryptoManager,
    private val backupRepository: BackupRepository,
    private val backupSerializer: BackupSerializer,
    private val backupValidator: BackupValidator
) : BackupManager {

    private var pendingBackupBytes: ByteArray? = null


    override suspend fun createBackup(destinationIdentifier: String): BackupResult<BackupMetadata> {
        try {
            // 1. Gather Vault Metadata
            val vaultMetadata = metadataRepository.getMetadata()
                ?: return BackupResult.Failure(BackupException.CorruptedData("Vault metadata is missing or corrupted"))

            val kdfConfig = vaultMetadata.kdfConfig
                ?: return BackupResult.Failure(BackupException.CorruptedData("Vault is missing KDF configuration"))
            val encryptionConfig = vaultMetadata.encryptionConfig
                ?: return BackupResult.Failure(BackupException.CorruptedData("Vault is missing Encryption configuration"))

            // 2. Gather Vault Entries
            val rawEntriesResult = vaultRepository.observeAllEntries().first()
            val entriesResult = if (rawEntriesResult is com.vaultpass.desktop.domain.RepositoryResult.Success) rawEntriesResult.data else emptyList()
            val entriesBackup = entriesResult.map {
                VaultEntryBackup(
                    id = it.id,
                    title = it.title,
                    username = it.username,
                    secret = it.secret,
                    url = it.url,
                    notes = it.notes,
                    isFavorite = it.isFavorite,
                    categoryId = it.category,
                    tagIds = it.tags,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt
                )
            }

            // 3. Gather Categories & Tags (Assuming simple retrieval, if TagRepository exists we'd use it, for now we leave tags empty or fetch if possible)
            // Note: TagRepository doesn't exist explicitly in our findings so we'll leave it empty for now, as tags are Phase 6.3
            val rawCategoriesResult = categoryRepository.getAllCategories().first()
            val categoriesResult = if (rawCategoriesResult is com.vaultpass.desktop.domain.RepositoryResult.Success) rawCategoriesResult.data else emptyList()
            val categoriesBackup = categoriesResult.map {
                CategoryBackup(id = it.id, name = it.name)
            }

            val metadataBackup = VaultMetadataBackup(
                name = "VaultPass Backup", // Placeholder or fetch actual if exists
                lastModified = vaultMetadata.lastOpenedAt ?: System.currentTimeMillis(),
                securityScore = null
            )

            val payload = BackupPayload(
                vaultMetadata = metadataBackup,
                entries = entriesBackup,
                categories = categoriesBackup,
                tags = emptyList() // Tags not yet fully implemented in Domain
            )

            // 4. Serialize and Compress
            val serializedBytesResult = backupSerializer.serialize(payload, BackupFormat.JSON_V1)
            val compressedBytes = when (serializedBytesResult) {
                is BackupResult.Success -> serializedBytesResult.data
                is BackupResult.Failure -> return BackupResult.Failure(serializedBytesResult.error)
            }

            // 5. Encrypt with current active DEK
            val encryptedBytes = cryptoManager.encryptData(compressedBytes)
            val encryptedPayloadBase64 = Base64.getEncoder().encodeToString(encryptedBytes)

            // 6. Build Header
            val header = BackupHeader(
                backupVersion = BackupVersion.CURRENT,
                applicationVersion = vaultMetadata.createdWithAppVersion,
                timestamp = System.currentTimeMillis(),
                vaultId = "vault_primary", // Assuming single vault for now
                vaultSchemaVersion = vaultMetadata.vaultVersion,
                encryptionAlgorithm = encryptionConfig.algorithm,
                kdfAlgorithm = kdfConfig.algorithm,
                iterations = kdfConfig.iterations,
                salt = kdfConfig.saltBase64,
                masterHashBase64 = kdfConfig.masterHashBase64,
                wrappedDekBase64 = encryptionConfig.wrappedDekBase64,
                iv = encryptionConfig.ivBase64
            )

            // 7. Create VpbFile Envelope
            val vpbFile = VpbFile(
                header = header,
                encryptedPayloadBase64 = encryptedPayloadBase64
            )

            // 8. Serialize Final File
            val json = Json { encodeDefaults = true }
            val fileBytes = json.encodeToString(vpbFile).toByteArray(Charsets.UTF_8)

            // 9. Save to Disk
            val saveResult = backupRepository.saveBackup(fileBytes, destinationIdentifier)
            if (saveResult is BackupResult.Failure) {
                return BackupResult.Failure(saveResult.error)
            }

            // Return success with generated metadata model for UI usage
            val resultMetadata = BackupMetadata(
                timestamp = header.timestamp,
                vaultId = header.vaultId,
                version = header.backupVersion,
                format = BackupFormat.JSON_V1,
                salt = Base64.getDecoder().decode(header.salt),
                encryptionParams = mapOf(
                    "iterations" to header.iterations.toString(),
                    "kdfAlgorithm" to header.kdfAlgorithm,
                    "encryptionAlgorithm" to header.encryptionAlgorithm
                )
            )

            return BackupResult.Success(resultMetadata)

        } catch (e: Exception) {
            return BackupResult.Failure(BackupException.StorageError("Failed to create backup: ${e.message}", e))
        }
    }

    override suspend fun restoreBackup(sourceIdentifier: String, masterPassword: CharArray): BackupResult<Unit> {
        try {
            // 1. Get Pending File Bytes
            val fileBytes = pendingBackupBytes ?: return BackupResult.Failure(BackupException.StorageError("No pending backup found to restore."))
            pendingBackupBytes = null
            
            // 2. Extract Envelope
            val json = Json { ignoreUnknownKeys = true }
            val vpbFile = try {
                json.decodeFromString<VpbFile>(String(fileBytes, Charsets.UTF_8))
            } catch (e: Exception) {
                return BackupResult.Failure(BackupException.InvalidFormat("Corrupted VaultPass backup file."))
            }

            // 3. Reconstruct KDF and Encryption Configs from Header
            val header = vpbFile.header
            val kdfConfig = KdfConfig(
                algorithm = header.kdfAlgorithm,
                iterations = header.iterations,
                saltBase64 = header.salt,
                masterHashBase64 = header.masterHashBase64
            )
            
            val encryptionConfig = EncryptionConfig(
                algorithm = header.encryptionAlgorithm,
                wrappedDekBase64 = header.wrappedDekBase64,
                ivBase64 = header.iv
            )

            // 4. Decrypt Statelessly
            val securePassword = SecureCharArray(masterPassword.clone())
            val encryptedPayload = Base64.getDecoder().decode(vpbFile.encryptedPayloadBase64)
            
            val decryptionResult = cryptoManager.decryptExternalPayload(
                ciphertext = encryptedPayload,
                password = securePassword,
                kdfConfig = kdfConfig,
                encryptionConfig = encryptionConfig
            )
            
            securePassword.wipe()
            
            if (decryptionResult is com.vaultpass.desktop.domain.crypto.CryptoResult.Failure) {
                return BackupResult.Failure(BackupException.DecryptionFailed("Incorrect master password or corrupted ciphertext."))
            }
            
            val compressedPayloadBytes = (decryptionResult as com.vaultpass.desktop.domain.crypto.CryptoResult.Success).data
            
            // 5. Deserialize Payload
            val deserializeResult = backupSerializer.deserialize(compressedPayloadBytes, BackupFormat.JSON_V1)
            if (deserializeResult is BackupResult.Failure) {
                return BackupResult.Failure(deserializeResult.error)
            }
            
            val payload = (deserializeResult as BackupResult.Success).data
            
            // 6. Restore to Repositories
            // Note: Currently performing blind inserts (skipping conflict resolution)
            
            // Categories
            payload.categories.forEach { catBackup ->
                val category = VaultCategory(
                    id = catBackup.id, 
                    name = catBackup.name, 
                    createdAt = System.currentTimeMillis(), 
                    updatedAt = System.currentTimeMillis()
                )
                categoryRepository.insertCategory(category)
            }
            
            // Entries
            val transactionResult = vaultRepository.transaction {
                payload.entries.forEach { entryBackup ->
                    val entry = VaultEntry(
                        id = entryBackup.id,
                        title = entryBackup.title,
                        username = entryBackup.username,
                        secret = entryBackup.secret,
                        url = entryBackup.url ?: "",
                        notes = entryBackup.notes ?: "",
                        category = entryBackup.categoryId,
                        tags = entryBackup.tagIds,
                        history = emptyList(),
                        isFavorite = entryBackup.isFavorite,
                        isDeleted = false,
                        createdAt = entryBackup.createdAt,
                        updatedAt = entryBackup.updatedAt
                    )
                    
                    val insertResult = createEntry(entry)
                    if (insertResult is com.vaultpass.desktop.domain.RepositoryResult.Error) {
                        throw Exception("Failed to insert entry ${entry.title}: ${insertResult.error}")
                    }
                }
            }
            
            if (transactionResult is com.vaultpass.desktop.domain.RepositoryResult.Error) {
                return BackupResult.Failure(BackupException.StorageError("Restore transaction failed: ${transactionResult.error.message}"))
            }
            
            val currentMetadata = metadataRepository.getMetadata()
            
            // Re-initialize Metadata to match imported Vault
            val newVaultMetadata = com.vaultpass.desktop.domain.models.VaultMetadata(
                initialized = true,
                vaultVersion = header.vaultSchemaVersion,
                metadataVersion = 1,
                kdfVersion = 1,
                encryptionVersion = 1,
                settingsVersion = 1,
                migrationVersion = 1,
                createdAt = payload.vaultMetadata.lastModified, // close approx
                lastOpenedAt = System.currentTimeMillis(),
                createdWithAppVersion = header.applicationVersion,
                lastOpenedAppVersion = header.applicationVersion,
                kdfConfig = currentMetadata?.kdfConfig ?: kdfConfig,
                encryptionConfig = currentMetadata?.encryptionConfig ?: encryptionConfig
            )
            metadataRepository.saveMetadata(newVaultMetadata)
            
            return BackupResult.Success(Unit)
        } catch (e: Exception) {
            return BackupResult.Failure(BackupException.StorageError("Failed to restore backup: ${e.message}", e))
        }
    }

    override suspend fun validateBackup(sourceIdentifier: String): BackupResult<BackupMetadata> {
        val loadResult = backupRepository.loadBackup(sourceIdentifier)
        if (loadResult is BackupResult.Failure) {
            return BackupResult.Failure(loadResult.error)
        }
        val fileBytes = (loadResult as BackupResult.Success).data
        val metadataResult = backupValidator.extractMetadata(fileBytes)
        if (metadataResult is BackupResult.Success) {
            pendingBackupBytes = fileBytes
        }
        return metadataResult
    }
    
    override fun clearPendingBackup() {
        pendingBackupBytes = null
    }
}
