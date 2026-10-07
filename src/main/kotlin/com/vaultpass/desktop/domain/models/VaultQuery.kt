package com.vaultpass.desktop.domain.models

data class VaultQuery(
    val searchQuery: String = "",
    val sortField: SortField = SortField.TITLE,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val filterType: EntryType? = null,
    val isFavorite: Boolean? = null,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val isArchived: Boolean = false,
    val isDeleted: Boolean = false,
    val page: Int = 1,
    val pageSize: Int = 50
)
