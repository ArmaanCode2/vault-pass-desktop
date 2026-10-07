package com.vaultpass.desktop.data.database

import com.vaultpass.desktop.data.models.VaultEntity
import com.vaultpass.desktop.domain.db.DatabaseClosedException
import com.vaultpass.desktop.domain.db.VaultLocalDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.combine
import com.vaultpass.desktop.domain.models.SyncTombstone
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Concrete implementation of VaultLocalDataSource using SQLite.
 * Reads go through [SQLiteConnectionManager.withConnection] and every write, even a single
 * statement, through [SQLiteConnectionManager.inTransaction].
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
        return combine(
            connectionManager.connectionState,
            invalidationTracker.onStart { emit(Unit) }
        ) { isConnected, _ -> isConnected }
            .map { isConnected ->
                val entries = mutableListOf<VaultEntity>()
                if (isConnected) {
                    try {
                        connectionManager.withConnection { conn ->
                            val sql = "SELECT * FROM vault_entry"
                            conn.prepareStatement(sql).use { stmt ->
                                stmt.executeQuery().use { rs ->
                                    while (rs.next()) {
                                        entries.add(mapResultSetToEntity(rs))
                                    }
                                }
                            }
                        }
                    } catch (e: DatabaseClosedException) {
                        // Locked meanwhile: there is nothing to show.
                        entries.clear()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        System.err.println("SQLiteVaultDataSource: Error reading vault entries: ${e.message}")
                        throw e
                    }
                }
                entries
            }
    }

    override suspend fun getById(id: String): VaultEntity? = connectionManager.withConnection { conn ->
        val sql = "SELECT * FROM vault_entry WHERE id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapResultSetToEntity(rs) else null
            }
        }
    }

    override suspend fun insert(entity: VaultEntity) {
        val sql = """
            INSERT OR REPLACE INTO vault_entry
            (id, type, encrypted_payload, created_at, updated_at, is_favorite, is_deleted, deleted_at, sync_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        connectionManager.inTransaction { conn ->
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
            deleteTombstone(conn, entity.id)
        }
        invalidationTracker.tryEmit(Unit)
    }

    override suspend fun update(entity: VaultEntity) {
        val sql = """
            UPDATE vault_entry
            SET type = ?, encrypted_payload = ?, updated_at = ?, is_favorite = ?, sync_version = ?, is_deleted = ?, deleted_at = ?
            WHERE id = ?
        """.trimIndent()

        connectionManager.inTransaction { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, entity.type)
                stmt.setBytes(2, entity.encryptedPayload)
                stmt.setLong(3, entity.updatedAt)
                stmt.setInt(4, if (entity.isFavorite) 1 else 0)
                stmt.setInt(5, entity.syncVersion)
                stmt.setInt(6, if (entity.isDeleted) 1 else 0)
                if (entity.deletedAt != null) {
                    stmt.setLong(7, entity.deletedAt)
                } else {
                    stmt.setNull(7, java.sql.Types.INTEGER)
                }
                stmt.setString(8, entity.id)

                stmt.executeUpdate()
            }
        }
        invalidationTracker.tryEmit(Unit)
    }

    override suspend fun delete(id: String) {
        connectionManager.inTransaction { conn ->
            deleteWithTombstones(conn, "id = ?") { stmt, first -> stmt.setString(first, id) }
        }
        invalidationTracker.tryEmit(Unit)
    }

    override suspend fun emptyRecycleBin() {
        connectionManager.inTransaction { conn -> deleteWithTombstones(conn, "is_deleted = 1") { _, _ -> } }
        invalidationTracker.tryEmit(Unit)
    }

    override suspend fun deleteRecycleBinOlderThan(thresholdMillis: Long): Int {
        val deletedCount = connectionManager.inTransaction { conn ->
            deleteWithTombstones(conn, "is_deleted = 1 AND deleted_at IS NOT NULL AND deleted_at < ?") { stmt, first ->
                stmt.setLong(first, thresholdMillis)
            }
        }
        if (deletedCount > 0) {
            invalidationTracker.tryEmit(Unit)
        }
        return deletedCount
    }

    override suspend fun batchDelete(ids: List<String>) {
        if (ids.isEmpty()) return
        connectionManager.inTransaction { conn ->
            for (id in ids) {
                deleteWithTombstones(conn, "id = ?") { stmt, first -> stmt.setString(first, id) }
            }
        }
        invalidationTracker.tryEmit(Unit)
    }

    override suspend fun batchRestore(ids: List<String>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        connectionManager.inTransaction { conn ->
            val sql = "UPDATE vault_entry SET is_deleted = 0, deleted_at = NULL, updated_at = ? WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                for (id in ids) {
                    stmt.setLong(1, now)
                    stmt.setString(2, id)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
        invalidationTracker.tryEmit(Unit)
    }

    override suspend fun changeId(oldId: String, newId: String) {
        if (oldId == newId) return
        connectionManager.inTransaction { conn ->
            val changed = conn.prepareStatement("UPDATE vault_entry SET id = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, newId)
                stmt.setString(2, oldId)
                stmt.executeUpdate()
            }
            check(changed == 1) { "No entry to rename" }
            deleteTombstone(conn, newId)
        }
        invalidationTracker.tryEmit(Unit)
    }

    override suspend fun getTombstones(): List<SyncTombstone> = connectionManager.withConnection { conn ->
        val tombstones = mutableListOf<SyncTombstone>()
        conn.prepareStatement("SELECT sync_id, deleted_at FROM sync_tombstone").use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    tombstones.add(SyncTombstone(syncId = rs.getString("sync_id"), deletedAt = rs.getLong("deleted_at")))
                }
            }
        }
        tombstones
    }

    /**
     * Deletes the vault_entry rows matching [where], first recording a tombstone for each (their
     * deleted_at, or now when they have none). [bind] fills the placeholders of [where] starting
     * at the given index. Returns the number of rows deleted.
     */
    private fun deleteWithTombstones(conn: Connection, where: String, bind: (PreparedStatement, Int) -> Unit): Int {
        val tombstoneSql = """
            INSERT OR REPLACE INTO sync_tombstone (sync_id, deleted_at)
            SELECT id, COALESCE(deleted_at, ?) FROM vault_entry WHERE $where
        """.trimIndent()
        conn.prepareStatement(tombstoneSql).use { stmt ->
            stmt.setLong(1, System.currentTimeMillis())
            bind(stmt, 2)
            stmt.executeUpdate()
        }
        return conn.prepareStatement("DELETE FROM vault_entry WHERE $where").use { stmt ->
            bind(stmt, 1)
            stmt.executeUpdate()
        }
    }

    private fun deleteTombstone(conn: Connection, syncId: String) {
        conn.prepareStatement("DELETE FROM sync_tombstone WHERE sync_id = ?").use { stmt ->
            stmt.setString(1, syncId)
            stmt.executeUpdate()
        }
    }

    override suspend fun <T> transaction(block: suspend () -> T): T {
        val result = connectionManager.inTransaction { block() }
        invalidationTracker.tryEmit(Unit)
        return result
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
