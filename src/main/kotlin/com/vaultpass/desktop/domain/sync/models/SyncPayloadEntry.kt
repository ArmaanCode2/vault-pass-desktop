package com.vaultpass.desktop.domain.sync.models

import com.vaultpass.desktop.domain.models.VaultEntry
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SyncPayloadEntry(
    val id: Int = 0,
    val title: String = "",
    val username: String = "",
    val password: String? = null,
    val secret: String? = null,
    val website: String? = null,
    val url: String? = null,
    val notes: String = "",
    val category: String = "Personal",
    val tags: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val timestamp: Long? = null,
    val updatedAt: Long? = null
) {
    fun toDomainVaultEntry(): VaultEntry {
        val resolvedPassword = password ?: secret ?: ""
        val resolvedUrl = website ?: url ?: ""
        val resolvedTimestamp = timestamp ?: updatedAt ?: 0L
        val now = if (resolvedTimestamp > 0) resolvedTimestamp else System.currentTimeMillis()
        return VaultEntry(
            id = UUID.randomUUID().toString(),
            title = title,
            username = username,
            secret = resolvedPassword,
            url = resolvedUrl,
            notes = notes,
            category = category.takeIf { it.isNotBlank() },
            tags = tags,
            history = emptyList(),
            isFavorite = isFavorite,
            isDeleted = false,
            deletedAt = null,
            createdAt = now,
            updatedAt = now
        )
    }

    companion object {
        fun fromDomainVaultEntry(entry: VaultEntry): SyncPayloadEntry {
            return SyncPayloadEntry(
                id = 0,
                title = entry.title,
                username = entry.username,
                password = entry.secret,
                secret = entry.secret,
                website = entry.url,
                url = entry.url,
                notes = entry.notes,
                category = entry.category ?: "Personal",
                tags = entry.tags,
                isFavorite = entry.isFavorite,
                timestamp = entry.updatedAt,
                updatedAt = entry.updatedAt
            )
        }
    }
}
