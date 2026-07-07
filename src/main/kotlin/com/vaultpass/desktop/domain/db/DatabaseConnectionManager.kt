package com.vaultpass.desktop.domain.db

/**
 * Defines the contract for managing the underlying database connection lifecycle.
 * Abstracted so the SessionManager can open/close connections without knowing about SQLite.
 */
interface DatabaseConnectionManager {
    /**
     * Opens the database connection.
     */
    fun openConnection()

    /**
     * Closes the database connection securely.
     */
    fun closeConnection()
}
