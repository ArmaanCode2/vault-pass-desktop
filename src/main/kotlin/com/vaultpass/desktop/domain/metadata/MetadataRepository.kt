package com.vaultpass.desktop.domain.metadata

import com.vaultpass.desktop.domain.models.VaultMetadata

/**
 * Interface responsible exclusively for reading and writing the VaultMetadata.
 */
interface MetadataRepository {
    suspend fun getMetadata(): VaultMetadata?
    suspend fun saveMetadata(metadata: VaultMetadata)
    suspend fun initializeMetadata(): VaultMetadata
}
