package com.vaultpass.desktop.data.repository

import com.vaultpass.desktop.data.models.VaultEntity
import com.vaultpass.desktop.data.models.VaultEntryPayload
import com.vaultpass.desktop.domain.crypto.CryptoManager
import com.vaultpass.desktop.domain.models.VaultEntry
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
            // History mapping deferred to CRUD phase
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
            isDeleted = false,
            deletedAt = null,
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
        // Step 1 & 2: Decrypt and Deserialize
        val decryptedBytes = cryptoManager.decryptData(entity.encryptedPayload)
        val jsonString = String(decryptedBytes, Charsets.UTF_8)
        val payload = json.decodeFromString<VaultEntryPayload>(jsonString)
        
        // Step 3: Map
        return when (payload) {
            is VaultEntryPayload.PasswordPayload -> {
                VaultEntry(
                    id = entity.id,
                    title = payload.title,
                    username = payload.username,
                    secret = payload.secret,
                    url = payload.url,
                    notes = payload.notes,
                    categoryId = null,
                    isFavorite = entity.isFavorite,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt
                )
            }
        }
    }
}
