package com.vaultpass.desktop.domain.backup.models.format

import kotlinx.serialization.Serializable

/**
 * The core encrypted payload of the backup.
 * This entire object is serialized and encrypted before being embedded in [VpbFile].
 */
@Serializable
data class BackupPayload(
    val vaultMetadata: VaultMetadataBackup,
    val entries: List<VaultEntryBackup>,
    val categories: List<CategoryBackup>,
    val tags: List<TagBackup>
)
