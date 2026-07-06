package com.vaultpass.desktop.domain.migration

import com.vaultpass.desktop.domain.models.VaultMetadata

class UnsupportedVaultVersionException(message: String) : Exception(message)

/**
 * Runner responsible for executing migrations on the VaultMetadata.
 */
class MigrationRunner(
    private val registry: MigrationRegistry
) {
    /**
     * Finds and executes required migrations to bring the metadata up to the target version.
     */
    fun run(type: MigrationType, currentVersion: Int, targetVersion: Int, initialContext: VaultMetadata): VaultMetadata {
        if (currentVersion > targetVersion) {
            throw UnsupportedVaultVersionException(
                "The vault's $type version ($currentVersion) is newer than the supported version ($targetVersion). Please update the app."
            )
        }

        var currentContext = initialContext
        var version = currentVersion

        val availableMigrations = registry.getMigrations(type)

        for (migration in availableMigrations) {
            if (migration.targetVersion > version && migration.targetVersion <= targetVersion) {
                currentContext = migration.migrate(currentContext)
                version = migration.targetVersion
            }
        }

        return currentContext
    }
}
