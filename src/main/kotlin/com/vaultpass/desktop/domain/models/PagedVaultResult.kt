package com.vaultpass.desktop.domain.models

data class PagedVaultResult(
    val items: List<VaultEntry>,
    val totalItems: Int,
    val totalPages: Int,
    val currentPage: Int
)
