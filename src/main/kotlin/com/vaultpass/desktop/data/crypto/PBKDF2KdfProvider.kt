package com.vaultpass.desktop.data.crypto

import com.vaultpass.desktop.domain.crypto.CryptoException
import com.vaultpass.desktop.domain.crypto.KdfProvider
import com.vaultpass.desktop.domain.security.SecureByteArray
import com.vaultpass.desktop.domain.security.SecureCharArray
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Concrete implementation of KdfProvider using PBKDF2-HMAC-SHA256.
 */
class PBKDF2KdfProvider : KdfProvider {

    companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val KEY_LENGTH_BITS = 256
    }

    override fun deriveKek(password: SecureCharArray, salt: ByteArray, iterations: Int): SecureByteArray {
        try {
            val spec = PBEKeySpec(password.data, salt, iterations, KEY_LENGTH_BITS)
            val factory = SecretKeyFactory.getInstance(ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            spec.clearPassword() // Explicitly clear the spec's internal copy
            
            val secureKey = SecureByteArray(keyBytes.clone())
            keyBytes.fill(0) // Zero out the raw JVM array before GC
            return secureKey
        } catch (e: Exception) {
            throw CryptoException.KeyDerivationFailed("Failed to derive KEK using PBKDF2", e)
        }
    }

    override fun deriveMasterHash(kek: SecureByteArray): ByteArray {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(kek.data)
        } catch (e: Exception) {
            throw CryptoException.KeyDerivationFailed("Failed to derive Master Hash", e)
        }
    }
}
