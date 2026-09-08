package com.vaultpass.desktop.domain.models

data class VaultEntryHistory(
    val previousSecret: String,
    val changedAt: Long,
    val changeReason: String? = null
)
