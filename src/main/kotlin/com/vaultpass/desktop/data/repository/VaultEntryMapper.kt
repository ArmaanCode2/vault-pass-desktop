package com.vaultpass.desktop.data.repository

import com.vaultpass.desktop.data.models.CustomFieldDto
import com.vaultpass.desktop.data.models.VaultEntity
import com.vaultpass.desktop.data.models.VaultEntryHistoryDto
import com.vaultpass.desktop.data.models.VaultEntryPayload
import com.vaultpass.desktop.domain.crypto.CryptoManager
import com.vaultpass.desktop.domain.models.CustomField
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.domain.models.VaultEntryHistory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object VaultEntryMapper {
    
    // Explicitly configure JSON to use a robust discriminator for cross-platform compatibility
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    /**
     * Maps a pure Domain VaultEntry to an SQLite VaultEntity.
     * 1. Maps Domain to `@Serializable` Payload
     * 2. Serializes to JSON String (transient memory)
     * 3. Encrypts with CryptoManager (DEK)
     * 4. Wraps in VaultEntity
     */
    fun toEntity(domain: VaultEntry, cryptoManager: CryptoManager): VaultEntity {
        // Step 1: Serialize to Payload DTO
        val payload = VaultEntryPayload.PasswordPayload(
            title = domain.title,
            username = domain.username,
            secret = domain.secret,
            url = domain.url,
            notes = domain.notes,
            category = domain.category,
            tags = domain.tags,
            history = domain.history.map {
                VaultEntryHistoryDto(
                    previousSecret = it.previousSecret,
                    changedAt = it.changedAt,
                    changeReason = it.changeReason
                )
            },
            customFields = domain.customFields.map { CustomFieldDto(key = it.key, value = it.value) }
        )
        
        // Step 2 & 3: Serialize and Encrypt
        val jsonString = json.encodeToString<VaultEntryPayload>(payload)
        val ciphertext = cryptoManager.encryptData(jsonString.toByteArray(Charsets.UTF_8))
        
        // Step 4: Wrap
        return VaultEntity(
            id = domain.id,
            type = "PASSWORD", // Hardcoded for this milestone
            encryptedPayload = ciphertext,
            createdAt = domain.createdAt,
            updatedAt = domain.updatedAt,
            isFavorite = domain.isFavorite,
            isDeleted = domain.isDeleted,
            deletedAt = domain.deletedAt,
            syncVersion = 1
        )
    }

    /**
     * Maps an SQLite VaultEntity back to a pure Domain VaultEntry.
     * 1. Decrypts with CryptoManager (DEK)
     * 2. Deserializes from JSON
     * 3. Maps back to Domain
     */
    fun toDomain(entity: VaultEntity, cryptoManager: CryptoManager): VaultEntry {
        try {
            val decryptedBytes = cryptoManager.decryptData(entity.encryptedPayload)
            try {
                val jsonString = String(decryptedBytes, Charsets.UTF_8)
                val payload = json.decodeFromString<VaultEntryPayload>(jsonString)
                return when (payload) {
                    is VaultEntryPayload.PasswordPayload -> {
                        VaultEntry(
                            id = entity.id,
                            title = payload.title,
                            username = payload.username,
                            secret = payload.secret,
                            url = payload.url,
                            notes = payload.notes,
                            category = payload.category,
                            tags = payload.tags,
                            customFields = payload.customFields.map { CustomField(key = it.key, value = it.value) },
                            history = payload.history.map {
                                VaultEntryHistory(
                                    previousSecret = it.previousSecret,
                                    changedAt = it.changedAt,
                                    changeReason = it.changeReason
                                )
                            },
                            isFavorite = entity.isFavorite,
                            isDeleted = entity.isDeleted,
                            deletedAt = entity.deletedAt,
                            createdAt = entity.createdAt,
                            updatedAt = entity.updatedAt
                        )
                    }
                }
            } catch (e: Exception) {
                println("[TRACE] Mapper JSON Error: ${e.message}")
                throw e
            }
        } catch (e: Exception) {
            println("[TRACE] Mapper DECRYPT Error: ${e.message} | CryptoMgr: ${System.identityHashCode(cryptoManager)}")
            throw e
        }
        
    }
}

