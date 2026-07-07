package com.vaultpass.desktop.data

import com.vaultpass.desktop.domain.crypto.KdfProvider
import com.vaultpass.desktop.domain.crypto.SecureRandomProvider
import com.vaultpass.desktop.domain.metadata.MetadataRepository
import com.vaultpass.desktop.domain.models.KdfConfig
import com.vaultpass.desktop.domain.security.AuthenticationRepository
import com.vaultpass.desktop.domain.security.toSecureCharArray
import java.security.MessageDigest
import java.util.Base64

import com.vaultpass.desktop.domain.crypto.EncryptionProvider
import com.vaultpass.desktop.domain.crypto.KeyManager
import com.vaultpass.desktop.domain.models.EncryptionConfig

import com.vaultpass.desktop.domain.crypto.CryptoManager
import com.vaultpass.desktop.domain.crypto.CryptoResult

/**
 * Concrete implementation of AuthenticationRepository for Milestone 4.4.
 * Delegates cryptographic workflows completely to the CryptoManager.
 */
class AuthenticationRepositoryImpl(
    private val metadataRepository: MetadataRepository,
    private val cryptoManager: CryptoManager
) : AuthenticationRepository {

    override suspend fun saveMasterPassword(password: String): Boolean {
        return try {
            val securePassword = password.toSecureCharArray()
            
            // Delegate setup entirely to CryptoManager
            val (kdfConfig, encryptionConfig) = cryptoManager.setupVault(securePassword)

            val metadata = metadataRepository.getMetadata() ?: metadataRepository.initializeMetadata()
            val updatedMetadata = metadata.copy(
                kdfConfig = kdfConfig,
                encryptionConfig = encryptionConfig,
                initialized = true
            )
            metadataRepository.saveMetadata(updatedMetadata)

            // Wipe intermediate password
            securePassword.wipe()
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun verifyMasterPassword(password: String): Boolean {
        return try {
            val metadata = metadataRepository.getMetadata() ?: return false
            val kdfConfig = metadata.kdfConfig ?: return false
            val encryptionConfig = metadata.encryptionConfig ?: return false

            val securePassword = password.toSecureCharArray()
            
            val result = cryptoManager.unlockVault(securePassword, kdfConfig, encryptionConfig)

            securePassword.wipe()
            
            result is CryptoResult.Success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
