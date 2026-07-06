package com.vaultpass.desktop.domain.migration

/**
 * A central registry for all available migrations.
 */
class MigrationRegistry {
    private val migrations = mutableListOf<Migration>()

    fun register(migration: Migration) {
        migrations.add(migration)
    }

    fun getMigrations(type: MigrationType): List<Migration> {
        return migrations.filter { it.type == type }.sortedBy { it.targetVersion }
    }
}
