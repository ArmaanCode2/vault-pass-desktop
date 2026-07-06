package com.vaultpass.desktop.domain.db

import com.vaultpass.desktop.domain.models.VaultEntry
import kotlinx.coroutines.flow.Flow

/**
 * Defines raw CRUD operations for Vault Entries at the local database level.
 * Abstracted to decouple the Repository layer from the actual SQL engine (Room/SQLDelight).
 */
interface VaultLocalDataSource {
    fun observeAll(): Flow<List<VaultEntry>>
    suspend fun getById(id: String): VaultEntry?
    suspend fun insert(entry: VaultEntry)
    suspend fun update(entry: VaultEntry)
    suspend fun delete(id: String)
}
