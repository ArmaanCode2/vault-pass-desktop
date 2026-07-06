package com.vaultpass.desktop.domain

import com.vaultpass.desktop.domain.models.VaultCategory
import kotlinx.coroutines.flow.Flow

/**
 * Defines the strict contract for interacting with Vault Categories.
 * Abstracted to support either local or remote/syncing data sources seamlessly.
 */
interface CategoryRepository {
    /**
     * Retrieves all categories as a reactive stream.
     */
    fun getAllCategories(): Flow<RepositoryResult<List<VaultCategory>>>
    
    /**
     * Retrieves a single category by its ID.
     */
    suspend fun getCategoryById(id: String): RepositoryResult<VaultCategory?>
    
    /**
     * Inserts a new category.
     */
    suspend fun insertCategory(category: VaultCategory): RepositoryResult<Unit>
    
    /**
     * Updates an existing category.
     */
    suspend fun updateCategory(category: VaultCategory): RepositoryResult<Unit>
    
    /**
     * Deletes a category by its ID.
     */
    suspend fun deleteCategory(id: String): RepositoryResult<Unit>
}
