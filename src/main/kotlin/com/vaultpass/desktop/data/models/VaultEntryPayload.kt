package com.vaultpass.desktop.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Historical snapshot of a vault entry embedded in the payload.
 */
@Serializable
data class VaultEntryHistoryDto(
    val previousSecret: String,
    val changedAt: Long,
    val changeReason: String? = null
)

/**
 * Polymorphic serialization wrapper for encrypted vault payloads.
 * This class abstracts the sensitive data away from the SQLite database.
 */
@Serializable
sealed class VaultEntryPayload {

    @Serializable
    @SerialName("PASSWORD")
    data class PasswordPayload(
        val title: String,
        val username: String,
        val secret: String,
        val url: String = "",
        val notes: String = "",
        val isArchived: Boolean = false,
        val history: List<VaultEntryHistoryDto> = emptyList()
    ) : VaultEntryPayload()
    
    // Future expansion: SecureNotePayload, CreditCardPayload, IdentityPayload
}
