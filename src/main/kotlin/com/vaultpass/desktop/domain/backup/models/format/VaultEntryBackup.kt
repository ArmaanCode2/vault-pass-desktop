package com.vaultpass.desktop.domain.backup.models.format

import kotlinx.serialization.Serializable

/**
 * Encrypted backup of a single password/secret entry.
 */
@Serializable
data class VaultEntryBackup(
    val id: String,
    val title: String,
    val username: String,
    val secret: String,
    val url: String,
    val notes: String,
    val isFavorite: Boolean,
    val categoryId: String?,
    val tagIds: List<String>,
    val createdAt: Long,
    val updatedAt: Long
)
