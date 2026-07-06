package com.vaultpass.desktop.domain.models

/**
 * Encapsulates the versioning state of the user's application preferences.
 * This guarantees that future releases can modify the settings structure
 * or defaults without wiping out a user's existing preferences.
 */
data class SettingsMetadata(
    val settingsVersion: Int,
    val schemaVersion: Int,
    val migrationVersion: Int
)
