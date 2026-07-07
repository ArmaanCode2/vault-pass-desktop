package com.vaultpass.desktop.data.crypto

import com.vaultpass.desktop.domain.crypto.CryptoException
import com.vaultpass.desktop.domain.crypto.CryptoManager
import com.vaultpass.desktop.domain.crypto.CryptoResult
import com.vaultpass.desktop.domain.crypto.EncryptionProvider
import com.vaultpass.desktop.domain.crypto.KdfProvider
import com.vaultpass.desktop.domain.crypto.KeyManager
import com.vaultpass.desktop.domain.crypto.SecureRandomProvider
import com.vaultpass.desktop.domain.models.EncryptionConfig
import com.vaultpass.desktop.domain.models.KdfConfig
import com.vaultpass.desktop.domain.security.SecureCharArray
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoManagerImpl(
    private val kdfProvider: KdfProvider,
    private val secureRandomProvider: SecureRandomProvider,
    private val encryptionProvider: EncryptionProvider,
    private val keyManager: KeyManager
) : CryptoManager {

    companion object {
        const val DEFAULT_ITERATIONS = 300_000
        const val ALGORITHM = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val IV_LENGTH_BYTES = 12
    }

    override fun setupVault(password: SecureCharArray): Pair<KdfConfig, EncryptionConfig> {
        // Generate secure salt, IV, and DEK
        val salt = secureRandomProvider.generateSalt(32)
        val iv = secureRandomProvider.generateIv(IV_LENGTH_BYTES)
        val dek = secureRandomProvider.generateDek(32)
        
        // Derive the Key Encryption Key (KEK)
        val kek = kdfProvider.deriveKek(password, salt, DEFAULT_ITERATIONS)
        
        // Derive the Master Hash for verification
        val masterHash = kdfProvider.deriveMasterHash(kek)

        // Wrap the DEK using the KEK
        val wrappedDek = encryptionProvider.wrapDek(dek, kek, iv)

        val kdfConfig = KdfConfig(
            algorithm = "PBKDF2WithHmacSHA256",
            iterations = DEFAULT_ITERATIONS,
            saltBase64 = Base64.getEncoder().encodeToString(salt),
            masterHashBase64 = Base64.getEncoder().encodeToString(masterHash)
        )

        val encryptionConfig = EncryptionConfig(
            algorithm = ALGORITHM,
            wrappedDekBase64 = Base64.getEncoder().encodeToString(wrappedDek),
            ivBase64 = Base64.getEncoder().encodeToString(iv)
        )

        // Load DEK into KeyManager for the session
        keyManager.loadActiveDek(dek)

        // Wipe intermediate keys and arrays
        kek.wipe()
        masterHash.fill(0)
        wrappedDek.fill(0)
        // Note: salt and iv are not strictly secret, but we wipe them for hygiene
        salt.fill(0)
        iv.fill(0)
        
        return Pair(kdfConfig, encryptionConfig)
    }

    override fun unlockVault(password: SecureCharArray, kdfConfig: KdfConfig, encryptionConfig: EncryptionConfig): CryptoResult<Unit> {
        return try {
            val salt = Base64.getDecoder().decode(kdfConfig.saltBase64)
            val expectedHash = Base64.getDecoder().decode(kdfConfig.masterHashBase64)

            // 1. Derive KEK
            val kek = kdfProvider.deriveKek(password, salt, kdfConfig.iterations)
            
            // 2. Derive Trial Hash
            val actualHash = kdfProvider.deriveMasterHash(kek)
            
            // 3. Constant-time verification
            val isVerified = MessageDigest.isEqual(expectedHash, actualHash)

            if (!isVerified) {
                kek.wipe()
                actualHash.fill(0)
                salt.fill(0)
                expectedHash.fill(0)
                return CryptoResult.Failure(CryptoException.InvalidKey())
            }

            // 4. Unwrap DEK and load into memory
            val wrappedDek = Base64.getDecoder().decode(encryptionConfig.wrappedDekBase64)
            val iv = Base64.getDecoder().decode(encryptionConfig.ivBase64)
            
            val dek = encryptionProvider.unwrapDek(wrappedDek, kek, iv)
            keyManager.loadActiveDek(dek)
            
            // Wipe intermediate keys and arrays
            kek.wipe()
            actualHash.fill(0)
            salt.fill(0)
            expectedHash.fill(0)
            wrappedDek.fill(0)
            iv.fill(0)
            
            CryptoResult.Success(Unit)
        } catch (e: Exception) {
            CryptoResult.Failure(CryptoException.DecryptionFailed("Failed to unlock vault", e))
        }
    }

    override fun lockVault() {
        keyManager.wipeKeys()
    }

    override fun encryptData(plaintext: ByteArray): ByteArray {
        val dek = keyManager.getActiveDek()
        val iv = secureRandomProvider.generateIv(IV_LENGTH_BYTES)
        
        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val keySpec = SecretKeySpec(dek.data, "AES")
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            val ciphertext = cipher.doFinal(plaintext)
            
            // Pack IV + Ciphertext (Tag is automatically appended by GCM)
            val buffer = ByteBuffer.allocate(iv.size + ciphertext.size)
            buffer.put(iv)
            buffer.put(ciphertext)
            return buffer.array()
        } catch (e: Exception) {
            throw CryptoException.DecryptionFailed("Encryption failed", e)
        }
    }

    override fun decryptData(ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < IV_LENGTH_BYTES + (TAG_LENGTH_BITS / 8)) {
            throw CryptoException.DecryptionFailed("Ciphertext too short")
        }
        
        val dek = keyManager.getActiveDek()
        
        try {
            val buffer = ByteBuffer.wrap(ciphertext)
            val iv = ByteArray(IV_LENGTH_BYTES)
            buffer.get(iv)
            
            val actualCiphertext = ByteArray(buffer.remaining())
            buffer.get(actualCiphertext)
            
            val cipher = Cipher.getInstance(ALGORITHM)
            val keySpec = SecretKeySpec(dek.data, "AES")
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            return cipher.doFinal(actualCiphertext)
        } catch (e: Exception) {
            throw CryptoException.DecryptionFailed("Decryption failed", e)
        }
    }
}
