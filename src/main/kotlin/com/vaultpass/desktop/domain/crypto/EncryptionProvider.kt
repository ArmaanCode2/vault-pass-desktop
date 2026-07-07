package com.vaultpass.desktop.domain.crypto

import com.vaultpass.desktop.domain.security.SecureByteArray

/**
 * Interface abstracting symmetric encryption operations (e.g., AES-GCM).
 */
interface EncryptionProvider {
    
    /**
     * Encrypts the Data Encryption Key (DEK) using the Key Encryption Key (KEK).
     *
     * @param dek The active Data Encryption Key.
     * @param kek The derived Key Encryption Key.
     * @param iv The initialization vector.
     * @return The encrypted (wrapped) DEK as a byte array.
     */
    @Throws(CryptoException::class)
    fun wrapDek(dek: SecureByteArray, kek: SecureByteArray, iv: ByteArray): ByteArray

    /**
     * Decrypts the wrapped DEK using the Key Encryption Key (KEK).
     *
     * @param wrappedDek The encrypted DEK read from metadata.
     * @param kek The derived Key Encryption Key.
     * @param iv The initialization vector read from metadata.
     * @return The plaintext Data Encryption Key securely wrapped in memory.
     */
    @Throws(CryptoException::class)
    fun unwrapDek(wrappedDek: ByteArray, kek: SecureByteArray, iv: ByteArray): SecureByteArray
}
