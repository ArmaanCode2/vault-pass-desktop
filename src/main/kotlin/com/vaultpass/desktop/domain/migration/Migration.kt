package com.vaultpass.desktop.domain.migration

import com.vaultpass.desktop.domain.models.VaultMetadata

/**
 * Interface for all metadata or vault migrations.
 */
interface Migration {
    val type: MigrationType
    val targetVersion: Int
    
    /**
     * Executes the migration on the provided metadata and returns the updated metadata.
     */
    fun migrate(metadata: VaultMetadata): VaultMetadata
}
