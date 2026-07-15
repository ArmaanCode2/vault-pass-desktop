package com.vaultpass.desktop.domain.backup.models

/**
 * Descriptive information required to validate and route a backup before decryption.
 */
data class BackupMetadata(
    val timestamp: Long,
    val vaultId: String,
    val version: BackupVersion,
    val format: BackupFormat,
    val salt: ByteArray?,
    val encryptionParams: Map<String, String> = emptyMap()
)
