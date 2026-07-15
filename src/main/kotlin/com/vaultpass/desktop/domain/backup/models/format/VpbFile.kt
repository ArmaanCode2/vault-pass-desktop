package com.vaultpass.desktop.domain.backup.models.format

import kotlinx.serialization.Serializable

/**
 * The root envelope of a VaultPass Backup (.vpb) file.
 * This structure separates the unencrypted metadata (for validation/routing)
 * from the highly encrypted sensitive payload.
 */
@Serializable
data class VpbFile(
    val header: BackupHeader,
    val encryptedPayloadBase64: String
)
