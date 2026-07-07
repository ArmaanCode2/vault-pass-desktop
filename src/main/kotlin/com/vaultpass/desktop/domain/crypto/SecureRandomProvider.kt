package com.vaultpass.desktop.domain.crypto

import com.vaultpass.desktop.domain.security.SecureByteArray

/**
 * Interface responsible for securely generating unpredictable values.
 */
interface SecureRandomProvider {
    /**
     * Generates a securely random salt for key derivation.
     */
    fun generateSalt(lengthBytes: Int = 32): ByteArray
    
    /**
     * Generates a securely random Initialization Vector (IV) for symmetric encryption.
     */
    fun generateIv(lengthBytes: Int = 12): ByteArray
    
    /**
     * Generates a securely random Data Encryption Key (DEK).
     */
    fun generateDek(lengthBytes: Int = 32): SecureByteArray
}
