package com.vaultpass.desktop.domain.crypto

/**
 * A sealed hierarchy of all possible cryptographic exceptions.
 * Ensures the presentation layer never receives raw java.security or javax.crypto exceptions.
 */
sealed class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    
    /**
     * Thrown when Key Derivation fails (e.g. invalid iteration count, algorithm not found).
     */
    class KeyDerivationFailed(message: String, cause: Throwable? = null) : CryptoException(message, cause)
    
    /**
     * Thrown when the user provides the wrong Master Password, resulting in an invalid KEK/MAC.
     */
    class InvalidKey(message: String = "Incorrect password or invalid key", cause: Throwable? = null) : CryptoException(message, cause)
    
    /**
     * Thrown when AES unwrapping or decrypting data fails due to corruption or tampering.
     */
    class DecryptionFailed(message: String, cause: Throwable? = null) : CryptoException(message, cause)
    
    /**
     * Thrown when the cryptographic environment is missing required algorithms.
     */
    class UnsupportedAlgorithm(message: String, cause: Throwable? = null) : CryptoException(message, cause)
    
    /**
     * Thrown when attempting to perform operations without an active DEK in memory.
     */
    class KeyNotLoaded(message: String = "Vault is locked or key is not loaded") : CryptoException(message)
}
