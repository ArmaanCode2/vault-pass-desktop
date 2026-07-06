package com.vaultpass.desktop.domain.security

/**
 * Centralized constants for the application's target settings structure.
 * When the app boots, the MigrationRunner will use these targets to determine
 * if the user's settings need to be upgraded.
 */
object SettingsVersion {
    const val TARGET_SETTINGS_VERSION = 1
    const val TARGET_SCHEMA_VERSION = 1
    const val TARGET_MIGRATION_VERSION = 1
}
