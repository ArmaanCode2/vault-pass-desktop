package com.vaultpass.desktop.domain.metadata

import com.vaultpass.desktop.domain.models.VaultMetadata
import java.io.File

/** What the vault database file holds, as far as a read-only look can tell. */
enum class VaultDatabaseFileState {
    MISSING,
    NO_ENTRIES,
    /** vault_entry has a row, or the file couldn't be read (it may hold entries). */
    HAS_ENTRIES
}

/**
 * Interface responsible exclusively for reading and writing the VaultMetadata.
 */
interface MetadataRepository {
    val metadataFile: File
    val databaseFile: File

    /** Looks at [databaseFile] through a read-only connection; the file is never created or changed. */
    suspend fun databaseFileState(): VaultDatabaseFileState

    /** Null when there is no metadata file; throws [MetadataUnreadableException] when it can't be read. */
    suspend fun getMetadata(): VaultMetadata?
    suspend fun saveMetadata(metadata: VaultMetadata)
    suspend fun initializeMetadata(): VaultMetadata

    /**
     * Undoes [initializeMetadata] after vault creation failed: deletes the metadata file it wrote,
     * and the database if it didn't exist before and has no entries. Does nothing otherwise.
     */
    suspend fun discardCreatedVault()
}

/** The metadata file exists but couldn't be read or parsed. */
class MetadataUnreadableException(
    val path: String,
    val reason: String,
    cause: Throwable? = null
) : Exception("Couldn't read $path: $reason", cause)
