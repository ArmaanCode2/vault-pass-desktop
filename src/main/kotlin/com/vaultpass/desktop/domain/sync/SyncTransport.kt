package com.vaultpass.desktop.domain.sync

import kotlinx.coroutines.flow.Flow

/**
 * Defines the generic read/write/connect boundaries for any synchronization medium.
 * Future implementations (e.g., LanTransportImpl, BluetoothTransportImpl, CloudTransportImpl)
 * will implement this contract, completely decoupling the SyncManager from network details.
 */
interface SyncTransport {
    
    /**
     * Initializes the connection to the transport medium.
     */
    suspend fun connect(): Boolean
    
    /**
     * Closes the connection and cleans up resources.
     */
    fun disconnect()
    
    /**
     * Sends a raw encrypted payload to the connected peer/server.
     */
    suspend fun sendPayload(payload: ByteArray): Boolean
    
    /**
     * A reactive stream that emits incoming encrypted payloads as they arrive.
     */
    fun receivePayloads(): Flow<ByteArray>
}
