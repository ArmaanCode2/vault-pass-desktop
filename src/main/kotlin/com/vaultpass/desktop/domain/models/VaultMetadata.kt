package com.vaultpass.desktop.domain.models

import kotlinx.serialization.Serializable

/**
 * The permanent Vault Metadata model.
 * 
 * This is the source of truth for the vault's existence and versioning,
 * decoupled from the physical database file to allow advanced synchronization
 * and seamless backwards compatibility.
 */
@Serializable
data class VaultMetadata(
    val initialized: Boolean,
    val vaultVersion: Int,
    val metadataVersion: Int,
    val kdfVersion: Int,
    val encryptionVersion: Int,
    val settingsVersion: Int,
    val migrationVersion: Int,
    val createdAt: Long,
    val lastOpenedAt: Long,
    val createdWithAppVersion: String,
    val lastOpenedAppVersion: String
)
