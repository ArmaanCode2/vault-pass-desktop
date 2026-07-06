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
     * Actively scanning for peers (e.g. mDNS for LAN, or Bluetooth discovery).
     */
    DISCOVERING,
    
    /**
     * Handshaking or exchanging trust certificates.
     */
    PAIRING,
    
    /**
     * Actively transferring encrypted payloads and merging diffs.
     */
    SYNCING,
    
    /**
     * A sync operation failed (timeout, network error, authentication failure).
     */
    ERROR
}
