package com.vaultpass.desktop.data

import com.vaultpass.desktop.domain.metadata.MetadataRepository
import com.vaultpass.desktop.domain.metadata.MetadataUnreadableException
import com.vaultpass.desktop.domain.metadata.VaultDatabaseFileState
import com.vaultpass.desktop.domain.models.VaultMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

/**
 * Implementation of MetadataRepository that reads/writes JSON to ~/.vaultpass/metadata.json
 */
class MetadataRepositoryImpl(
    override val metadataFile: File = com.vaultpass.desktop.data.platform.AppStorageConfig.metadataFile,
    private val vaultDirectory: File = com.vaultpass.desktop.data.platform.AppStorageConfig.dataDirectory,
    override val databaseFile: File = File(vaultDirectory, "vaultpass.db")
) : MetadataRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Set when [initializeMetadata] wrote a metadata file; read by [discardCreatedVault]. */
    private class CreatedVault(val databaseExisted: Boolean)

    @Volatile
    private var createdVault: CreatedVault? = null

    override suspend fun getMetadata(): VaultMetadata? = withContext(Dispatchers.IO) {
        if (Files.notExists(metadataFile.toPath())) {
            return@withContext null
        }

        return@withContext try {
            val content = metadataFile.readText()
            json.decodeFromString<VaultMetadata>(content)
        } catch (e: Exception) {
            val reason = if (e is IOException) {
                e.message ?: e.javaClass.simpleName
            } else {
                "the file is damaged or isn't a VaultPass vault file"
            }
            throw MetadataUnreadableException(metadataFile.absolutePath, reason, e)
        }
    }

    override suspend fun saveMetadata(metadata: VaultMetadata): Unit = withContext(Dispatchers.IO) {
        if (!vaultDirectory.exists()) {
            vaultDirectory.mkdirs()
        }

        val content = json.encodeToString(metadata)
        // Write a temp file and move it over the old one, so a failed write never leaves half a file.
        val tempFile = File(metadataFile.path + ".tmp")
        try {
            FileOutputStream(tempFile).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(
                tempFile.toPath(),
                metadataFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    override suspend fun initializeMetadata(): VaultMetadata {
        createdVault = null
        val databaseExisted = withContext(Dispatchers.IO) {
            ensureNoExistingVault()
            databaseFile.exists()
        }
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
        createdVault = CreatedVault(databaseExisted)
        return initialMetadata
    }

    override suspend fun discardCreatedVault(): Unit = withContext(Dispatchers.IO) {
        val created = createdVault ?: return@withContext
        createdVault = null
        // Entries mean the vault is in use; its key must stay.
        if (databaseFile.exists() && databaseHasEntries()) return@withContext
        Files.deleteIfExists(metadataFile.toPath())
        if (!created.databaseExisted && databaseFile.exists()) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                Files.deleteIfExists(File(databaseFile.path + suffix).toPath())
            }
        }
    }

    override suspend fun databaseFileState(): VaultDatabaseFileState = withContext(Dispatchers.IO) {
        when {
            !databaseFile.exists() -> VaultDatabaseFileState.MISSING
            databaseHasEntries() -> VaultDatabaseFileState.HAS_ENTRIES
            else -> VaultDatabaseFileState.NO_ENTRIES
        }
    }

    /** Refuses to create a vault where one already exists. */
    private fun ensureNoExistingVault() {
        if (!Files.notExists(metadataFile.toPath()) || (databaseFile.exists() && databaseHasEntries())) {
            throw IllegalStateException(
                "A vault already exists in ${vaultDirectory.absolutePath}. VaultPass won't create a new vault over it."
            )
        }
    }

    /** True when vault_entry has a row, or when the database can't be read (it might hold entries). */
    private fun databaseHasEntries(): Boolean {
        return try {
            val config = org.sqlite.SQLiteConfig().apply { setReadOnly(true) }
            DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}", config.toProperties()).use { conn ->
                conn.createStatement().use { statement ->
                    val hasTable = statement.executeQuery(
                        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'vault_entry'"
                    ).use { it.next() }
                    hasTable && statement.executeQuery("SELECT EXISTS(SELECT 1 FROM vault_entry)").use { rs ->
                        rs.next() && rs.getInt(1) == 1
                    }
                }
            }
        } catch (_: Exception) {
            true
        }
    }
}
