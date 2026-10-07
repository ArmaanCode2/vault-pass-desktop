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

    /**
     * A copy of the active DEK, for a key that leaves the key manager (e.g. the stored startup
     * session). Unlike [getActiveDek] it can't be zero-filled by [wipeKeys] while it is used;
     * the caller wipes it when done. Throws when no key is loaded.
     *
     * Real implementations must override this and take the copy atomically with respect to
     * [loadActiveDek] and [wipeKeys], so it is never half wiped (InMemoryKeyManager does). This
     * default only clones [getActiveDek] and is NOT atomic: a wipe running at the same time can
     * leave the copy partly zeroed. It exists so simple test fakes keep compiling.
     */
    @Throws(CryptoException::class)
    fun copyActiveDek(): SecureByteArray = SecureByteArray(getActiveDek().data.clone())

    /**
     * Checks whether an active DEK is currently loaded in memory.
     */
    fun hasActiveDek(): Boolean = try {
        getActiveDek()
        true
    } catch (e: Exception) {
        false
    }
}
