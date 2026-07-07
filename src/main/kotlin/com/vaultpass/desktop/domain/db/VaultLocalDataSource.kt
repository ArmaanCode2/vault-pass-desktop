package com.vaultpass.desktop.domain.db

import com.vaultpass.desktop.data.models.VaultEntity
import kotlinx.coroutines.flow.Flow

/**
 * Defines raw CRUD operations for Vault Entries at the local database level.
 * Abstracted to decouple the Repository layer from the actual SQL engine (Room/SQLDelight).
 */
interface VaultLocalDataSource {
    fun observeAll(): Flow<List<VaultEntity>>
    suspend fun getById(id: String): VaultEntity?
    suspend fun insert(entity: VaultEntity)
    suspend fun update(entity: VaultEntity)
    suspend fun delete(id: String)
}
