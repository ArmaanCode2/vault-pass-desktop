package com.vaultpass.desktop.domain.session

import java.io.File

/**
 * Handles unrecoverable session states and provides destructive or diagnostic
 * operations to restore the application to a healthy state.
 */
interface RecoveryManager {
    /**
     * Bundles the corrupted `.sqlite` and metadata files into an encrypted `.zip` 
     * for the user to send to support.
     * @param destination The file path where the archive should be saved.
     * @return True if successful.
     */
    suspend fun exportDiagnosticArchive(destination: File): Boolean

    /**
     * Overwrites the current corrupted database and metadata with a known-good `.vaultpass` archive.
     * @param backupFile The known-good backup file to restore from.
     * @return True if successful.
     */
    suspend fun restoreFromBackup(backupFile: File): Boolean

    /**
     * Dangerously wipes the `.sqlite` file, the metadata file, and clears all Preferences.
     * This completely resets the Desktop app to a clean install state.
     */
    suspend fun factoryReset()
}
