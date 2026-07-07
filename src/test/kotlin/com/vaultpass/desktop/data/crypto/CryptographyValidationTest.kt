package com.vaultpass.desktop.data.crypto

import com.vaultpass.desktop.domain.crypto.CryptoException
import com.vaultpass.desktop.domain.crypto.CryptoResult
import com.vaultpass.desktop.domain.models.EncryptionConfig
import com.vaultpass.desktop.domain.models.KdfConfig
import com.vaultpass.desktop.domain.security.SecureCharArray
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CryptographyValidationTest {

    private val kdfProvider = PBKDF2KdfProvider()
    private val secureRandomProvider = SecureRandomProviderImpl()
    private val encryptionProvider = AESGcmEncryptionProvider()
    private val keyManager = InMemoryKeyManager()

    private val cryptoManager = CryptoManagerImpl(
        kdfProvider,
        secureRandomProvider,
        encryptionProvider,
        keyManager
    )

    private fun setupVault(password: String): Pair<KdfConfig, EncryptionConfig> {
        val securePwd = password.toSecureCharArray()
        val config = cryptoManager.setupVault(securePwd)
        securePwd.wipe()
        return config
    }

    private fun String.toSecureCharArray(): SecureCharArray {
        return SecureCharArray(this.toCharArray())
    }

    @Test
    fun testValidUnlock() {
        val (kdfConfig, encConfig) = setupVault("correct-password")
        val pwd = "correct-password".toSecureCharArray()
        val result = cryptoManager.unlockVault(pwd, kdfConfig, encConfig)
        pwd.wipe()
        assertTrue(result is CryptoResult.Success)
    }

    @Test
    fun testWrongPassword() {
        val (kdfConfig, encConfig) = setupVault("correct-password")
        val pwd = "wrong-password".toSecureCharArray()
        val result = cryptoManager.unlockVault(pwd, kdfConfig, encConfig)
        pwd.wipe()
        
        // Ensure verification fails safely
        assertTrue(result is CryptoResult.Failure)
        assertTrue(result.error is CryptoException.InvalidKey)
    }

    @Test
    fun testCorruptedSalt() {
        val (kdfConfig, encConfig) = setupVault("password")
        
        // Corrupt salt
        val originalSalt = Base64.getDecoder().decode(kdfConfig.saltBase64)
        originalSalt[0] = (originalSalt[0] + 1).toByte()
        
        val badKdf = kdfConfig.copy(saltBase64 = Base64.getEncoder().encodeToString(originalSalt))
        
        val pwd = "password".toSecureCharArray()
        val result = cryptoManager.unlockVault(pwd, badKdf, encConfig)
        pwd.wipe()

        // Changing the salt changes the derived KEK, which will fail the Master Hash verification
        assertTrue(result is CryptoResult.Failure)
        assertTrue(result.error is CryptoException.InvalidKey)
    }

    @Test
    fun testCorruptedEncryptedDEK() {
        val (kdfConfig, encConfig) = setupVault("password")
        
        val dekBytes = Base64.getDecoder().decode(encConfig.wrappedDekBase64)
        dekBytes[dekBytes.size / 2] = (dekBytes[dekBytes.size / 2] + 1).toByte()
        
        val badEnc = encConfig.copy(wrappedDekBase64 = Base64.getEncoder().encodeToString(dekBytes))
        
        val pwd = "password".toSecureCharArray()
        val result = cryptoManager.unlockVault(pwd, kdfConfig, badEnc)
        pwd.wipe()

        // Fails during AES-GCM unwrapping
        assertTrue(result is CryptoResult.Failure)
        assertTrue(result.error is CryptoException.DecryptionFailed)
    }

    @Test
    fun testCorruptedIV() {
        val (kdfConfig, encConfig) = setupVault("password")
        
        val ivBytes = Base64.getDecoder().decode(encConfig.ivBase64)
        ivBytes[0] = (ivBytes[0] + 1).toByte()
        
        val badEnc = encConfig.copy(ivBase64 = Base64.getEncoder().encodeToString(ivBytes))
        
        val pwd = "password".toSecureCharArray()
        val result = cryptoManager.unlockVault(pwd, kdfConfig, badEnc)
        pwd.wipe()

        // Fails during AES-GCM unwrapping because IV is part of GCM auth
        assertTrue(result is CryptoResult.Failure)
        assertTrue(result.error is CryptoException.DecryptionFailed)
    }

    @Test
    fun testCorruptedAuthenticationTag() {
        val (kdfConfig, encConfig) = setupVault("password")
        
        val dekBytes = Base64.getDecoder().decode(encConfig.wrappedDekBase64)
        // Corrupt the last byte (part of the 16-byte authentication tag)
        dekBytes[dekBytes.size - 1] = (dekBytes[dekBytes.size - 1] + 1).toByte()
        
        val badEnc = encConfig.copy(wrappedDekBase64 = Base64.getEncoder().encodeToString(dekBytes))
        
        val pwd = "password".toSecureCharArray()
        val result = cryptoManager.unlockVault(pwd, kdfConfig, badEnc)
        pwd.wipe()

        // Fails natively throwing AEADBadTagException, caught as DecryptionFailed
        assertTrue(result is CryptoResult.Failure)
        assertTrue(result.error is CryptoException.DecryptionFailed)
    }

    @Test
    fun testEncryptDecryptArbitraryData() {
        val (kdfConfig, encConfig) = setupVault("password")
        val pwd = "password".toSecureCharArray()
        cryptoManager.unlockVault(pwd, kdfConfig, encConfig)
        pwd.wipe()

        val plaintext = "Secret Message".toByteArray()
        val ciphertext = cryptoManager.encryptData(plaintext)
        val decrypted = cryptoManager.decryptData(ciphertext)

        assertTrue(plaintext.contentEquals(decrypted))
    }

    @Test
    fun testCorruptedArbitraryDataTag() {
        val (kdfConfig, encConfig) = setupVault("password")
        val pwd = "password".toSecureCharArray()
        cryptoManager.unlockVault(pwd, kdfConfig, encConfig)
        pwd.wipe()

        val plaintext = "Secret Message".toByteArray()
        val ciphertext = cryptoManager.encryptData(plaintext)
        
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1] + 1).toByte()
        
        assertFailsWith<CryptoException.DecryptionFailed> {
            cryptoManager.decryptData(ciphertext)
        }
    }
}
