package com.vaultpass.desktop.data.sync

import com.vaultpass.desktop.data.database.SQLiteConnectionManager
import com.vaultpass.desktop.domain.db.DatabaseClosedException
import com.vaultpass.desktop.domain.sync.models.PairedDevice
import com.vaultpass.synccore.PairKeyFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Thread-safe SQLite repository managing permanent paired devices (bonded via LAN/QR pairing).
 * Reads go through [SQLiteConnectionManager.withConnection], writes through
 * [SQLiteConnectionManager.inTransaction].
 */
class PairedDeviceRepository(
    private val connectionManager: SQLiteConnectionManager
) {
    private val changeNotifier = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    suspend fun getPairedDevices(): List<PairedDevice> = connectionManager.withConnection { conn ->
        val sql = """
            SELECT device_id, device_name, shared_secret, ip_address, port, paired_at, last_sync_at
            FROM paired_device
            ORDER BY paired_at DESC
        """.trimIndent()

        val list = mutableListOf<PairedDevice>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    list.add(
                        PairedDevice(
                            deviceId = rs.getString("device_id"),
                            deviceName = rs.getString("device_name"),
                            sharedSecret = rs.getString("shared_secret"),
                            ipAddress = rs.getString("ip_address"),
                            port = rs.getInt("port"),
                            pairedAt = rs.getLong("paired_at"),
                            lastSyncAt = rs.getLong("last_sync_at"),
                            isOnline = false
                        )
                    )
                }
            }
        }
        // Only protocol v2 pairings count; v1 rows are removed by purgeLegacyPairings().
        list.filter { PairKeyFormat.isCurrent(it.sharedSecret) }
    }

    /**
     * The paired devices; none while the database is closed (the vault is locked), and read
     * again when it opens.
     */
    fun observePairedDevices(): Flow<List<PairedDevice>> = combine(
        connectionManager.connectionState,
        changeNotifier.onStart { emit(Unit) }
    ) { isConnected, _ -> isConnected }
        .map { isConnected -> if (isConnected) pairedDevicesOrNone() else emptyList() }
        .flowOn(Dispatchers.IO)

    /**
     * Runs [block] in one database transaction: its writes are kept together or not at all, and
     * a lock before the commit undoes them (throwing [DatabaseClosedException]). Work that
     * encrypts with the vault key belongs inside, so nothing encrypted while the key is being
     * wiped is ever committed.
     */
    suspend fun <T> transaction(block: suspend PairedDeviceRepository.() -> T): T =
        connectionManager.inTransaction { block() }

    private suspend fun pairedDevicesOrNone(): List<PairedDevice> = try {
        getPairedDevices()
    } catch (_: DatabaseClosedException) {
        emptyList()
    }

    suspend fun getPairedDevice(deviceId: String): PairedDevice? = connectionManager.withConnection { conn ->
        val sql = """
            SELECT device_id, device_name, shared_secret, ip_address, port, paired_at, last_sync_at
            FROM paired_device
            WHERE device_id = ?
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, deviceId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    PairedDevice(
                        deviceId = rs.getString("device_id"),
                        deviceName = rs.getString("device_name"),
                        sharedSecret = rs.getString("shared_secret"),
                        ipAddress = rs.getString("ip_address"),
                        port = rs.getInt("port"),
                        pairedAt = rs.getLong("paired_at"),
                        lastSyncAt = rs.getLong("last_sync_at"),
                        isOnline = false
                    ).takeIf { PairKeyFormat.isCurrent(it.sharedSecret) }
                } else null
            }
        }
    }

    /**
     * Deletes pairings made with sync protocol v1, whose keys travelled over the network in
     * plaintext. Those devices must be paired again.
     */
    suspend fun purgeLegacyPairings(): Int {
        val sql = "DELETE FROM paired_device WHERE shared_secret IS NULL OR substr(shared_secret, 1, 3) <> ?"
        val removed = connectionManager.inTransaction { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, PairKeyFormat.PREFIX)
                stmt.executeUpdate()
            }
        }
        if (removed > 0) changeNotifier.tryEmit(Unit)
        return removed
    }

    suspend fun savePairedDevice(device: PairedDevice) {
        val sql = """
            INSERT OR REPLACE INTO paired_device (
                device_id, device_name, shared_secret, ip_address, port, paired_at, last_sync_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        connectionManager.inTransaction { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, device.deviceId)
                stmt.setString(2, device.deviceName)
                stmt.setString(3, device.sharedSecret)
                stmt.setString(4, device.ipAddress)
                stmt.setInt(5, device.port)
                stmt.setLong(6, device.pairedAt)
                stmt.setLong(7, device.lastSyncAt)
                stmt.executeUpdate()
            }
        }
        changeNotifier.tryEmit(Unit)
    }

    suspend fun unpairDevice(deviceId: String) {
        val sql = "DELETE FROM paired_device WHERE device_id = ?"
        connectionManager.inTransaction { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, deviceId)
                stmt.executeUpdate()
            }
        }
        changeNotifier.tryEmit(Unit)
    }

    suspend fun updateLastSync(deviceId: String, timestamp: Long) {
        val sql = "UPDATE paired_device SET last_sync_at = ? WHERE device_id = ?"
        connectionManager.inTransaction { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, timestamp)
                stmt.setString(2, deviceId)
                stmt.executeUpdate()
            }
        }
        changeNotifier.tryEmit(Unit)
    }

    suspend fun updateNetworkLocation(deviceId: String, ipAddress: String, port: Int) {
        val sql = "UPDATE paired_device SET ip_address = ?, port = ? WHERE device_id = ?"
        connectionManager.inTransaction { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, ipAddress)
                stmt.setInt(2, port)
                stmt.setString(3, deviceId)
                stmt.executeUpdate()
            }
        }
        changeNotifier.tryEmit(Unit)
    }
}
