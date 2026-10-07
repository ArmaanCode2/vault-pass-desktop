package com.vaultpass.desktop.domain.models

/**
 * The core domain representation of a Vault Entry (password, secure note, etc.).
 * This model must remain completely pure and agnostic to any underlying data source.
 * It should not contain Room annotations, SQLDelight constructs, or JSON serialization tags.
 *
 * [id] is also the entry's syncId: the same on every device the entry is synced to.
 */
data class VaultEntry(
    val id: String,
    val title: String,
    val username: String,
    val secret: String, // Kept opaque as it will be decrypted on demand
    val url: String = "",
    val notes: String = "",
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val customFields: List<CustomField> = emptyList(),
    val history: List<VaultEntryHistory> = emptyList(),
    val isFavorite: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** A placeholder for a row that could not be decrypted: never edited, exported or synced. */
    val isCorrupted: Boolean = false
) {
    /**
     * Prevents accidental logging of sensitive contents (like the encrypted secret or notes).
     */
    override fun toString(): String {
        return "VaultEntry(id='$id', title='$title', isFavorite=$isFavorite)"
    }
}
