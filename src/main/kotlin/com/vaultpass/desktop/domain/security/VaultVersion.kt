package com.vaultpass.desktop.domain.security

/**
 * The single source of truth for the application's target vault version requirements.
 * Used to avoid hardcoding version numbers in repositories or ViewModels.
 */
object VaultVersion {
    const val CURRENT_FORMAT_VERSION = 1
    const val CURRENT_KDF_VERSION = 1
    const val CURRENT_ENCRYPTION_VERSION = 1
    
    const val TARGET_KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val TARGET_KDF_ITERATIONS = 300_000
    const val TARGET_SALT_LENGTH = 32
    
    const val TARGET_CIPHER = "AES/GCM/NoPadding"
    const val TARGET_KEY_SIZE = 256
    const val TARGET_IV_LENGTH = 12
    const val TARGET_AUTH_TAG_LENGTH = 128
}
