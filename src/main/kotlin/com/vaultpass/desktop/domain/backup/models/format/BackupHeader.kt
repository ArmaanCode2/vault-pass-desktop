package com.vaultpass.desktop.domain.backup.models.format

import kotlinx.serialization.Serializable
import com.vaultpass.desktop.domain.backup.models.BackupVersion

/**
 * The unencrypted metadata envelope attached to every backup.
 * MUST NOT contain sensitive information like passwords, notes, categories, or URLs.
 */
@Serializable
data class BackupHeader(
    val magicBytes: String = "VPB",
    val backupVersion: BackupVersion,
    val applicationVersion: String,
    val timestamp: Long,
    val vaultId: String,
    val vaultSchemaVersion: Int,
    val encryptionAlgorithm: String = "AES-GCM-256",
    val kdfAlgorithm: String = "PBKDF2",
    val iterations: Int,
    val salt: String, // Base64 encoded KDF salt
    val masterHashBase64: String, // Base64 expected master hash for verification
    val wrappedDekBase64: String, // Base64 wrapped DEK
    val iv: String    // Base64 encoded Initialization Vector for payload
)
