package com.vaultpass.desktop.data.database

import com.vaultpass.desktop.domain.db.DatabaseConnectionManager
import java.sql.Connection
import java.sql.DriverManager
import java.io.File

/**
 * Manages the lifecycle of the SQLite connection.
 * Ensuring that connections are securely established and destroyed.
 */
class SQLiteConnectionManager(private val databasePath: String) : DatabaseConnectionManager {

    private var connection: Connection? = null

    init {
        // Ensure parent directories exist
        val file = File(databasePath)
        file.parentFile?.mkdirs()
    }

    /**
     * Opens the database connection and initializes the schema if necessary.
     */
    override fun openConnection() {
        if (connection == null || connection?.isClosed == true) {
            val url = "jdbc:sqlite:$databasePath"
            connection = DriverManager.getConnection(url)
            initializeSchema()
        }
    }

    /**
     * Returns the active connection. Throws an exception if not opened.
     */
    fun getConnection(): Connection {
        return connection ?: throw IllegalStateException("Database connection is not open.")
    }

    /**
     * Closes the connection securely.
     */
    override fun closeConnection() {
        connection?.close()
        connection = null
    }

    private fun initializeSchema() {
        val conn = getConnection()
        conn.createStatement().use { statement ->
            statement.execute(DatabaseQueries.CREATE_VAULT_ENTRY_TABLE)
        }
    }
}
