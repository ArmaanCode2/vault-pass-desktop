package com.vaultpass.desktop.domain.exportimport

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles AES-256-GCM encryption and decryption for Android-compatible .vpex files.
 * Uses PBKDF2WithHmacSHA256 with 100,000 iterations to derive the 256-bit key.
 */
object VpexCryptoManager {
    private const val KEY_LENGTH = 256
    private const val ITERATIONS = 100000
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    fun encrypt(jsonPayload: String, password: String): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)

        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val derivedKey = deriveKey(password, salt)
        val secretKey = SecretKeySpec(derivedKey, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(jsonPayload.toByteArray(Charsets.UTF_8))

        val binaryPayload = salt + iv + ciphertext
        return Base64.getEncoder().encodeToString(binaryPayload)
    }

    fun decrypt(vpexContent: String, password: String): String {
        val rawBytes = try {
            val cleaned = vpexContent.trim().replace("\r", "").replace("\n", "")
            Base64.getDecoder().decode(cleaned)
        } catch (e: Exception) {
            throw IllegalArgumentException("Corrupted .vpex file: Invalid Base64 encoding.", e)
        }

        if (rawBytes.size <= 16 + 12) {
            throw IllegalArgumentException("Corrupted .vpex file: Payload too short.")
        }

        val salt = rawBytes.copyOfRange(0, 16)
        val derivedKey = deriveKey(password, salt)
        val secretKey = SecretKeySpec(derivedKey, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        // Try standard 12-byte IV first
        try {
            val iv = rawBytes.copyOfRange(16, 16 + 12)
            val ciphertext = rawBytes.copyOfRange(16 + 12, rawBytes.size)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val plaintextBytes = cipher.doFinal(ciphertext)
            return String(plaintextBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            // Fallback to legacy 16-byte IV if file size allows
            if (rawBytes.size > 16 + 16) {
                try {
                    val iv16 = rawBytes.copyOfRange(16, 16 + 16)
                    val ciphertext16 = rawBytes.copyOfRange(16 + 16, rawBytes.size)
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv16))
                    val plaintextBytes = cipher.doFinal(ciphertext16)
                    return String(plaintextBytes, Charsets.UTF_8)
                } catch (e2: Exception) {
                    throw IllegalArgumentException("Decryption failed: Incorrect password or corrupted payload.", e2)
                }
            }
            throw IllegalArgumentException("Decryption failed: Incorrect password or corrupted payload.", e)
        }
    }
}
