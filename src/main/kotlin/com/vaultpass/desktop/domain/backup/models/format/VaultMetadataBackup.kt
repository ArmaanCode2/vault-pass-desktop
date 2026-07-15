package com.vaultpass.desktop.domain.backup.models.format

import kotlinx.serialization.Serializable

/**
 * Encrypted backup of vault-level settings and metadata.
 */
@Serializable
data class VaultMetadataBackup(
    val name: String,
    val lastModified: Long,
    val securityScore: Double?
)
