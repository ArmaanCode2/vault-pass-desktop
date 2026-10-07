package com.vaultpass.desktop.domain.exportimport

import com.vaultpass.desktop.domain.models.VaultEntry

data class ImportParseResult(
    val validEntries: List<VaultEntry>,
    val invalidCount: Int,
    val duplicateCount: Int,
    val totalCount: Int,
    val duplicateKeys: Set<String> = emptySet()
)
