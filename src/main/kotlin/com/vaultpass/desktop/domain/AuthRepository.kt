package com.vaultpass.desktop.domain

/**
 * Core interface for authentication operations.
 * This explicitly decouples the UI from the cryptography layer.
 * Future milestones will replace the simple implementation of this interface
 * with one that handles PBKDF2, KEK derivation, and AES database unlocking.
 */
interface AuthRepository {
    /**
     * Checks whether a vault exists on this device.
     */
    suspend fun hasVault(): Boolean

    /**
     * Creates a new vault protected by the given master password.
     * @return true if successful, false otherwise.
     */
    suspend fun createMasterPassword(password: String): Boolean

    /**
     * Verifies the provided master password against the existing vault.
     * @return true if the password is correct and vault is unlocked, false otherwise.
     */
    suspend fun verifyMasterPassword(password: String): Boolean

    /**
     * Locks the vault immediately.
     */
    fun lock()
}
