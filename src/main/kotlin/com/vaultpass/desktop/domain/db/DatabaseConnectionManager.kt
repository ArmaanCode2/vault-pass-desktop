package com.vaultpass.desktop.domain.db

import kotlinx.coroutines.flow.StateFlow

/**
 * Defines the contract for managing the underlying database connection lifecycle.
 * Abstracted so the SessionManager can open/close connections without knowing about SQLite.
 */
interface DatabaseConnectionManager {
    val connectionState: StateFlow<Boolean>

    /**
     * Opens the database connection.
     */
    fun openConnection()

    /**
     * Closes the database connection securely.
     */
    fun closeConnection()

    /**
     * Closes the database connection and returns only once it really is closed, after whoever was
     * using it has finished. For when the database file is deleted or replaced next.
     */
    suspend fun closeConnectionAndWait() = closeConnection()

    /**
     * Sets what to call when the open connection had to be dropped on its own (not by
     * [closeConnection]), so the database is closed while the session may think it is open.
     */
    fun setOnConnectionLost(listener: (() -> Unit)?) {}
}

/** The database was saved by a newer version of VaultPass. It was not changed. */
class NewerVaultException(
    val foundVersion: Int,
    val supportedVersion: Int
) : Exception("The vault database has schema version $foundVersion; this version of VaultPass supports up to $supportedVersion.")

/**
 * The database was closed (the vault locked) before or while the work ran. A transaction that
 * fails with it was rolled back. When openConnection() throws it, the connection it opened was
 * dropped, but what opening did first (a backup before an upgrade, a schema migration) is kept.
 * [cause] is what the work failed with, when it failed because of the lock.
 */
class DatabaseClosedException(cause: Throwable? = null) : IllegalStateException("Database connection is not open.", cause)

/** The copy made before a schema upgrade failed, so the upgrade did not run. */
class DatabaseBackupException(
    reason: String,
    cause: Throwable? = null
) : Exception("Couldn't back up the vault before upgrading it: $reason", cause)
