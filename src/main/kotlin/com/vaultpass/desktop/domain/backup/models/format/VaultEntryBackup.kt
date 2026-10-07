package com.vaultpass.desktop.domain.backup.models.format

import kotlinx.serialization.Serializable

/**
 * A custom field of a backed-up entry.
 */
@Serializable
data class CustomFieldBackup(
    val key: String,
    val value: String
)

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
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val customFields: List<CustomFieldBackup> = emptyList()
)
