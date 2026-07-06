package com.vaultpass.desktop.domain

import com.vaultpass.desktop.domain.models.VaultEntry
import kotlinx.coroutines.flow.Flow

/**
 * Defines the strict contract for interacting with Vault Entries.
 * ViewModels consume this interface without knowing whether the data is backed by
 * a local SQLite database or a LAN Sync stream.
 */
interface VaultRepository {
    /**
     * Retrieves all vault entries as a reactive stream.
     */
    fun getAllEntries(): Flow<RepositoryResult<List<VaultEntry>>>
    
    /**
     * Retrieves a single entry by its ID.
     */
    suspend fun getEntryById(id: String): RepositoryResult<VaultEntry?>
    
    /**
     * Inserts a new entry into the data source.
     */
    suspend fun insertEntry(entry: VaultEntry): RepositoryResult<Unit>
    
    /**
     * Updates an existing entry in the data source.
     */
    suspend fun updateEntry(entry: VaultEntry): RepositoryResult<Unit>
    
    /**
     * Deletes an entry by its ID.
     */
    suspend fun deleteEntry(id: String): RepositoryResult<Unit>
}
