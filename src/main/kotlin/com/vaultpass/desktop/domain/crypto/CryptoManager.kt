package com.vaultpass.desktop.domain.crypto

import com.vaultpass.desktop.domain.models.EncryptionConfig
import com.vaultpass.desktop.domain.models.KdfConfig
import com.vaultpass.desktop.domain.security.SecureByteArray
import com.vaultpass.desktop.domain.security.SecureCharArray

/**
 * High-level orchestration facade for all cryptography.
 * Business logic and repositories depend on this interface, shielding them from the
 * complexities of PBKDF2, AES-GCM, and raw javax.crypto primitives.
 */
interface CryptoManager {
    
    /**
     * Executes the initial setup cryptographic workflow:
     * 1. Generates salts, IVs, and a new DEK.
     * 2. Derives KEK and Master Hash from the password.
     * 3. Encrypts the DEK.
     * 4. Loads the DEK into the KeyManager for immediate use.
     *
     * @return Pair containing the generated KdfConfig and EncryptionConfig to be saved to metadata.
     */
    @Throws(CryptoException::class)
    fun setupVault(password: SecureCharArray): Pair<KdfConfig, EncryptionConfig>
    
    /**
     * Executes the unlock cryptographic workflow:
     * 1. Derives Trial KEK and Trial Hash from the input password.
     * 2. Performs a constant-time verification against the expected Hash.
     * 3. If verified, decrypts the wrapped DEK.
     * 4. Loads the DEK into the KeyManager for the session.
     *
     * @return CryptoResult.Success if the password is correct, Failure otherwise.
     */
    fun unlockVault(password: SecureCharArray, kdfConfig: KdfConfig, encryptionConfig: EncryptionConfig): CryptoResult<Unit>
    
    /**
     * Wipes all keys from memory, effectively locking the cryptographic layer.
     */
    fun lockVault()
    
    /**
     * Encrypts arbitrary raw database data using the active session DEK.
     */
    @Throws(CryptoException::class)
    fun encryptData(plaintext: ByteArray): ByteArray
    
    /**
     * Decrypts arbitrary raw database data using the active session DEK.
     */
    @Throws(CryptoException::class)
    fun decryptData(ciphertext: ByteArray): ByteArray
}
