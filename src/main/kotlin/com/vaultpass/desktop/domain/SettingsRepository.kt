package com.vaultpass.desktop.domain

import com.vaultpass.desktop.domain.models.SettingsMetadata

/**
 * Defines the contract for fetching and mutating application settings.
 * Specifically handles the versioning metadata to hook into the MigrationRunner.
 */
interface SettingsRepository {
    /**
     * Retrieves the structural versioning information for the current settings.
     * If the settings do not exist, it should initialize them to the target defaults.
     */
    suspend fun getSettingsMetadata(): SettingsMetadata

    /**
     * Updates the structural versioning information.
     * This is typically called by the MigrationRunner after a successful settings migration.
     */
    suspend fun updateSettingsMetadata(metadata: SettingsMetadata)
}
