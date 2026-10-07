package com.vaultpass.desktop.domain

import com.vaultpass.desktop.domain.models.PagedVaultResult
import com.vaultpass.desktop.domain.models.SyncTombstone
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.domain.models.VaultEntryHistory
import com.vaultpass.desktop.domain.models.VaultQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Defines the strict contract for interacting with Vault Entries.
 * ViewModels consume this interface without knowing whether the data is backed by
 * a local SQLite database or a LAN Sync stream.
 */
interface VaultRepository {
    // 1. Reactive Read Operations (Search, Sort, Paginate)
    fun observeAllEntries(isDeleted: Boolean = false): Flow<RepositoryResult<List<VaultEntry>>>
    suspend fun getAllEntries(isDeleted: Boolean = false): RepositoryResult<List<VaultEntry>> =
        observeAllEntries(isDeleted).first()
    fun observeQuery(query: VaultQuery): Flow<RepositoryResult<PagedVaultResult>>
    
    // 2. Single Reads
    suspend fun getEntryById(id: String): RepositoryResult<VaultEntry?>
    suspend fun getEntryHistory(id: String): RepositoryResult<List<VaultEntryHistory>>
    
    // 3. Core Write Operations (CRUD)
    suspend fun createEntry(entry: VaultEntry): RepositoryResult<Unit>
    suspend fun updateEntry(entry: VaultEntry): RepositoryResult<Unit>
    
    // 4. Lifecycle Operations (Delete, Archive, Restore)
    suspend fun softDeleteEntry(id: String): RepositoryResult<Unit>
    suspend fun hardDeleteEntry(id: String): RepositoryResult<Unit>
    suspend fun archiveEntry(id: String): RepositoryResult<Unit>
    suspend fun restoreEntry(id: String): RepositoryResult<Unit>
    suspend fun emptyRecycleBin(): RepositoryResult<Unit>
    suspend fun purgeRecycleBinOlderThan(thresholdMillis: Long): RepositoryResult<Int>
    suspend fun restoreEntries(ids: List<String>): RepositoryResult<Unit>
    suspend fun hardDeleteEntries(ids: List<String>): RepositoryResult<Unit>

    // 5. Sync bookkeeping. Every permanent delete leaves a tombstone; creating an entry with that id removes it.
    suspend fun getTombstones(): RepositoryResult<List<SyncTombstone>>
    /** Gives the entry [oldId] the syncId (id) [newId], keeping everything else. */
    suspend fun changeEntryId(oldId: String, newId: String): RepositoryResult<Unit>
    
    // 6. Atomic Bulk Operations (Transactions)
    suspend fun <T> transaction(block: suspend VaultRepository.() -> T): RepositoryResult<T>
    
    // 7. Security
    fun clearCache()
}
