package com.vaultpass.desktop.domain.db

import com.vaultpass.desktop.domain.models.VaultCategory
import kotlinx.coroutines.flow.Flow

/**
 * Defines raw CRUD operations for Categories at the local database level.
 * Abstracted to decouple the Repository layer from the actual SQL engine (Room/SQLDelight).
 */
interface CategoryLocalDataSource {
    fun observeAll(): Flow<List<VaultCategory>>
    suspend fun getById(id: String): VaultCategory?
    suspend fun insert(category: VaultCategory)
    suspend fun update(category: VaultCategory)
    suspend fun delete(id: String)
}
