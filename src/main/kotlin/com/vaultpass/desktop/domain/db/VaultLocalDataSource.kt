package com.vaultpass.desktop.domain.db

import com.vaultpass.desktop.data.models.VaultEntity
import com.vaultpass.desktop.domain.models.SyncTombstone
import kotlinx.coroutines.flow.Flow

/**
 * Defines raw CRUD operations for Vault Entries at the local database level.
 * Abstracted to decouple the Repository layer from the actual SQL engine (Room/SQLDelight).
 *
 * Every permanent delete leaves a [SyncTombstone] (deletedAt = the row's deleted_at, else now),
 * and inserting an entry removes the tombstone with its id.
 */
interface VaultLocalDataSource {
    fun observeAll(): Flow<List<VaultEntity>>
    suspend fun getById(id: String): VaultEntity?
    suspend fun insert(entity: VaultEntity)
    suspend fun update(entity: VaultEntity)
    suspend fun delete(id: String)
    suspend fun emptyRecycleBin()
    suspend fun deleteRecycleBinOlderThan(thresholdMillis: Long): Int
    suspend fun batchDelete(ids: List<String>)
    suspend fun batchRestore(ids: List<String>)
    /** Gives the entry [oldId] the id [newId]. */
    suspend fun changeId(oldId: String, newId: String)
    suspend fun getTombstones(): List<SyncTombstone>
    suspend fun <T> transaction(block: suspend () -> T): T
}
