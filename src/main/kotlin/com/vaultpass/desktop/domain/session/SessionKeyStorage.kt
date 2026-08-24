package com.vaultpass.desktop.domain.session

import com.vaultpass.desktop.domain.security.SecureByteArray

/**
 * Contract for securely storing session secrets (like the active DEK) across application restarts.
 */
interface SessionKeyStorage {
    
    /**
     * Checks if a protected session exists.
     */
    fun hasProtectedSession(): Boolean

    /**
     * Saves the provided DEK (or session secret) to protected storage.
     */
    fun saveProtectedSession(dek: SecureByteArray)

    /**
     * Loads and unprotects the DEK. Returns null if loading or unprotecting fails.
     */
    fun loadProtectedSession(): SecureByteArray?

    /**
     * Clears any saved protected session.
     */
    fun clearProtectedSession()
}
