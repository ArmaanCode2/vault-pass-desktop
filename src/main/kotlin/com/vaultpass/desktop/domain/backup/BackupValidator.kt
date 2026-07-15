package com.vaultpass.desktop.domain.backup

import com.vaultpass.desktop.domain.backup.models.BackupMetadata
import com.vaultpass.desktop.domain.backup.models.BackupResult

/**
 * Verifies backup integrity and metadata authenticity before processing.
 */
interface BackupValidator {
    /**
     * Extracts metadata without fully decrypting the payload.
     */
    fun extractMetadata(rawBackup: ByteArray): BackupResult<BackupMetadata>

    /**
     * Verifies the cryptographic signature or checksum of the backup.
     */
    fun verifyIntegrity(rawBackup: ByteArray, metadata: BackupMetadata): BackupResult<Boolean>
}
