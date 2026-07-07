package com.vaultpass.desktop.domain.models

data class VaultEntryHistory(
    val previousEntry: VaultEntry,
    val changedAt: Long,
    val changeReason: String? = null
)
