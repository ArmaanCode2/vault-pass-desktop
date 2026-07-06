package com.vaultpass.desktop.domain.db

/**
 * Defines generic lifecycle hooks and transaction management for the local database.
 * This contract prevents domain logic from leaking driver-specific classes
 * (like SQLDelight's SqlDriver or Room's SupportSQLiteDatabase).
 */
interface LocalDatabaseManager {
    /**
     * Bootstraps the database connection.
     */
    suspend fun initialize()

    /**
     * Executes the given block within a single database transaction.
     * Rolls back automatically if an exception is thrown.
     */
    suspend fun <T> transaction(block: suspend () -> T): T

    /**
     * Closes the database connection.
     */
    fun close()
}
