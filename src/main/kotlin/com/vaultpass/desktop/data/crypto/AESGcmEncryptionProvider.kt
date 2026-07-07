package com.vaultpass.desktop.data.crypto

import com.vaultpass.desktop.domain.crypto.CryptoException
import com.vaultpass.desktop.domain.crypto.EncryptionProvider
import com.vaultpass.desktop.domain.security.SecureByteArray
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Concrete implementation of EncryptionProvider using AES-GCM.
 */
class AESGcmEncryptionProvider : EncryptionProvider {

    companion object {
        const val ALGORITHM = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }

    override fun wrapDek(dek: SecureByteArray, kek: SecureByteArray, iv: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val keySpec = SecretKeySpec(kek.data, "AES")
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            return cipher.doFinal(dek.data)
        } catch (e: Exception) {
            throw CryptoException.DecryptionFailed("Failed to encrypt DEK", e)
        }
    }

    override fun unwrapDek(wrappedDek: ByteArray, kek: SecureByteArray, iv: ByteArray): SecureByteArray {
        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val keySpec = SecretKeySpec(kek.data, "AES")
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val rawPlaintext = cipher.doFinal(wrappedDek)
            val secureDek = SecureByteArray(rawPlaintext.clone())
            
            rawPlaintext.fill(0) // Zero out raw array
            
            return secureDek
        } catch (e: Exception) {
            throw CryptoException.DecryptionFailed("Failed to decrypt DEK", e)
        }
    }
}
