package com.vaultpass.desktop.domain.sync

/**
 * Represents the current status of a background synchronization session.
 * The Settings UI can observe this state to display progress without knowing
 * the underlying transport medium.
 */
enum class SyncState {
    /**
     * No active synchronization.
     */
    IDLE,
    
    /**
     * Handshaking or exchanging trust certificates.
     */
    PAIRING,
    
    /**
     * An incoming pairing request has arrived; awaiting user confirmation on local device.
     */
    AWAITING_LOCAL_APPROVAL,
    
    /**
     * Local user approved pairing; waiting for remote peer to accept.
     */
    WAITING_FOR_REMOTE_APPROVAL,
    
    /**
     * Mutual pairing accepted and bonded keys stored.
     */
    PAIRED,
    
    /**
     * Actively transferring encrypted payloads and merging diffs.
     */
    SYNCING,
    
    /**
     * A sync operation failed (timeout, network error, authentication failure).
     */
    ERROR
}
