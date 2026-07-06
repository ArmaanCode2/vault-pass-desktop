package com.vaultpass.desktop.data

import com.vaultpass.desktop.domain.metadata.MetadataRepository
import com.vaultpass.desktop.domain.models.VaultMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Implementation of MetadataRepository that reads/writes JSON to ~/.vaultpass/metadata.json
 */
class MetadataRepositoryImpl : MetadataRepository {
    
    private val vaultDirectory = File(System.getProperty("user.home"), ".vaultpass")
    private val metadataFile = File(vaultDirectory, "metadata.json")
    
    private val json = Json { 
        prettyPrint = true 
        ignoreUnknownKeys = true
    }

    override suspend fun getMetadata(): VaultMetadata? = withContext(Dispatchers.IO) {
        if (!metadataFile.exists()) {
            return@withContext null
        }
        
        return@withContext try {
            val content = metadataFile.readText()
            json.decodeFromString<VaultMetadata>(content)
        } catch (e: Exception) {
            // In a production app, we would handle this with VaultError.
            null
        }
    }

    override suspend fun saveMetadata(metadata: VaultMetadata) = withContext(Dispatchers.IO) {
        if (!vaultDirectory.exists()) {
            vaultDirectory.mkdirs()
        }
        
        val content = json.encodeToString(metadata)
        metadataFile.writeText(content)
    }

    override suspend fun initializeMetadata(): VaultMetadata {
        val initialMetadata = VaultMetadata(
            initialized = true,
            vaultVersion = 1,
            metadataVersion = 1,
            kdfVersion = 1,
            encryptionVersion = 1,
            settingsVersion = 1,
            migrationVersion = 1,
            createdAt = System.currentTimeMillis(),
            lastOpenedAt = System.currentTimeMillis(),
            createdWithAppVersion = "1.0.0",
            lastOpenedAppVersion = "1.0.0"
        )
        saveMetadata(initialMetadata)
        return initialMetadata
    }
}
