package com.vaultpass.desktop.data.crypto

import com.vaultpass.desktop.domain.crypto.SecureRandomProvider
import com.vaultpass.desktop.domain.security.SecureByteArray
import java.security.SecureRandom

/**
 * Concrete implementation of SecureRandomProvider using java.security.SecureRandom.
 */
class SecureRandomProviderImpl : SecureRandomProvider {

    private val secureRandom = SecureRandom()

    override fun generateSalt(lengthBytes: Int): ByteArray {
        val salt = ByteArray(lengthBytes)
        secureRandom.nextBytes(salt)
        return salt
    }

    override fun generateIv(lengthBytes: Int): ByteArray {
        val iv = ByteArray(lengthBytes)
        secureRandom.nextBytes(iv)
        return iv
    }

    override fun generateDek(lengthBytes: Int): SecureByteArray {
        val dek = ByteArray(lengthBytes)
        secureRandom.nextBytes(dek)
        return SecureByteArray(dek)
    }
}
