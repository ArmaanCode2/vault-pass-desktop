package com.vaultpass.desktop.data.repository

import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.VaultRepository
import com.vaultpass.desktop.domain.db.VaultLocalDataSource
import com.vaultpass.desktop.domain.models.PagedVaultResult
import com.vaultpass.desktop.domain.models.SortDirection
import com.vaultpass.desktop.domain.models.SortField
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.domain.models.VaultEntryHistory
import com.vaultpass.desktop.domain.models.VaultQuery
import com.vaultpass.desktop.domain.crypto.CryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import com.vaultpass.desktop.domain.errors.DatabaseError
import com.vaultpass.desktop.domain.errors.CryptoError

/**
 * Concrete implementation of the VaultRepository.
 * Manages the in-memory decrypted cache, orchestrates CRUD with the local data source,
 * and handles AES-GCM encryption/decryption via CryptoManager.
 */
class VaultRepositoryImpl(
    private val localDataSource: VaultLocalDataSource,
    private val cryptoManager: CryptoManager
) : VaultRepository {

    override fun observeQuery(query: VaultQuery): Flow<RepositoryResult<PagedVaultResult>> {
        return localDataSource.observeAll()
            .map { entities ->
                try {
                    // Decrypt all entities
                    val decryptedEntries = entities.map { entity ->
                        try {
                            VaultEntryMapper.toDomain(entity, cryptoManager)
                        } catch (e: Exception) {
                            VaultEntry(
                                id = entity.id,
                                title = "Corrupted Entry",
                                username = "",
                                secret = "",
                                url = "",
                                notes = "This entry is mathematically corrupted and cannot be decrypted.",
                                isFavorite = false,
                                createdAt = entity.createdAt,
                                updatedAt = entity.updatedAt
                            )
                        }
                    }
                    
                    // Filter (Includes Type Filter and Free-Text Search)
                    val queryText = query.searchQuery.trim()
                    val filtered = decryptedEntries.filter { entry ->
                        // 1. Type Filter
                        if (query.filterType != null && entry.javaClass.simpleName != query.filterType.name) {
                            return@filter false
                        }
                        
                        // 2. Search Filter
                        if (queryText.isNotBlank()) {
                            val matchesSearch = entry.title.contains(queryText, ignoreCase = true) ||
                                                entry.username.contains(queryText, ignoreCase = true) ||
                                                entry.url.contains(queryText, ignoreCase = true) ||
                                                entry.notes.contains(queryText, ignoreCase = true)
                            if (!matchesSearch) return@filter false
                        }
                        
                        true
                    }
                    
                    // Sort (Optimized to prevent excessive String allocations)
                    val sorted = when (query.sortField) {
                        SortField.TITLE -> if (query.sortDirection == SortDirection.ASCENDING) {
                            filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                        } else {
                            filtered.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
                        }
                        SortField.CREATED_AT -> if (query.sortDirection == SortDirection.ASCENDING) {
                            filtered.sortedBy { it.createdAt }
                        } else {
                            filtered.sortedByDescending { it.createdAt }
                        }
                        SortField.UPDATED_AT -> if (query.sortDirection == SortDirection.ASCENDING) {
                            filtered.sortedBy { it.updatedAt }
                        } else {
                            filtered.sortedByDescending { it.updatedAt }
                        }
                    }
                    
                    // Paginate
                    val totalItems = sorted.size
                    val totalPages = if (totalItems == 0) 1 else (totalItems + query.pageSize - 1) / query.pageSize
                    val startIndex = (query.page - 1) * query.pageSize
                    val endIndex = minOf(startIndex + query.pageSize, totalItems)
                    
                    val pagedItems = if (startIndex < totalItems) {
                        sorted.subList(startIndex, endIndex)
                    } else {
                        emptyList()
                    }
                    
                    RepositoryResult.Success(
                        PagedVaultResult(
                            items = pagedItems,
                            totalItems = totalItems,
                            totalPages = totalPages,
                            currentPage = query.page
                        )
                    )
                } catch (e: Exception) {
                    RepositoryResult.Error(CryptoError.DecryptionFailed(e.message ?: "Failed to decrypt entries."))
                }
            }
            .catch { e ->
                emit(RepositoryResult.Error(DatabaseError.CorruptionDetected(e.message ?: "Database error.")))
            }
    }

    override suspend fun getEntryById(id: String): RepositoryResult<VaultEntry?> {
        return try {
            val entity = localDataSource.getById(id)
            if (entity != null) {
                RepositoryResult.Success(VaultEntryMapper.toDomain(entity, cryptoManager))
            } else {
                RepositoryResult.Success(null)
            }
        } catch (e: Exception) {
            RepositoryResult.Error(DatabaseError.NotFound(e.message ?: "Entry not found."))
        }
    }

    override suspend fun getEntryHistory(id: String): RepositoryResult<List<VaultEntryHistory>> {
        // Deferred implementation for future milestone
        return RepositoryResult.Success(emptyList())
    }

    override suspend fun createEntry(entry: VaultEntry): RepositoryResult<Unit> {
        return try {
            val entity = VaultEntryMapper.toEntity(entry, cryptoManager)
            localDataSource.insert(entity)
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            RepositoryResult.Error(DatabaseError.CorruptionDetected(e.message ?: "Failed to insert."))
        }
    }

    override suspend fun updateEntry(entry: VaultEntry): RepositoryResult<Unit> {
        return try {
            val entity = VaultEntryMapper.toEntity(entry, cryptoManager)
            localDataSource.update(entity)
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            RepositoryResult.Error(DatabaseError.CorruptionDetected(e.message ?: "Failed to update."))
        }
    }

    override suspend fun softDeleteEntry(id: String): RepositoryResult<Unit> {
        // Deferred for recycle bin support
        return RepositoryResult.Success(Unit)
    }

    override suspend fun hardDeleteEntry(id: String): RepositoryResult<Unit> {
        return try {
            localDataSource.delete(id)
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            RepositoryResult.Error(DatabaseError.CorruptionDetected(e.message ?: "Failed to delete."))
        }
    }

    override suspend fun archiveEntry(id: String): RepositoryResult<Unit> {
        // Deferred implementation
        return RepositoryResult.Success(Unit)
    }

    override suspend fun restoreEntry(id: String): RepositoryResult<Unit> {
        // Deferred implementation
        return RepositoryResult.Success(Unit)
    }

    override suspend fun <T> transaction(block: suspend VaultRepository.() -> T): RepositoryResult<T> {
        return try {
            RepositoryResult.Success(block())
        } catch (e: Exception) {
            RepositoryResult.Error(DatabaseError.CorruptionDetected(e.message ?: "Transaction failed"))
        }
    }
}
