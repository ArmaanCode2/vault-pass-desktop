package com.vaultpass.desktop.domain.models

/**
 * The core domain representation of a Vault Entry (password, secure note, etc.).
 * This model must remain completely pure and agnostic to any underlying data source.
 * It should not contain Room annotations, SQLDelight constructs, or JSON serialization tags.
 */
data class VaultEntry(
    val id: String,
    val title: String,
    val username: String,
    val secret: String, // Kept opaque as it will be decrypted on demand
    val url: String = "",
    val notes: String = "",
    val categoryId: String? = null,
    val isFavorite: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
) {
    /**
     * Prevents accidental logging of sensitive contents (like the encrypted secret or notes).
     */
    override fun toString(): String {
        return "VaultEntry(id='$id', title='$title', isFavorite=$isFavorite)"
    }
}
