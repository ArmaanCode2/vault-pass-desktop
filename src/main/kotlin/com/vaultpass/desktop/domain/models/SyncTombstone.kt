package com.vaultpass.desktop.domain.models

/** Records that the entry with [syncId] was deleted for good at [deletedAt]. */
data class SyncTombstone(
    val syncId: String,
    val deletedAt: Long
)
