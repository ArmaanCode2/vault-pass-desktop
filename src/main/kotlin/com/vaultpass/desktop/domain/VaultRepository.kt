package com.vaultpass.desktop.domain

import com.vaultpass.desktop.domain.models.PagedVaultResult
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.domain.models.VaultEntryHistory
import com.vaultpass.desktop.domain.models.VaultQuery
import kotlinx.coroutines.flow.Flow

/**
 * Defines the strict contract for interacting with Vault Entries.
 * ViewModels consume this interface without knowing whether the data is backed by
 * a local SQLite database or a LAN Sync stream.
 */
interface VaultRepository {
    // 1. Reactive Read Operations (Search, Sort, Paginate)
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
    
    // 5. Atomic Bulk Operations (Transactions)
    suspend fun <T> transaction(block: suspend VaultRepository.() -> T): RepositoryResult<T>
}
