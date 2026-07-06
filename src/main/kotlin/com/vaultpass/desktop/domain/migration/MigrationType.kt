package com.vaultpass.desktop.domain.migration

/**
 * Defines the subsystem that a migration applies to.
 */
enum class MigrationType {
    VAULT,
    METADATA,
    KDF,
    ENCRYPTION,
    SETTINGS
}
