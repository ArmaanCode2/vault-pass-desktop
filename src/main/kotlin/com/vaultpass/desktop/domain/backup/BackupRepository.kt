package com.vaultpass.desktop.domain.backup

import com.vaultpass.desktop.domain.backup.models.BackupResult

/**
 * Abstract storage interface for backups.
 * Defers file I/O operations until Phase 7 implementation details are needed.
 */
interface BackupRepository {
    /**
     * Saves raw encrypted backup data to a persistent store.
     */
    suspend fun saveBackup(data: ByteArray, destinationIdentifier: String): BackupResult<Unit>

    /**
     * Loads raw encrypted backup data from a persistent store.
     */
    suspend fun loadBackup(sourceIdentifier: String): BackupResult<ByteArray>
}
