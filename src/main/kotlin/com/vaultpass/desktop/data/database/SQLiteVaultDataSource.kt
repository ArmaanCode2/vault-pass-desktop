package com.vaultpass.desktop.data.database

import com.vaultpass.desktop.data.models.VaultEntity
import com.vaultpass.desktop.domain.db.VaultLocalDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.sql.ResultSet

/**
 * Concrete implementation of VaultLocalDataSource using SQLite.
 */
class SQLiteVaultDataSource(
    private val connectionManager: SQLiteConnectionManager
) : VaultLocalDataSource {

    // Emits a signal every time a write operation occurs to trigger reactive re-queries.
    private val invalidationTracker = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun observeAll(): Flow<List<VaultEntity>> {
        return invalidationTracker
            .onStart { emit(Unit) } // Initial query on collection
            .map {
                withContext(Dispatchers.IO) {
                    val entries = mutableListOf<VaultEntity>()
                    connectionManager.getConnection()?.let { conn ->
                        val sql = "SELECT * FROM vault_entry WHERE is_deleted = 0"
                        conn.prepareStatement(sql).use { stmt ->
                            stmt.executeQuery().use { rs ->
                                while (rs.next()) {
                                    entries.add(mapResultSetToEntity(rs))
                                }
                            }
                        }
                    }
                    entries
                }
            }
    }

    override suspend fun getById(id: String): VaultEntity? = withContext(Dispatchers.IO) {
        connectionManager.getConnection()?.let { conn ->
            val sql = "SELECT * FROM vault_entry WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        return@withContext mapResultSetToEntity(rs)
                    }
                }
            }
        }
        null
    }

    override suspend fun insert(entity: VaultEntity): Unit = withContext(Dispatchers.IO) {
        connectionManager.getConnection()?.let { conn ->
            val sql = """
                INSERT INTO vault_entry 
                (id, type, encrypted_payload, created_at, updated_at, is_favorite, is_deleted, deleted_at, sync_version) 
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, entity.id)
                stmt.setString(2, entity.type)
                stmt.setBytes(3, entity.encryptedPayload)
                stmt.setLong(4, entity.createdAt)
                stmt.setLong(5, entity.updatedAt)
                stmt.setInt(6, if (entity.isFavorite) 1 else 0)
                stmt.setInt(7, if (entity.isDeleted) 1 else 0)
                if (entity.deletedAt != null) {
                    stmt.setLong(8, entity.deletedAt)
                } else {
                    stmt.setNull(8, java.sql.Types.INTEGER)
                }
                stmt.setInt(9, entity.syncVersion)
                
                stmt.executeUpdate()
            }
            invalidationTracker.tryEmit(Unit)
        }
    }

    override suspend fun update(entity: VaultEntity): Unit = withContext(Dispatchers.IO) {
        connectionManager.getConnection()?.let { conn ->
            val sql = """
                UPDATE vault_entry 
                SET type = ?, encrypted_payload = ?, updated_at = ?, is_favorite = ?, sync_version = ?
                WHERE id = ?
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, entity.type)
                stmt.setBytes(2, entity.encryptedPayload)
                stmt.setLong(3, entity.updatedAt)
                stmt.setInt(4, if (entity.isFavorite) 1 else 0)
                stmt.setInt(5, entity.syncVersion)
                stmt.setString(6, entity.id)
                
                stmt.executeUpdate()
            }
            invalidationTracker.tryEmit(Unit)
        }
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        connectionManager.getConnection()?.let { conn ->
            val sql = "DELETE FROM vault_entry WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                stmt.executeUpdate()
            }
            invalidationTracker.tryEmit(Unit)
        }
    }

    private fun mapResultSetToEntity(rs: ResultSet): VaultEntity {
        val deletedAtVal = rs.getLong("deleted_at")
        val deletedAt = if (rs.wasNull()) null else deletedAtVal
        
        return VaultEntity(
            id = rs.getString("id"),
            type = rs.getString("type"),
            encryptedPayload = rs.getBytes("encrypted_payload"),
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
            isFavorite = rs.getInt("is_favorite") == 1,
            isDeleted = rs.getInt("is_deleted") == 1,
            deletedAt = deletedAt,
            syncVersion = rs.getInt("sync_version")
        )
    }
}
