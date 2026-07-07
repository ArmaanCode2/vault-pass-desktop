package com.vaultpass.desktop.data.models

/**
 * Data layer representation of the SQLite vault_entry table.
 * Contains both the AES-GCM encrypted payload and plaintext metadata.
 */
data class VaultEntity(
    val id: String,
    val type: String,
    val encryptedPayload: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
    val isFavorite: Boolean,
    val isDeleted: Boolean,
    val deletedAt: Long?,
    val syncVersion: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VaultEntity

        if (id != other.id) return false
        if (type != other.type) return false
        if (!encryptedPayload.contentEquals(other.encryptedPayload)) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (isFavorite != other.isFavorite) return false
        if (isDeleted != other.isDeleted) return false
        if (deletedAt != other.deletedAt) return false
        if (syncVersion != other.syncVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + isFavorite.hashCode()
        result = 31 * result + isDeleted.hashCode()
        result = 31 * result + (deletedAt?.hashCode() ?: 0)
        result = 31 * result + syncVersion
        return result
    }
}
