package com.vaultpass.desktop.domain.sync

import kotlinx.coroutines.flow.StateFlow

/**
 * The orchestrator for all background synchronization tasks.
 * It takes a SyncTransport, handles the pairing if necessary, and coordinates the 
 * pushing and pulling of data to and from the local database.
 */
interface SyncManager {
    
    /**
     * A reactive stream representing the global status of the sync operation.
     */
    val syncState: StateFlow<SyncState>

    /**
     * Initiates a sync session using the specified transport and pairing protocols.
     */
    suspend fun startSync(transport: SyncTransport, pairingProtocol: PairingProtocol?)

    /**
     * Gracefully aborts an active sync session and transitions the state back to IDLE.
     */
    fun stopSync()
}
