package com.vaultpass.desktop.domain.backup

import com.vaultpass.desktop.domain.backup.models.BackupResult
import com.vaultpass.desktop.domain.backup.models.BackupMetadata

/**
 * High-level coordinator for backup operations.
 * The UI should exclusively interact with this interface for backups.
 */
interface BackupManager {
    /**
     * Creates a full encrypted backup of the current vault and saves it.
     */
    suspend fun createBackup(destinationIdentifier: String): BackupResult<BackupMetadata>

    /**
     * Imports an encrypted backup into the current vault.
     */
    suspend fun restoreBackup(sourceIdentifier: String, masterPassword: CharArray): BackupResult<Unit>
    fun clearPendingBackup()

    /**
     * Validates a backup file and returns its metadata if valid.
     */
    suspend fun validateBackup(sourceIdentifier: String): BackupResult<BackupMetadata>
}
