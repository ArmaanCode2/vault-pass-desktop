package com.vaultpass.desktop.domain.backup

import com.vaultpass.desktop.domain.backup.models.format.BackupPayload
import com.vaultpass.desktop.domain.backup.models.BackupFormat
import com.vaultpass.desktop.domain.backup.models.BackupResult

/**
 * Handles the serialization and deserialization of backup payloads.
 */
interface BackupSerializer {
    /**
     * Serializes a BackupPayload into raw bytes according to the specified format.
     */
    fun serialize(payload: BackupPayload, format: BackupFormat): BackupResult<ByteArray>

    /**
     * Deserializes raw bytes back into a BackupPayload.
     */
    fun deserialize(data: ByteArray, format: BackupFormat): BackupResult<BackupPayload>
}
