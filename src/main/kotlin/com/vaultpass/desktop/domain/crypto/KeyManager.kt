package com.vaultpass.desktop.domain.crypto

import com.vaultpass.desktop.domain.security.SecureByteArray

/**
 * Interface responsible for managing the active session's Data Encryption Key (DEK).
 */
interface KeyManager {
    
    /**
     * Stores the unwrapped active DEK securely in memory for the duration of the unlocked session.
     */
    @Throws(CryptoException::class)
    fun loadActiveDek(dek: SecureByteArray)
    
    /**
     * Wipes the active DEK from memory and clears the session state.
     */
    fun wipeKeys()
    
    /**
     * Retrieves a reference to the active DEK for use by the repository layer.
     * Must throw an exception if the vault is locked and the key is unavailable.
     */
    @Throws(CryptoException::class)
    fun getActiveDek(): SecureByteArray
}
