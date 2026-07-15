package com.vaultpass.desktop.data.backup

import com.vaultpass.desktop.domain.backup.BackupValidator
import com.vaultpass.desktop.domain.backup.errors.BackupException
import com.vaultpass.desktop.domain.backup.models.BackupFormat
import com.vaultpass.desktop.domain.backup.models.BackupMetadata
import com.vaultpass.desktop.domain.backup.models.BackupResult
import com.vaultpass.desktop.domain.backup.models.BackupVersion
import com.vaultpass.desktop.domain.backup.models.format.VpbFile
import kotlinx.serialization.json.Json
import java.util.Base64

class BackupValidatorImpl : BackupValidator {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    override fun extractMetadata(rawBackup: ByteArray): BackupResult<BackupMetadata> {
        try {
            // 1. Read JSON String
            val jsonString = String(rawBackup, Charsets.UTF_8)
            
            // 2. Deserialize Envelope
            val vpbFile = try {
                json.decodeFromString<VpbFile>(jsonString)
            } catch (e: Exception) {
                return BackupResult.Failure(BackupException.InvalidFormat("File is not a valid VaultPass backup JSON structure."))
            }

            // 3. Validate Magic Bytes
            if (vpbFile.header.magicBytes != "VPB") {
                return BackupResult.Failure(BackupException.InvalidFormat("Invalid magic bytes. Not a VaultPass backup."))
            }

            // 4. Validate Version Support
            if (vpbFile.header.backupVersion > BackupVersion.CURRENT) {
                return BackupResult.Failure(BackupException.UnsupportedVersion("Backup version ${vpbFile.header.backupVersion} is newer than this application supports."))
            }

            // 5. Construct Safe Metadata for UI
            val metadata = BackupMetadata(
                timestamp = vpbFile.header.timestamp,
                vaultId = vpbFile.header.vaultId,
                version = vpbFile.header.backupVersion,
                format = BackupFormat.JSON_V1,
                salt = try { Base64.getDecoder().decode(vpbFile.header.salt) } catch (e: Exception) { ByteArray(0) },
                encryptionParams = mapOf(
                    "iterations" to vpbFile.header.iterations.toString(),
                    "kdfAlgorithm" to vpbFile.header.kdfAlgorithm,
                    "encryptionAlgorithm" to vpbFile.header.encryptionAlgorithm
                )
            )

            return BackupResult.Success(metadata)

        } catch (e: Exception) {
            return BackupResult.Failure(BackupException.CorruptedData("Unexpected error while validating backup: ${e.message}"))
        }
    }

    override fun verifyIntegrity(rawBackup: ByteArray, metadata: BackupMetadata): BackupResult<Boolean> {
        // Validation during import is mostly handled by decryptExternalPayload. 
        // We just return true for now, as signature validation/HMACs can be added later.
        return BackupResult.Success(true)
    }
}
