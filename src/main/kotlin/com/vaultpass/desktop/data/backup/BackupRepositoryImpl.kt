package com.vaultpass.desktop.data.backup

import com.vaultpass.desktop.domain.backup.BackupRepository
import com.vaultpass.desktop.domain.backup.errors.BackupException
import com.vaultpass.desktop.domain.backup.models.BackupResult
import com.vaultpass.desktop.domain.platform.FilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import com.vaultpass.desktop.domain.AppSettingsRepository
import com.vaultpass.desktop.domain.models.BackupMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class BackupRepositoryImpl(
    private val fileDialogProvider: FilePicker,
    private val appSettingsRepository: AppSettingsRepository
) : BackupRepository {
    override suspend fun saveBackup(data: ByteArray, destinationIdentifier: String): BackupResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val settings = appSettingsRepository.getSettings()
            var file: File? = null

            if (settings.backupMode == BackupMode.ASK_EVERY_TIME) {
                file = fileDialogProvider.showSaveFileDialog(
                    title = "Export VaultPass Backup",
                    defaultFileName = destinationIdentifier
                )
            } else {
                val folder = File(settings.backupFolder)
                if (!folder.exists()) {
                    folder.mkdirs()
                }
                
                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
                val baseName = "VaultPass_Backup_$timestamp"
                
                file = File(folder, "$baseName.vpb")
                var counter = 1
                while (file!!.exists()) {
                    file = File(folder, "$baseName ($counter).vpb")
                    counter++
                }
            }
            
            if (file == null) {
                return@withContext BackupResult.Failure(BackupException.StorageError("User cancelled export"))
            }

            file.writeBytes(data)
            
            val updatedSettings = settings.copy(
                latestBackupTimestamp = System.currentTimeMillis(),
                latestBackupFilename = file.name,
                lastUsedFolder = file.parentFile.absolutePath.replace("\\\\", "/")
            )
            appSettingsRepository.updateSettings(updatedSettings)
            
            BackupResult.Success(Unit)
        } catch (e: IOException) {
            BackupResult.Failure(BackupException.StorageError("Failed to write backup file to disk", e))
        } catch (e: Exception) {
            BackupResult.Failure(BackupException.StorageError("Unexpected error during backup save", e))
        }
    }

    override suspend fun loadBackup(sourceIdentifier: String): BackupResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val file = fileDialogProvider.showOpenFileDialog(
                title = "Import VaultPass Backup",
                allowedExtensions = listOf("vpb")
            )

            if (file == null) {
                return@withContext BackupResult.Failure(BackupException.StorageError("User cancelled import"))
            }

            if (!file.exists() || !file.canRead()) {
                return@withContext BackupResult.Failure(BackupException.StorageError("Cannot read the selected backup file"))
            }

            val data = file.readBytes()
            BackupResult.Success(data)
        } catch (e: IOException) {
            BackupResult.Failure(BackupException.StorageError("Failed to read backup file from disk", e))
        } catch (e: Exception) {
            BackupResult.Failure(BackupException.StorageError("Unexpected error during backup load", e))
        }
    }
}
