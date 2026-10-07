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
 * A user-defined key/value pair embedded in the payload.
 */
@Serializable
data class CustomFieldDto(
    val key: String,
    val value: String
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
        val category: String? = null,
        val tags: List<String> = emptyList(),
        val isArchived: Boolean = false,
        val history: List<VaultEntryHistoryDto> = emptyList(),
        val customFields: List<CustomFieldDto> = emptyList()
    ) : VaultEntryPayload()
    
    // Future expansion: SecureNotePayload, CreditCardPayload, IdentityPayload
}
