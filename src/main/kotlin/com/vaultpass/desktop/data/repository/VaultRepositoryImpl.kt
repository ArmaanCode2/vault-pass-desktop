package com.vaultpass.desktop.data.repository

import com.vaultpass.desktop.data.models.VaultEntity
import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.VaultRepository
import com.vaultpass.desktop.domain.db.VaultLocalDataSource
import com.vaultpass.desktop.domain.models.PagedVaultResult
import com.vaultpass.desktop.domain.models.SortDirection
import com.vaultpass.desktop.domain.models.SortField
import com.vaultpass.desktop.domain.models.SyncTombstone
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.domain.models.VaultEntryHistory
import com.vaultpass.desktop.domain.models.VaultQuery
import com.vaultpass.desktop.domain.crypto.CryptoManager
import com.vaultpass.desktop.domain.errors.VaultError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import com.vaultpass.desktop.domain.db.DatabaseClosedException
import com.vaultpass.desktop.domain.errors.AuthError
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

    private val decryptedCache = java.util.concurrent.ConcurrentHashMap<String, VaultEntry>()

    override fun clearCache() {
        decryptedCache.clear()
    }

    /** Stands in for a row that can't be decrypted; flagged so it is never edited, exported or synced. */
    private fun corruptedPlaceholder(entity: VaultEntity) = VaultEntry(
        id = entity.id,
        title = "Corrupted Entry",
        username = "",
        secret = "",
        url = "",
        notes = "This entry is mathematically corrupted and cannot be decrypted.",
        isFavorite = false,
        isDeleted = entity.isDeleted,
        deletedAt = entity.deletedAt,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        isCorrupted = true
    )


    override fun observeAllEntries(isDeleted: Boolean): Flow<RepositoryResult<List<VaultEntry>>> {
        return localDataSource.observeAll()
            .map { entities ->
                try {
                    val filtered = entities.filter { it.isDeleted == isDeleted }
                    val decrypted = filtered.map { entity ->
                        val cached = decryptedCache[entity.id]
                        if (cached != null && cached.updatedAt == entity.updatedAt) {
                            cached
                        } else {
                            try {
                                val dec = com.vaultpass.desktop.data.repository.VaultEntryMapper.toDomain(entity, cryptoManager)
                                decryptedCache[entity.id] = dec
                                dec
                            } catch (e: Exception) {
                                corruptedPlaceholder(entity)
                            }
                        }
                    }
                    RepositoryResult.Success(decrypted)
                } catch (e: Exception) {
                    println("[TRACE] observeAllEntries DECRYPT/GSON ERROR: ${e.message}")
                    RepositoryResult.Error(DatabaseError.CorruptionDetected(e.message ?: "Unknown error"))
                }
            }
    }

    override suspend fun emptyRecycleBin(): RepositoryResult<Unit> {
        return try {
            localDataSource.emptyRecycleBin()
            decryptedCache.values.filter { it.isDeleted }.forEach { decryptedCache.remove(it.id) }
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            errorFor(e) { DatabaseError.CorruptionDetected(e.message ?: "Failed to empty recycle bin") }
        }
    }

    override fun observeQuery(query: VaultQuery): Flow<RepositoryResult<PagedVaultResult>> {
        return localDataSource.observeAll()
            .map { entities ->
                try {
                    // Decrypt all entities
                    val decryptedEntries = entities.map { entity ->
                        val cached = decryptedCache[entity.id]
                        if (cached != null && cached.updatedAt == entity.updatedAt) {
                            cached
                        } else {
                            try {
                                val dec = VaultEntryMapper.toDomain(entity, cryptoManager)
                                decryptedCache[entity.id] = dec
                                dec
                            } catch (e: Exception) {
                                corruptedPlaceholder(entity)
                            }
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
                        SortField.FAVORITES_FIRST -> {
                            val favs = filtered.filter { it.isFavorite }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                            val nonFavs = filtered.filter { !it.isFavorite }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                            if (query.sortDirection == SortDirection.ASCENDING) favs + nonFavs else nonFavs + favs
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
            errorFor(e) { DatabaseError.NotFound(e.message ?: "Entry not found.") }
        }
    }

    override suspend fun getEntryHistory(id: String): RepositoryResult<List<VaultEntryHistory>> {
        return try {
            val entryResult = getEntryById(id)
            if (entryResult is RepositoryResult.Success && entryResult.data != null) {
                RepositoryResult.Success(entryResult.data.history)
            } else {
                RepositoryResult.Success(emptyList())
            }
        } catch (e: Exception) {
            errorFor(e) { DatabaseError.CorruptionDetected(e.message ?: "Failed to get history") }
        }
    }

    // createEntry and updateEntry encrypt inside the write's transaction: a lock closes the database
    // before it wipes the key, so anything encrypted while the key is being wiped is rolled back.

    override suspend fun createEntry(entry: VaultEntry): RepositoryResult<Unit> {
        return try {
            localDataSource.transaction {
                localDataSource.insert(VaultEntryMapper.toEntity(entry, cryptoManager))
            }
            decryptedCache.remove(entry.id)
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            errorFor(e) { DatabaseError.CorruptionDetected(e.message ?: "Failed to insert.") }
        }
    }

    override suspend fun updateEntry(entry: VaultEntry): RepositoryResult<Unit> {
        return try {
            localDataSource.transaction {
                localDataSource.update(VaultEntryMapper.toEntity(entry, cryptoManager))
            }
            // The cache is keyed on updatedAt, which an update may keep (e.g. a sync merge).
            decryptedCache.remove(entry.id)
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            errorFor(e) { DatabaseError.CorruptionDetected(e.message ?: "Failed to update.") }
        }
    }

    override suspend fun softDeleteEntry(id: String): RepositoryResult<Unit> {
        return try {
            val getResult = getEntryById(id)
            if (getResult is RepositoryResult.Success && getResult.data != null) {
                val updated = getResult.data.copy(
                    isDeleted = true,
                    deletedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                updateEntry(updated)
            } else if (getResult is RepositoryResult.Error) {
                getResult
            } else {
                RepositoryResult.Error(DatabaseError.NotFound("Entry not found"))
            }
        } catch (e: Exception) {
            errorFor(e) { DatabaseError.CorruptionDetected(e.message ?: "Failed to delete") }
        }
    }

    override suspend fun hardDeleteEntry(id: String): RepositoryResult<Unit> {
        return try {
            localDataSource.delete(id)
            decryptedCache.remove(id)
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            errorFor(e) { DatabaseError.CorruptionDetected(e.message ?: "Failed to delete.") }
        }
    }

    override suspend fun archiveEntry(id: String): RepositoryResult<Unit> {
        // Deferred implementation
        return RepositoryResult.Success(Unit)
    }

    override suspend fun restoreEntry(id: String): RepositoryResult<Unit> {
        return try {
            val getResult = getEntryById(id)
            if (getResult is RepositoryResult.Success && getResult.data != null) {
                val updated = getResult.data.copy(
                    isDeleted = false,
                    deletedAt = null,
                    updatedAt = System.currentTimeMillis()
                )
                updateEntry(updated)
            } else if (getResult is RepositoryResult.Error) {
                getResult
            } else {
                RepositoryResult.Error(DatabaseError.NotFound("Entry not found"))
            }
        } catch (e: Exception) {
            errorFor(e) { DatabaseError.CorruptionDetected(e.message ?: "Failed to restore") }
        }
    }

    override suspend fun purgeRecycleBinOlderThan(thresholdMillis: Long): RepositoryResult<Int> {
        return try {
            val count = localDataSource.deleteRecycleBinOlderThan(thresholdMillis)
            decryptedCache.values.filter { it.isDeleted && (it.deletedAt ?: 0L) < thresholdMillis }.forEach {
                decryptedCache.remove(it.id)
            }
            RepositoryResult.Success(count)
        } catch (e: Exception) {
            errorFor(e) { DatabaseError.CorruptionDetected(e.message ?: "Failed to purge recycle bin.") }
        }
    }

    override suspend fun restoreEntries(ids: List<String>): RepositoryResult<Unit> {
        return try {
            localDataSource.batchRestore(ids)
            ids.forEach { decryptedCache.remove(it) }
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            errorFor(e) { DatabaseError.CorruptionDetected(e.message ?: "Failed to restore entries.") }
        }
    }

    override suspend fun hardDeleteEntries(ids: List<String>): RepositoryResult<Unit> {
        return try {
            localDataSource.batchDelete(ids)
            ids.forEach { decryptedCache.remove(it) }
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            errorFor(e) { DatabaseError.CorruptionDetected(e.message ?: "Failed to delete entries.") }
        }
    }

    override suspend fun getTombstones(): RepositoryResult<List<SyncTombstone>> {
        return try {
            RepositoryResult.Success(localDataSource.getTombstones())
        } catch (e: Exception) {
            errorFor(e) { DatabaseError.CorruptionDetected(e.message ?: "Failed to read deleted entries.") }
        }
    }

    override suspend fun changeEntryId(oldId: String, newId: String): RepositoryResult<Unit> {
        return try {
            localDataSource.changeId(oldId, newId)
            decryptedCache.remove(oldId)
            decryptedCache.remove(newId)
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            errorFor(e) { DatabaseError.CorruptionDetected(e.message ?: "Failed to rename entry.") }
        }
    }

    override suspend fun <T> transaction(block: suspend VaultRepository.() -> T): RepositoryResult<T> {
        return try {
            val result = localDataSource.transaction {
                block()
            }
            RepositoryResult.Success(result)
        } catch (e: Exception) {
            // Rolled back. Locked before or during the transaction: callers can tell why.
            decryptedCache.clear()
            errorFor(e) { DatabaseError.CorruptionDetected(e.message ?: "Transaction failed") }
        }
    }

    /**
     * The result for a failed operation: [AuthError.VaultLocked] when the database closed (the
     * vault locked), else [otherwise]. A cancellation is never a result: it is thrown on.
     */
    private fun errorFor(e: Exception, otherwise: () -> VaultError): RepositoryResult.Error {
        if (e is CancellationException) throw e
        return RepositoryResult.Error(if (e is DatabaseClosedException) AuthError.VaultLocked() else otherwise())
    }
}
