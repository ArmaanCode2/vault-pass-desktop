package com.vaultpass.desktop.domain.crypto

import com.vaultpass.desktop.domain.security.SecureByteArray
import com.vaultpass.desktop.domain.security.SecureCharArray

/**
 * Interface abstracting Key Derivation Functions (e.g., PBKDF2, Argon2).
 */
interface KdfProvider {
    
    /**
     * Derives a Key Encryption Key (KEK) from a user password and salt.
     *
     * @param password The user's master password in a secure memory container.
     * @param salt The cryptographic salt.
     * @param iterations The number of iterations to apply.
     * @return A secure byte array containing the derived KEK.
     */
    @Throws(CryptoException::class)
    fun deriveKek(password: SecureCharArray, salt: ByteArray, iterations: Int): SecureByteArray

    /**
     * Derives a verification hash from the KEK, used for fast password validation
     * without exposing the actual KEK or DEK.
     *
     * @param kek The Key Encryption Key.
     * @return The derived verification hash.
     */
    @Throws(CryptoException::class)
    fun deriveMasterHash(kek: SecureByteArray): ByteArray
}
