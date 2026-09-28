package com.vaultpass.desktop.domain.exportimport

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidVpexInteroperabilityTest {

    private val testPassword = "CrossPlatformTestPass2026!"
    private val sampleJson = """{"version":1,"entries":[{"id":1001,"title":"GitHub Test","username":"octocat","password":"SecretGitHubPassword123!","website":"https://github.com","notes":"Test entry notes","category":"Development","tags":["git","coding"],"customFields":[{"key":"PIN","value":"4321"}],"isFavorite":true,"timestamp":1700000000000,"isDeleted":false,"deletedAt":null}]}"""

    @Test
    fun testAndroidDomainSeparatedVpexDecryption() {
        // Encrypt with Android's exact specification:
        // 1. PBKDF2WithHmacSHA256 (100k, 256)
        // 2. HMAC-SHA256 with "vaultpass-kek-v1"
        // 3. AES/GCM/NoPadding (12-byte IV, 128-bit tag)
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)

        val spec = PBEKeySpec(testPassword.toCharArray(), salt, 100000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val rootKey = factory.generateSecret(spec).encoded

        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(rootKey, "HmacSHA256"))
        val kek = mac.doFinal("vaultpass-kek-v1".toByteArray(Charsets.UTF_8))

        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(sampleJson.toByteArray(Charsets.UTF_8))

        val androidPayload = Base64.getEncoder().encodeToString(salt + iv + ciphertext)

        // Desktop VpexCryptoManager must decrypt this successfully
        val decrypted = VpexCryptoManager.decrypt(androidPayload, testPassword)
        assertEquals(sampleJson, decrypted)
    }

    @Test
    fun testDesktopVpexExportMatchesAndroidFormat() {
        val desktopExport = VpexCryptoManager.encrypt(sampleJson, testPassword)
        assertNotNull(desktopExport)

        val rawBytes = Base64.getDecoder().decode(desktopExport)
        assertTrue(rawBytes.size > 28, "Payload must contain at least 16 salt + 12 IV + ciphertext")

        // Desktop must be able to decrypt it
        val roundTrip = VpexCryptoManager.decrypt(desktopExport, testPassword)
        assertEquals(sampleJson, roundTrip)
    }

    @Test
    fun testLegacyDesktopPayloadFallbackDecryption() {
        // Encrypt using legacy raw PBKDF2 without HMAC domain separation
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)

        val spec = PBEKeySpec(testPassword.toCharArray(), salt, 100000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val legacyKey = factory.generateSecret(spec).encoded

        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(legacyKey, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(sampleJson.toByteArray(Charsets.UTF_8))

        val legacyPayload = Base64.getEncoder().encodeToString(salt + iv + ciphertext)

        // Desktop VpexCryptoManager must decrypt this through fallback
        val decrypted = VpexCryptoManager.decrypt(legacyPayload, testPassword)
        assertEquals(sampleJson, decrypted)
    }

    @Test
    fun testIncorrectPasswordFails() {
        val desktopExport = VpexCryptoManager.encrypt(sampleJson, testPassword)
        assertThrows<IllegalArgumentException> {
            VpexCryptoManager.decrypt(desktopExport, "WrongPassword!")
        }
    }
}
