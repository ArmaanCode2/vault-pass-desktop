package com.vaultpass.desktop.domain.models

/**
 * The core domain representation of a Vault Category (e.g. Work, Personal).
 * This model must remain completely pure and agnostic to any underlying data source.
 */
data class VaultCategory(
    val id: String,
    val name: String,
    val colorHex: String = "#FFFFFF",
    val createdAt: Long,
    val updatedAt: Long
)
