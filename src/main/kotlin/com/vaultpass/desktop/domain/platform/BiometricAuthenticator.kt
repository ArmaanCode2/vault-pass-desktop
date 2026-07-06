package com.vaultpass.desktop.domain.platform

/**
 * A generic contract for requesting a biometric authentication challenge from the OS.
 * Decouples VaultPass from specific APIs like Windows Hello, TouchID, or Polkit.
 */
interface BiometricAuthenticator {
    
    /**
     * Determines if the underlying operating system supports and has enrolled 
     * biometric credentials (fingerprint, face, etc.).
     */
    suspend fun isBiometricsAvailable(): Boolean

    /**
     * Prompts the user with an OS-level biometric challenge.
     *
     * @param promptMessage The text to display on the OS prompt (e.g. "Unlock VaultPass").
     * @return True if the user successfully authenticated, false otherwise.
     */
    suspend fun authenticate(promptMessage: String): Boolean
}
