package com.vaultpass.desktop.domain.backup.models.format

import kotlinx.serialization.Serializable

@Serializable
data class CategoryBackup(
    val id: String,
    val name: String
)

@Serializable
data class TagBackup(
    val id: String,
    val name: String
)
