package com.vaultpass.desktop.domain.export

import com.vaultpass.desktop.domain.models.VaultMetadata

/**
 * Represents the cleartext metadata of an exported VPEX file.
 * Kept strictly unencrypted so that importing clients can validate versions
 * before attempting decryption.
 */
data class VpexManifest(
    val vpexVersion: Int,
    val metadata: VaultMetadata,
    val createdAt: Long,
    val platform: String // e.g., "Desktop", "Android"
)

/**
 * The root container for a VaultPass Export (.vpex) file.
 * Guarantees compatibility between Desktop and Android by enforcing a shared structure.
 */
data class VpexContainer(
    val manifest: VpexManifest,
    val encryptedPayload: String, // Base64 encoded payload
    val integrityMac: String      // HMAC signature to verify data integrity
)
